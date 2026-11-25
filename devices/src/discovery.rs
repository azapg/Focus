//! Standard DNS-SD/mDNS; advertisements are hints, never authority to send credentials.
use crate::{identity, transport};
use mdns_sd::{ServiceDaemon, ServiceEvent};
use serde::Serialize;
use std::{
    collections::HashMap,
    net::SocketAddr,
    sync::{mpsc, Arc, Mutex},
    thread,
};
pub const SERVICE_TYPE: &str = "_focus-tv._tcp.local.";
pub const PHONE_SERVICE_TYPE: &str = "_focus-phone._tcp.local.";

#[derive(Clone, Debug)]
pub(crate) struct Peer {
    pub key: String,
    pub id: String,
    pub kind: String,
    pub name: String,
    pub address: String,
    pub fingerprint: String,
}
#[derive(Clone, Serialize)]
pub struct NearbyDevice {
    pub key: String,
    pub id: String,
    pub kind: String,
    pub name: String,
    pub paired: bool,
}
struct Cache {
    services: HashMap<String, Vec<String>>,
    peers: HashMap<String, Peer>,
}
pub(crate) struct Discovery {
    daemon: ServiceDaemon,
    cache: Arc<Mutex<Cache>>,
}
impl Discovery {
    pub fn start() -> Result<Self, String> {
        let daemon = ServiceDaemon::new()
            .map_err(|_| "Could not search this network. Check Wi-Fi and try again.")?;
        let tv_events = daemon.browse(SERVICE_TYPE).map_err(|e| e.to_string())?;
        let phone_events = daemon.browse(PHONE_SERVICE_TYPE).map_err(|e| e.to_string())?;
        let cache = Arc::new(Mutex::new(Cache {
            services: HashMap::new(),
            peers: HashMap::new(),
        }));
        let (send, receive) = mpsc::sync_channel::<(String, Vec<String>)>(16);
        let receive = Arc::new(Mutex::new(receive));
        for _ in 0..4 {
            let (receive, cache) = (receive.clone(), cache.clone());
            thread::spawn(move || loop {
                let job = receive.lock().unwrap().recv();
                let Ok((service, addresses)) = job else { break };
                let peer = addresses
                    .iter()
                    .find_map(|address| probe_peer(address).ok());
                let mut c = cache.lock().unwrap();
                if c.services.get(&service) != Some(&addresses) {
                    continue;
                }
                if let Some(peer) = peer {
                    c.peers.insert(service, peer);
                } else {
                    c.peers.remove(&service);
                }
            });
        }
        for events in [tv_events, phone_events] {
            let target = cache.clone();
            let send = send.clone();
            thread::spawn(move || {
                while let Ok(event) = events.recv() {
                    match event {
                        ServiceEvent::ServiceResolved(info) => {
                            if info.get_property_val_str("pairing") != Some("approval-v2") {
                                continue;
                            }
                            let service = info.get_fullname().to_owned();
                            let mut addresses: Vec<String> = info
                                .get_addresses()
                                .iter()
                                .map(|ip| SocketAddr::new(ip.to_ip_addr(), info.get_port()).to_string())
                                .filter(|ip| transport::address(ip).is_ok())
                                .collect();
                            addresses.sort_by_key(|s| (s.starts_with('['), s.clone()));
                            addresses.truncate(2);
                            if addresses.is_empty() {
                                continue;
                            }
                            let mut c = target.lock().unwrap();
                            if !c.services.contains_key(&service) && c.services.len() >= 32 {
                                continue;
                            }
                            c.services.insert(service.clone(), addresses.clone());
                            let _ = send.try_send((service, addresses));
                        }
                        ServiceEvent::ServiceRemoved(_, service) => {
                            let mut c = target.lock().unwrap();
                            c.services.remove(&service);
                            c.peers.remove(&service);
                        }
                        _ => {}
                    }
                }
            });
        }
        Ok(Self { daemon, cache })
    }
    pub fn peers(&self) -> Vec<Peer> {
        self.cache.lock().unwrap().peers.values().cloned().collect()
    }
}
impl Drop for Discovery {
    fn drop(&mut self) {
        let _ = self.daemon.shutdown();
    }
}
pub(crate) fn probe_peer(address: &str) -> Result<Peer, String> {
    let (hello, fingerprint) = transport::request(address, None, None, "/v1/hello", None)?;
    let (id, name, kind) = identity(&hello)?;
    if hello["pairing"] != "approval-v2" {
        return Err("Update Focus on this device before connecting".into());
    }
    let key = transport::fingerprint(format!("{id}\n{fingerprint}").as_bytes());
    Ok(Peer {
        key,
        id: id.to_owned(),
        kind: kind.to_owned(),
        name: name.to_owned(),
        address: address.to_owned(),
        fingerprint,
    })
}
