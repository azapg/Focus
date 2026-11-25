use serde_json::Value;
use std::{
    io::{BufRead, BufReader, Read, Write},
    net::{TcpListener, TcpStream},
    thread::{self, JoinHandle},
    time::Duration,
};

pub struct MockHttp {
    pub url: String,
    thread: JoinHandle<Vec<Request>>,
}

pub struct Request {
    pub line: String,
    pub headers: String,
    pub body: Value,
}

pub struct Reply {
    pub status: u16,
    pub body: Value,
    pub body_delay: Duration,
    pub retry_after: Option<&'static str>,
    pub(crate) raw_body: Option<&'static str>,
}

impl Reply {
    pub fn new(status: u16, body: Value) -> Self {
        Self {
            status,
            body,
            body_delay: Duration::ZERO,
            retry_after: None,
            raw_body: None,
        }
    }

    pub fn raw(status: u16, body: &'static str) -> Self {
        Self {
            raw_body: Some(body),
            ..Self::new(status, Value::Null)
        }
    }
}

impl MockHttp {
    pub fn start(replies: Vec<Reply>) -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let thread = thread::spawn(move || {
            replies.into_iter().map(|reply| {
                let start = std::time::Instant::now();
                let mut stream = loop {
                    match listener.accept() {
                        Ok((stream, _)) => break stream,
                        Err(e) if e.kind() == std::io::ErrorKind::WouldBlock
                            && start.elapsed() < Duration::from_secs(5) => thread::sleep(Duration::from_millis(2)),
                        Err(e) => panic!("Mock HTTP did not get an expected request: {e}"),
                    }
                };
                // Accepted sockets inherit nonblocking mode on Windows.
                stream.set_nonblocking(false).unwrap();
                stream.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
                let request = read_request(&stream);
                thread::spawn(move || {
                    let bytes = reply.raw_body.map_or_else(|| reply.body.to_string(), str::to_owned);
                    let retry_after = reply.retry_after.map_or(String::new(), |value| format!("Retry-After: {value}\r\n"));
                    let _ = write!(stream, "HTTP/1.1 {} Test\r\nContent-Type: application/json\r\nContent-Length: {}\r\n{retry_after}Connection: close\r\n\r\n",
                        reply.status, bytes.len());
                    thread::sleep(reply.body_delay);
                    let _ = write!(stream, "{bytes}");
                    request
                })
            }).collect::<Vec<_>>().into_iter().map(|worker| worker.join().unwrap()).collect()
        });
        Self { url, thread }
    }

    pub fn requests(self) -> Vec<Request> {
        self.thread.join().unwrap()
    }
}

fn read_request(stream: &TcpStream) -> Request {
    let mut reader = BufReader::new(stream);
    let mut line = String::new();
    reader.read_line(&mut line).unwrap();
    let mut headers = String::new();
    let mut content_length = 0;
    loop {
        let mut header = String::new();
        assert!(reader.read_line(&mut header).unwrap() > 0);
        if header == "\r\n" {
            break;
        }
        header.make_ascii_lowercase();
        if let Some(value) = header.strip_prefix("content-length:") {
            content_length = value.trim().parse().unwrap();
        }
        headers.push_str(&header);
    }
    let mut body = vec![0; content_length];
    reader.read_exact(&mut body).unwrap();
    Request {
        line: line.trim().into(),
        headers,
        body: if body.is_empty() {
            Value::Null
        } else {
            serde_json::from_slice(&body).unwrap()
        },
    }
}
