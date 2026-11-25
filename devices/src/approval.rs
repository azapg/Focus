use crate::{discovery::Peer, transport};
use serde::Serialize;
use serde_json::{json, Value};
use std::time::Instant;

#[derive(Clone)]
pub(crate) struct Pending {
    pub peer: Peer,
    pub request_id: String,
    pub secret: String,
    pub code: String,
    pub started: Instant,
}
#[derive(Serialize)]
pub struct ApprovalView {
    pub request_id: String,
    pub device_id: String,
    pub kind: String,
    pub name: String,
    pub code: String,
    pub status: String,
}
impl Pending {
    pub fn start(peer: Peer, manager_name: &str) -> Result<Self, String> {
        let nonce = random()?;
        let (reply, _) = transport::request(
            &peer.address,
            Some(&peer.fingerprint),
            None,
            "/v2/pair/request",
            Some(
                &json!({"commitment":transport::fingerprint(nonce.as_bytes()),"manager_name":manager_name}),
            ),
        )?;
        let request_id = hex_field(&reply, "request_id")?;
        let secret = hex_field(&reply, "poll_secret")?;
        let server_nonce = hex_field(&reply, "server_nonce")?;
        let code = comparison(&peer.fingerprint, &nonce, &server_nonce);
        let (revealed, _) = transport::request(
            &peer.address,
            Some(&peer.fingerprint),
            None,
            "/v2/pair/reveal",
            Some(&json!({"request_id":request_id,"poll_secret":secret,"client_nonce":nonce})),
        )?;
        if revealed["device_id"] != peer.id
            || revealed["code"] != code
            || revealed["status"] != "waiting"
        {
            return Err("TV identity check failed. Start again.".into());
        }
        Ok(Self {
            peer,
            request_id,
            secret,
            code,
            started: Instant::now(),
        })
    }
    pub fn request(&self, path: &str) -> Result<Value, String> {
        transport::request(
            &self.peer.address,
            Some(&self.peer.fingerprint),
            None,
            path,
            Some(&json!({"request_id":self.request_id,"poll_secret":self.secret})),
        )
        .map(|r| r.0)
    }
    pub fn view(&self, status: &str) -> ApprovalView {
        ApprovalView {
            request_id: self.request_id.clone(),
            device_id: self.peer.id.clone(),
            kind: self.peer.kind.clone(),
            name: self.peer.name.clone(),
            code: self.code.clone(),
            status: status.to_owned(),
        }
    }
}
pub(crate) fn random() -> Result<String, String> {
    let mut bytes = [0u8; 32];
    getrandom::getrandom(&mut bytes).map_err(|_| "Could not create a pairing identity")?;
    Ok(bytes.iter().map(|b| format!("{b:02x}")).collect())
}
fn hex_field(value: &Value, key: &str) -> Result<String, String> {
    value[key]
        .as_str()
        .filter(|s| {
            s.len() == 64
                && s.bytes()
                    .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
        })
        .map(str::to_owned)
        .ok_or("Invalid TV pairing response".into())
}
pub(crate) fn comparison(pin: &str, client_nonce: &str, server_nonce: &str) -> String {
    let hash = transport::fingerprint(
        format!("focus-approval-v2\n{pin}\n{client_nonce}\n{server_nonce}").as_bytes(),
    );
    format!(
        "{:06}",
        u32::from_str_radix(&hash[..8], 16).unwrap() % 1_000_000
    )
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn comparison_is_bound_to_certificate_and_both_nonces() {
        let p = "a".repeat(64);
        let a = "b".repeat(64);
        let b = "c".repeat(64);
        assert_eq!(comparison(&p, &a, &b), "973875");
        assert_ne!(comparison(&p, &a, &b), comparison(&b, &a, &p));
    }
}
