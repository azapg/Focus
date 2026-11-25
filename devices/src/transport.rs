//! A small, bounded HTTPS client for the versioned device API. No proxy or redirects.
use focus_telemetry as telemetry;
use rustls::{
    client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier},
    pki_types::{CertificateDer, ServerName, UnixTime},
    DigitallySignedStruct, SignatureScheme,
};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::{
    io::{BufRead, BufReader, Read, Write},
    net::{IpAddr, SocketAddr, TcpStream},
    sync::{Arc, Mutex},
    time::Duration,
};

pub const MAX_BODY: u64 = 8 * 1024 * 1024;
pub fn fingerprint(cert: &[u8]) -> String {
    Sha256::digest(cert)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

/// Numeric LAN addresses only. Other connection types can supply their own address policy later.
pub fn address(value: &str) -> Result<SocketAddr, String> {
    let socket: SocketAddr = value
        .parse()
        .map_err(|_| "Enter the TV's IP address and port, such as 192.168.1.20:47832")?;
    let local = match socket.ip() {
        IpAddr::V4(ip) => ip.is_private() || ip.is_loopback() || ip.is_link_local(),
        IpAddr::V6(ip) => ip.is_loopback() || (ip.segments()[0] & 0xfe00 == 0xfc00),
    };
    if !local || socket.port() == 0 {
        return Err("Use a local-network IP address and a nonzero port".into());
    }
    Ok(socket)
}

const PIN_MISMATCH: &str = "TV identity changed. Pair again on the TV.";

#[derive(Debug)]
struct Pin {
    expected: Option<String>,
    observed: Arc<Mutex<Option<String>>>,
    provider: Arc<rustls::crypto::CryptoProvider>,
}
impl ServerCertVerifier for Pin {
    fn verify_server_cert(
        &self,
        cert: &CertificateDer<'_>,
        _: &[CertificateDer<'_>],
        _: &ServerName<'_>,
        _: &[u8],
        _: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        let found = fingerprint(cert.as_ref());
        if self.expected.as_ref().is_some_and(|pin| pin != &found) {
            return Err(rustls::Error::General(
                PIN_MISMATCH.into(),
            ));
        }
        *self.observed.lock().unwrap() = Some(found);
        // Only the public hello probe may omit a pin. Pairing and every authenticated request pin it.
        Ok(ServerCertVerified::assertion())
    }
    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }
    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) struct RequestOutcome {
    pub stage: &'static str,
    pub cause: &'static str,
    pub status: Option<u16>,
}

impl Default for RequestOutcome {
    fn default() -> Self {
        Self { stage: "connect", cause: "lan_unreachable", status: None }
    }
}

pub fn request(
    endpoint: &str,
    pin: Option<&str>,
    token: Option<&str>,
    path: &str,
    body: Option<&Value>,
) -> Result<(Value, String), String> {
    if pin.is_none() && body.is_some() {
        return Err("Pairing requires a confirmed TV identity".into());
    }
    request_with_body(endpoint, pin, token, path,
        |_| Ok(body.map(Value::to_string)), &mut RequestOutcome::default())
}

pub(crate) fn request_with_body(
    endpoint: &str,
    pin: Option<&str>,
    token: Option<&str>,
    path: &str,
    body: impl FnOnce(SocketAddr) -> Result<Option<String>, String>,
    outcome: &mut RequestOutcome,
) -> Result<(Value, String), String> {
    if pin.is_none() && (path != "/v1/hello" || token.is_some()) {
        return Err("Pairing requires a confirmed TV identity".into());
    }
    if token.is_some_and(|t| t.len() != 64 || !t.bytes().all(|b| b.is_ascii_hexdigit())) {
        return Err("Invalid device credential".into());
    }
    let socket = address(endpoint)?;
    let observed = Arc::new(Mutex::new(None));
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let tls = rustls::ClientConfig::builder_with_provider(provider.clone())
        .with_safe_default_protocol_versions()
        .map_err(|e| e.to_string())?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(Pin {
            expected: pin.map(str::to_owned),
            observed: observed.clone(),
            provider,
        }))
        .with_no_client_auth();
    telemetry::DEVICE_REQUEST.count(1);
    let tcp = TcpStream::connect_timeout(&socket, Duration::from_secs(4))
        .map_err(|error| {
            outcome.cause = crate::events::io_cause(&error, "lan_unreachable");
            "TV unreachable. Check its address and Wi-Fi connection."
        })?;
    tcp.set_read_timeout(Some(Duration::from_secs(8)))
        .map_err(|e| e.to_string())?;
    tcp.set_write_timeout(Some(Duration::from_secs(8)))
        .map_err(|e| e.to_string())?;
    let connection =
        rustls::ClientConnection::new(Arc::new(tls), ServerName::IpAddress(socket.ip().into()))
            .map_err(|e| e.to_string())?;
    let mut stream = rustls::StreamOwned::new(connection, tcp);
    outcome.stage = "tls";
    outcome.cause = "lan_rejected";
    while stream.conn.is_handshaking() {
        stream.conn.complete_io(&mut stream.sock).map_err(|error| {
            outcome.cause = if error.get_ref().and_then(|e| e.downcast_ref::<rustls::Error>())
                .is_some_and(|e| matches!(e, rustls::Error::General(message) if message == PIN_MISMATCH))
            {
                "lan_tls_mismatch"
            } else {
                crate::events::io_cause(&error, "lan_rejected")
            };
            format!("Secure TV connection failed: {error}")
        })?;
    }
    outcome.stage = "request";
    let body = body(stream.sock.local_addr().map_err(|e| e.to_string())?)?;
    if pin.is_none() && body.is_some() {
        return Err("Pairing requires a confirmed TV identity".into());
    }
    outcome.cause = "lan_invalid_reply";
    let method = if body.is_some() { "POST" } else { "GET" };
    let payload = body.unwrap_or_default();
    let auth = token
        .map(|t| format!("Authorization: Bearer {t}\r\n"))
        .unwrap_or_default();
    let message = format!("{method} {path} HTTP/1.1\r\nHost: {socket}\r\n{auth}Content-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{payload}", payload.len());
    stream
        .write_all(message.as_bytes())
        .map_err(|e| {
            outcome.cause = crate::events::io_cause(&e, "lan_unreachable");
            format!("Secure TV connection failed: {e}")
        })?;
    stream.flush().map_err(|e| {
        outcome.cause = crate::events::io_cause(&e, "lan_unreachable");
        e.to_string()
    })?;
    telemetry::DEVICE_TX.count(payload.len() as u64);
    let mut input = BufReader::new(stream);
    let mut headers = Vec::new();
    while !headers.ends_with(b"\r\n\r\n") {
        if headers.len() >= 8192 {
            return Err("TV response headers are too large".into());
        }
        let buf = input.fill_buf().map_err(|e| {
            outcome.cause = crate::events::io_cause(&e, "lan_invalid_reply");
            e.to_string()
        })?;
        if buf.is_empty() {
            return Err("TV closed the connection before confirming the request".into());
        }
        headers.push(buf[0]);
        input.consume(1);
    }
    let header = std::str::from_utf8(&headers).map_err(|_| "Invalid TV response")?;
    let mut lines = header.split("\r\n");
    let status = lines
        .next()
        .and_then(|l| l.split_whitespace().nth(1))
        .and_then(|s| s.parse::<u16>().ok())
        .ok_or("Invalid TV response status")?;
    outcome.status = Some(status);
    if status == 401 { outcome.stage = "auth"; }
    if status != 200 { outcome.cause = crate::events::status_cause(status); }
    let mut length = None;
    for line in lines.filter(|l| !l.is_empty()) {
        let (key, value) = line.split_once(':').ok_or("Invalid TV response header")?;
        if key.eq_ignore_ascii_case("transfer-encoding") {
            return Err("Unsupported TV response framing".into());
        }
        if key.eq_ignore_ascii_case("content-length") {
            if length.is_some() {
                return Err("Duplicate TV response length".into());
            }
            length = Some(
                value
                    .trim()
                    .parse::<u64>()
                    .map_err(|_| "Invalid TV response length")?,
            );
        }
    }
    let length = length
        .filter(|n| *n <= MAX_BODY)
        .ok_or("TV response is too large or has no length")?;
    let mut bytes = vec![0; length as usize];
    input
        .read_exact(&mut bytes)
        .map_err(|e| {
            outcome.cause = crate::events::io_cause(&e, "lan_invalid_reply");
            "TV did not confirm the request. Refresh before trying again."
        })?;
    telemetry::DEVICE_RX.count(bytes.len() as u64);
    let result: Value = serde_json::from_slice(&bytes).map_err(|_| "Invalid TV JSON")?;
    if status != 200 {
        return Err(result["error"]
            .as_str()
            .unwrap_or("TV refused the request")
            .to_owned());
    }
    let pin = observed
        .lock()
        .unwrap()
        .clone()
        .ok_or("TV did not provide an identity")?;
    outcome.cause = "ok";
    Ok((result, pin))
}
