use futures_util::{stream::FuturesUnordered, StreamExt};
use regex::Regex;
use reqwest::Client;
use ring::rand::{SecureRandom, SystemRandom};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    cell::Cell,
    collections::{BTreeMap, BTreeSet},
    fs,
    path::Path,
    sync::LazyLock,
    time::{Duration, Instant, SystemTime},
};

pub const DEFAULT_MODEL: &str = "anthropic/claude-haiku-5.5";
pub const LUNA_MODEL: &str = "openai/gpt-6-luna";
pub const GATEWAY_ENDPOINT: &str = "https://ai-gateway.vercel.sh/v1/chat/completions";
const MODEL_FULL_PAUSE_MAX_MINUTES: u64 = 5;
const MAX_RESPONSE: u64 = 64 * 1024;
const MAX_SERVICES: usize = 3;
pub const MIN_REASON_CHARS: usize = 3;
pub const MINUTE_MS: u64 = 60_000;
pub const PROMPT: &str = include_str!("judge_prompt.txt");

pub use crate::pause_history::CheckIn;

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Request {
    pub text: String,
    pub check_in: Option<CheckIn>,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Action {
    AllowServices,
    FullPause,
    Peek,
    CounterOffer,
    Deny,
}

#[derive(Clone, Debug, Serialize)]
pub struct Decision {
    pub action: Action,
    pub services: Vec<String>,
    pub minutes: u64,
    pub message: String,
    pub counter_offer: Option<String>,
}

#[derive(Clone, Copy)]
pub struct Policy {
    pub daily_limit_minutes: u64,
    pub max_pause_minutes: u64,
    pub wait_seconds: u64,
    pub used_ms: u64,
    pub count: usize,
}

impl Policy {
    // PausePolicy gives a one-minute exception when the shared soft limit is used up.
    pub fn cap(self) -> u64 {
        let left = (self.daily_limit_minutes * MINUTE_MS).saturating_sub(self.used_ms);
        self.max_pause_minutes.min(left / MINUTE_MS).max(1)
    }

    pub fn manual_choices(self) -> Vec<u64> {
        crate::ALLOWED_MINUTES
            .into_iter()
            .filter(|&m| m <= self.cap())
            .collect()
    }

    pub fn friction(self, fallback: bool) -> Friction {
        // PausePolicy uses 1-indexed thresholds (third and fifth pause).
        let wait = fallback || self.count >= 2;
        Friction {
            wait_ms: if fallback {
                self.wait_seconds.max(5) * 1000
            } else if wait {
                self.wait_seconds * 1000
            } else {
                0
            },
            hold_ms: if wait { 3000 } else { 2000 },
            requires_plan: fallback || self.count >= 4,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
pub struct Friction {
    pub wait_ms: u64,
    pub hold_ms: u64,
    pub requires_plan: bool,
}

pub struct Catalog(BTreeMap<String, BTreeSet<String>>);

impl Catalog {
    pub fn seed(dir: &Path) -> Result<(), String> {
        let path = dir.join("services.json");
        if !path.exists() {
            focus_browser_host::atomic_write(&path, include_bytes!("services.json"))
                .map_err(|e| format!("Could not seed services.json: {e}"))?;
        }
        Ok(())
    }

    pub fn load(dir: &Path) -> Result<Self, String> {
        let text = match fs::read_to_string(dir.join("services.json")) {
            Ok(text) => text,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                include_str!("services.json").into()
            }
            Err(_) => return Err("Could not read services.json".into()),
        };
        Self::parse(&text)
    }

    pub fn entries(&self) -> &BTreeMap<String, BTreeSet<String>> {
        &self.0
    }

    fn parse(text: &str) -> Result<Self, String> {
        let invalid = || "Invalid services.json".to_string();
        if text.len() > 32 * 1024 {
            return Err(invalid());
        }
        let raw: BTreeMap<String, Vec<String>> =
            serde_json::from_str(text).map_err(|_| invalid())?;
        if raw.is_empty() || raw.len() > 64 {
            return Err(invalid());
        }
        let mut services = BTreeMap::new();
        for (service, names) in raw {
            if service.is_empty()
                || service.len() > 40
                || !service
                    .bytes()
                    .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_')
                || names.is_empty()
                || names.len() > 10
            {
                return Err(invalid());
            }
            let mut domains = BTreeSet::new();
            for name in names {
                // NextDNS plain domain entries already include every subdomain.
                let host = name.trim().to_ascii_lowercase();
                let host = host.strip_prefix("*.").unwrap_or(&host);
                if !valid_domain(host) {
                    return Err(invalid());
                }
                domains.insert(host.to_owned());
            }
            services.insert(service, domains);
        }
        Ok(Self(services))
    }

    pub fn services(&self) -> Vec<&str> {
        self.0.keys().map(String::as_str).collect()
    }

    pub fn domains(&self, services: &[String]) -> Result<Vec<String>, String> {
        let mut domains = BTreeSet::new();
        for service in services {
            domains.extend(
                self.0
                    .get(service)
                    .ok_or("Unknown service")?
                    .iter()
                    .cloned(),
            );
        }
        Ok(domains.into_iter().collect())
    }

    pub fn matching_services(&self, name: &str) -> BTreeSet<&str> {
        let name = name.to_ascii_lowercase();
        self.0
            .iter()
            .filter(|(service, domains)| {
                service.as_str() == name
                    || domains.iter().any(|domain| {
                        name == *domain
                            || name
                                .strip_suffix(domain)
                                .is_some_and(|prefix| prefix.ends_with('.'))
                    })
            })
            .map(|(service, _)| service.as_str())
            .collect()
    }
}

pub fn valid_domain(host: &str) -> bool {
    host.len() <= 253
        && host.contains('.')
        && host.parse::<std::net::IpAddr>().is_err()
        && host
            .rsplit('.')
            .next()
            .is_some_and(|label| label.bytes().any(|b| b.is_ascii_lowercase()))
        && host.split('.').all(|label| {
            !label.is_empty()
                && label.len() <= 63
                && !label.starts_with('-')
                && !label.ends_with('-')
                && label
                    .bytes()
                    .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
        })
}

// User-authored text may contain full links. Drop credentials, ports, paths,
// queries and fragments before sending it, including links typed without a scheme.
static LINK: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(
    r#"(?i)(?:\b[a-z][a-z0-9+.-]*://|//|\b(?:https?|ftp|file|mailto|data|javascript|about|blob|magnet|tel|sms):|\b[\p{L}\p{N}._%+-]+(?::[^\s@/?#<>"`]+)?@(?:\[[\da-f:.]+\]|[\p{L}\p{N}-]+(?:\.[\p{L}\p{N}-]+)*\.?)|(?:\b[\p{L}\p{N}-]+\.)+[\p{L}\p{N}][\p{L}\p{N}-]*\.?[/?#:]|(?:\blocalhost|\[[\da-f:.]+\])[/?#:])[^\s<>"`]*"#
).expect("link pattern")
});

fn link_host(link: &str) -> Option<String> {
    let host = if link.starts_with("//") {
        focus_browser_host::url_host(&format!("https:{link}"))
    } else if link.contains("://")
        || ["http:", "https:", "ftp:"]
            .iter()
            .any(|scheme| link.to_ascii_lowercase().starts_with(scheme))
        || !link.contains('.')
    {
        focus_browser_host::url_host(link)
    } else {
        focus_browser_host::url_host(&format!("https://{link}"))
    }?;
    let host = host.trim_end_matches('.');
    valid_domain(host).then(|| host.to_owned())
}

pub fn summary_text(text: &str) -> String {
    LINK.replace_all(text, |cap: &regex::Captures<'_>| {
        link_host(&cap[0]).unwrap_or_else(|| "[link]".into())
    })
    .into_owned()
}

pub fn peek_target(text: &str) -> Option<String> {
    LINK.find_iter(text).find_map(|m| {
        let link = m.as_str().trim_end_matches(['.', ',', ')', '!', ';']);
        let lower = link.to_ascii_lowercase();
        if (lower.starts_with("https://") || lower.starts_with("http://"))
            && link_host(link).is_some()
        {
            Some(link.to_string())
        } else {
            None
        }
    })
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
enum AttemptFailure {
    Configuration,
    Timeout,
    Transport,
    Http,
    Envelope,
    FinishReason,
    Refusal,
    NotText,
    Parse,
    ServicesUnknown,
    ServicesTooMany,
    ServicesEmpty,
    MinutesMismatch,
    PeekWithoutTarget,
    CounterOfferMismatch,
    Cancelled,
}

impl AttemptFailure {
    fn wire(self) -> Failure {
        match self {
            Self::Configuration => Failure::Configuration,
            Self::Timeout | Self::Cancelled => Failure::Timeout,
            Self::Transport | Self::Http => Failure::Unavailable,
            _ => Failure::Invalid,
        }
    }

    fn retryable(self, status: Option<u16>) -> bool {
        match self {
            Self::Http => matches!(status, Some(408 | 425 | 429 | 500..=599)),
            Self::Configuration | Self::Refusal | Self::Cancelled => false,
            _ => true,
        }
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Sample {
    action: Action,
    services: Vec<String>,
    minutes: i128,
    message: String,
    #[serde(deserialize_with = "Deserialize::deserialize")]
    counter_offer: Option<String>,
}

impl Sample {
    fn validate(
        self,
        catalog: &Catalog,
        policy: Policy,
        has_peek_target: bool,
    ) -> Result<Decision, AttemptFailure> {
        use AttemptFailure::*;
        let mut decision = Decision {
            action: self.action,
            services: self.services,
            minutes: 0,
            message: self.message,
            counter_offer: self.counter_offer,
        };
        let mut seen = BTreeSet::new();
        if decision.action == Action::AllowServices {
            decision
                .services
                .retain(|service| seen.insert(service.clone()));
            if decision.services.is_empty() {
                return Err(ServicesEmpty);
            }
            if decision.services.len() > MAX_SERVICES {
                return Err(ServicesTooMany);
            }
            if catalog.domains(&decision.services).is_err() {
                return Err(ServicesUnknown);
            }
        } else {
            decision.services.clear();
        }
        if decision.action == Action::Peek && !has_peek_target {
            return Err(PeekWithoutTarget);
        }

        if matches!(decision.action, Action::AllowServices | Action::FullPause) {
            if self.minutes <= 0 {
                return Err(MinutesMismatch);
            }
            decision.minutes = u64::try_from(self.minutes)
                .unwrap_or(u64::MAX)
                .min(policy.cap());
            if decision.action == Action::FullPause {
                decision.minutes = decision.minutes.min(MODEL_FULL_PAUSE_MAX_MINUTES);
            }
        }

        decision.message = decision.message.trim().chars().take(500).collect();
        if decision.message.is_empty() {
            decision.message = match decision.action {
                Action::AllowServices => "Scoped pause suggested.",
                Action::FullPause => "Full pause suggested.",
                Action::Peek => "Peek suggested.",
                Action::CounterOffer | Action::Deny => "Pause declined.",
            }
            .into();
        }
        if decision.action == Action::CounterOffer {
            decision.counter_offer = decision
                .counter_offer
                .map(|text| text.trim().to_owned())
                .filter(|text| !text.is_empty() && text.chars().count() <= 500);
            if decision.counter_offer.is_none() {
                return Err(CounterOfferMismatch);
            }
        } else {
            decision.counter_offer = None;
        }
        Ok(decision)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Failure {
    Configuration,
    Timeout,
    Unavailable,
    Invalid,
}

#[derive(Deserialize)]
struct RetryPolicy {
    max_attempts: usize,
    attempt_ms: u64,
    connect_ms: u64,
    hedge_ms: u64,
    backoff_ms: u64,
    jitter_ms: u64,
    response_margin_ms: u64,
    legacy_phone_read_ms: u64,
    legacy_phone_overall_ms: u64,
    legacy_phone_presend_ms: u64,
    decision_commit_margin_ms: u64,
    laptop_overall_ms: u64,
    min_retry_window_ms: u64,
}

static RETRY: LazyLock<RetryPolicy> = LazyLock::new(|| {
    serde_json::from_str(include_str!("../../shared/ask-retry.json")).expect("Ask retry policy")
});

pub fn ask_budget(phone_wait_ms: Option<u64>, remote: bool) -> Duration {
    let ms = if remote {
        phone_wait_ms
            .unwrap_or_else(|| RETRY.legacy_phone_read_ms.min(
                RETRY.legacy_phone_overall_ms - RETRY.legacy_phone_presend_ms,
            ))
            .saturating_sub(RETRY.response_margin_ms)
            .min(RETRY.laptop_overall_ms)
    } else {
        RETRY.laptop_overall_ms
    };
    Duration::from_millis(ms)
}

pub fn decision_commit_margin() -> Duration {
    Duration::from_millis(RETRY.decision_commit_margin_ms)
}

#[derive(Serialize)]
pub struct Attempt {
    failure: Option<AttemptFailure>,
    latency_ms: u64,
    status_class: Option<u16>,
    #[serde(skip)]
    finished_at: Instant,
}

impl Attempt {
    pub fn refused(&self) -> bool {
        self.failure == Some(AttemptFailure::Refusal)
    }

    fn diagnostic(&self, failure: Option<Failure>) -> Option<(&'static str, Option<u16>)> {
        use AttemptFailure::*;
        if self.failure.map(AttemptFailure::wire) != failure || self.failure == Some(Cancelled) {
            return None;
        }
        let cause = match self.failure {
            None => "ok",
            Some(Timeout) => "gateway_timeout",
            Some(Transport) => "gateway_transport",
            Some(Http) if self.status_class == Some(4) => "gateway_http_4xx",
            Some(Http) if self.status_class == Some(5) => "gateway_http_5xx",
            Some(Refusal) => "gateway_refused",
            _ => "gateway_invalid_reply",
        };
        Some((cause, self.status_class))
    }
}

pub fn gateway_diagnostic(attempts: &[Attempt], failure: Option<Failure>) -> Option<(&'static str, Option<u16>)> {
    attempts.iter().filter_map(|attempt| Some((attempt.finished_at, attempt.diagnostic(failure)?)))
        .max_by_key(|(finished_at, _)| *finished_at).map(|(_, diagnostic)| diagnostic)
}

struct AttemptError {
    kind: AttemptFailure,
    retry_after: Duration,
}

impl From<AttemptFailure> for AttemptError {
    fn from(kind: AttemptFailure) -> Self {
        Self {
            kind,
            retry_after: Duration::ZERO,
        }
    }
}

pub struct Gateway {
    client: Client,
    endpoint: String,
    attempt_timeout: Duration,
    hedge_delay: Duration,
}

static GATEWAY: LazyLock<Result<Gateway, Failure>> = LazyLock::new(|| {
    Gateway::at(
        GATEWAY_ENDPOINT,
        Duration::from_millis(RETRY.attempt_ms),
        Duration::from_millis(RETRY.hedge_ms),
    )
});

static RUNTIME: LazyLock<Result<tokio::runtime::Runtime, Failure>> = LazyLock::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(1)
        .enable_all()
        .build()
        .map_err(|_| Failure::Configuration)
});

impl Gateway {
    pub fn shared() -> Result<&'static Self, Failure> {
        GATEWAY.as_ref().map_err(|failure| *failure)
    }

    pub(crate) fn at(
        endpoint: &str,
        attempt_timeout: Duration,
        hedge_delay: Duration,
    ) -> Result<Self, Failure> {
        let client = Client::builder()
            .connect_timeout(Duration::from_millis(RETRY.connect_ms))
            .redirect(reqwest::redirect::Policy::none())
            .retry(reqwest::retry::never())
            .build()
            .map_err(|_| Failure::Configuration)?;
        Ok(Self {
            client,
            endpoint: endpoint.into(),
            attempt_timeout,
            hedge_delay,
        })
    }

    pub fn decide(
        &self,
        key: &str,
        model: &str,
        context: &Value,
        catalog: &Catalog,
        policy: Policy,
        has_peek_target: bool,
        budget: Duration,
        attempts: &mut Vec<Attempt>,
    ) -> Result<Decision, Failure> {
        let mut actions = vec!["allow_services", "full_pause", "counter_offer", "deny"];
        if has_peek_target {
            actions.push("peek");
        }
        let mut body = json!({
            "model": model,
            "stream": false,
            "messages": [
                { "role": "system", "content": PROMPT },
                { "role": "user", "content": context.to_string() }
            ],
            "response_format": {
                "type": "json_schema",
                "json_schema": {
                    "name": "pause_decision",
                    "strict": true,
                    "schema": {
                        "type": "object",
                        "properties": {
                            "action": { "type": "string", "enum": actions, "description": "The proposed response to the request." },
                            "services": { "type": "array", "items": { "type": "string", "enum": catalog.services() }, "description": "One to three distinct services for allow_services. Empty for other actions." },
                            "minutes": { "type": "integer", "description": "Positive for allow_services or full_pause. Zero for other actions." },
                            "message": { "type": "string", "description": "A short explanation of the proposal." },
                            "counter_offer": { "type": ["string", "null"], "description": "One concrete next step for counter_offer. Null for other actions." }
                        },
                        "required": ["action", "services", "minutes", "message", "counter_offer"],
                        "additionalProperties": false
                    }
                }
            }
        });
        if matches!(model, DEFAULT_MODEL | LUNA_MODEL) {
            body["reasoning_effort"] = json!("none");
        }
        let runtime = RUNTIME.as_ref().map_err(|failure| *failure)?;
        let validate = |bytes: &[u8]| {
            let response: Value =
                serde_json::from_slice(bytes).map_err(|_| AttemptFailure::Envelope)?;
            let choice = response["choices"].get(0).ok_or(AttemptFailure::Envelope)?;
            if choice["finish_reason"] == "content_filter"
                || !choice["message"]["refusal"].is_null()
            {
                return Err(AttemptFailure::Refusal);
            }
            if !choice["message"].is_object() {
                return Err(AttemptFailure::Envelope);
            }
            if choice["finish_reason"] != "stop" {
                return Err(AttemptFailure::FinishReason);
            }
            let content = choice["message"]["content"]
                .as_str()
                .ok_or(AttemptFailure::NotText)?;
            let decision: Sample =
                serde_json::from_str(content).map_err(|_| AttemptFailure::Parse)?;
            decision.validate(catalog, policy, has_peek_target)
        };
        runtime.block_on(self.run(key, &body, budget, attempts, validate))
    }

    async fn run(
        &self,
        key: &str,
        body: &Value,
        budget: Duration,
        attempts: &mut Vec<Attempt>,
        validate: impl Fn(&[u8]) -> Result<Decision, AttemptFailure>,
    ) -> Result<Decision, Failure> {
        use tokio::time::{sleep_until, timeout_at, Instant as Deadline};
        let deadline = Deadline::now() + budget;
        let mut hedge = Some(Deadline::now() + self.hedge_delay);
        let statuses = (0..RETRY.max_attempts)
            .map(|_| Cell::new(None))
            .collect::<Vec<_>>();
        let mut started = Vec::new();
        let mut completed = Vec::new();
        let mut pending = FuturesUnordered::new();
        let mut invalid = 0;
        let mut last = Failure::Timeout;
        let mut retry_at = Deadline::now();
        let min_window = Duration::from_millis(RETRY.min_retry_window_ms);
        let result = loop {
            let now = Deadline::now();
            if now >= deadline {
                break Err(if pending.is_empty() {
                    last
                } else {
                    Failure::Timeout
                });
            }
            let launch_at = if pending.is_empty() {
                Some(retry_at)
            } else {
                hedge.map(|at| at.max(retry_at))
            }
            .filter(|at| {
                started.len() < RETRY.max_attempts
                    && (started.is_empty() || (*at).max(now) + min_window <= deadline)
            });
            if launch_at.is_some_and(|at| at <= now) {
                let index = started.len();
                let status = &statuses[index];
                let validate = &validate;
                started.push(Instant::now());
                completed.push(None);
                if index > 0 {
                    hedge = None;
                }
                pending.push(async move {
                    let response = self.attempt(key, body, status, validate).await;
                    (index, response)
                });
                continue;
            }
            if pending.is_empty() {
                let Some(at) = launch_at else { break Err(last) };
                sleep_until(at).await;
                continue;
            }
            let wake = launch_at.unwrap_or(deadline).min(deadline);
            let Ok(Some((index, result))) = timeout_at(wake, pending.next()).await else {
                continue;
            };
            if Deadline::now() >= deadline {
                completed[index] = Some((started[index].elapsed(), Some(AttemptFailure::Timeout)));
                break Err(Failure::Timeout);
            }
            completed[index] = Some((
                started[index].elapsed(),
                result.as_ref().err().map(|error| error.kind),
            ));
            match result {
                Ok(decision) => break Ok(decision),
                Err(error) => {
                    last = error.kind.wire();
                    if last == Failure::Invalid {
                        invalid += 1;
                    }
                    if invalid >= 2 || !error.kind.retryable(statuses[index].get()) {
                        break Err(last);
                    }
                    let mut random = [0u8; 1];
                    let _ = SystemRandom::new().fill(&mut random);
                    let backoff = Duration::from_millis(
                        RETRY.backoff_ms + u64::from(random[0]) % (RETRY.jitter_ms + 1),
                    );
                    retry_at = retry_at.max(Deadline::now() + backoff.max(error.retry_after));
                }
            }
        };
        drop(pending);
        for (index, time) in started.into_iter().enumerate() {
            let (elapsed, failure) = completed[index].unwrap_or_else(|| {
                (
                    time.elapsed(),
                    Some(if Deadline::now() >= deadline {
                        AttemptFailure::Timeout
                    } else {
                        AttemptFailure::Cancelled
                    }),
                )
            });
            attempts.push(Attempt {
                failure,
                latency_ms: elapsed.as_millis() as u64,
                status_class: statuses[index].get().map(|status| status / 100),
                finished_at: time + elapsed,
            });
        }
        result
    }

    async fn attempt(
        &self,
        key: &str,
        body: &Value,
        status: &Cell<Option<u16>>,
        validate: &impl Fn(&[u8]) -> Result<Decision, AttemptFailure>,
    ) -> Result<Decision, AttemptError> {
        use AttemptFailure::*;
        let mut response = self
            .client
            .post(&self.endpoint)
            .bearer_auth(key)
            .json(body)
            .timeout(self.attempt_timeout)
            .send()
            .await
            .map_err(|error| {
                if error.is_timeout() {
                    Timeout
                } else if error.is_builder() {
                    Configuration
                } else {
                    Transport
                }
            })?;
        status.set(Some(response.status().as_u16()));
        if !response.status().is_success() {
            let retry_after = response
                .headers()
                .get(reqwest::header::RETRY_AFTER)
                .and_then(|header| header.to_str().ok())
                .map_or(Duration::ZERO, retry_after);
            return Err(AttemptError {
                kind: Http,
                retry_after,
            });
        }
        let mut bytes = Vec::new();
        while let Some(chunk) = response.chunk().await.map_err(|error| {
            if error.is_timeout() {
                Timeout
            } else {
                Envelope
            }
        })? {
            if bytes.len() as u64 + chunk.len() as u64 > MAX_RESPONSE {
                return Err(Envelope.into());
            }
            bytes.extend_from_slice(&chunk);
        }
        Ok(validate(&bytes)?)
    }
}

fn retry_after(value: &str) -> Duration {
    // Cap before adding to an Instant. A delay above the largest budget cannot fit.
    let cap = Duration::from_millis(RETRY.laptop_overall_ms + 1);
    let value = value.trim();
    if !value.is_empty() && value.bytes().all(|byte| byte.is_ascii_digit()) {
        return value
            .parse::<u64>()
            .map_or(cap, |seconds| Duration::from_secs(seconds).min(cap));
    }
    chrono::DateTime::parse_from_rfc2822(value)
        .ok()
        .and_then(|date| {
            SystemTime::from(date)
                .duration_since(SystemTime::now())
                .ok()
        })
        .unwrap_or_default()
        .min(cap)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mock_http::{MockHttp, Reply};

    fn policy(used_ms: u64) -> Policy {
        Policy {
            daily_limit_minutes: 60,
            max_pause_minutes: 10,
            wait_seconds: 15,
            used_ms,
            count: 0,
        }
    }

    fn output(action: &str, services: Value, minutes: Value) -> Value {
        json!({ "action": action, "services": services, "minutes": minutes, "message": "For your task", "counter_offer": null })
    }

    fn completion(value: &Value) -> Value {
        json!({ "choices": [{"finish_reason":"stop", "message": {"content": value.to_string()}}] })
    }

    fn call(
        replies: Vec<Reply>,
        policy: Policy,
        timeout: Duration,
        hedge: Duration,
        budget: Duration,
    ) -> (Result<Decision, Failure>, Vec<Attempt>, Duration) {
        let mock = MockHttp::start(replies);
        let catalog = Catalog::parse(include_str!("services.json")).unwrap();
        let gateway =
            Gateway::at(&format!("{}/v1/chat/completions", mock.url), timeout, hedge).unwrap();
        let mut attempts = Vec::new();
        let started = Instant::now();
        let result = gateway.decide(
            "test-key",
            DEFAULT_MODEL,
            &json!({"request":"Discord task"}),
            &catalog,
            policy,
            false,
            budget,
            &mut attempts,
        );
        let elapsed = started.elapsed();
        let requests = mock.requests();
        let request = &requests[0];
        assert!(request.headers.contains("authorization: bearer test-key"));
        assert_eq!(request.line, "POST /v1/chat/completions HTTP/1.1");
        assert_eq!(request.body["messages"][0]["content"], PROMPT);
        assert_eq!(request.body["model"], DEFAULT_MODEL);
        assert_eq!(request.body["reasoning_effort"], "none");
        assert_eq!(request.body["response_format"]["type"], "json_schema");
        assert!(
            request.body["response_format"]["json_schema"]["schema"]["properties"]["services"]
                ["items"]["enum"]
                .as_array()
                .unwrap()
                .contains(&json!("discord"))
        );
        let schema = &request.body["response_format"]["json_schema"];
        assert_eq!(schema["strict"], true);
        assert!(!schema["schema"]["properties"]["action"]["enum"]
            .as_array()
            .unwrap()
            .contains(&json!("peek")));
        assert_eq!(requests.len(), attempts.len());
        (result, attempts, elapsed)
    }

    fn call_until(
        replies: Vec<Reply>,
        policy: Policy,
    ) -> (Result<Decision, Failure>, Vec<Attempt>, Duration) {
        call(
            replies,
            policy,
            Duration::from_secs(2),
            Duration::from_secs(2),
            Duration::from_secs(3),
        )
    }

    #[test]
    fn clamps_gateway_decisions_to_device_and_shared_daily_limits() {
        for (action, services, expected) in [
            ("allow_services", json!(["discord"]), [10, 3, 1]),
            ("full_pause", json!([]), [5, 3, 1]),
        ] {
            for (used, expected) in [0, 57, 61].into_iter().zip(expected) {
                let sample = completion(&output(action, services.clone(), json!(60)));
                let decision = call_until(vec![Reply::new(200, sample)], policy(used * MINUTE_MS))
                    .0
                    .unwrap();
                assert_eq!(decision.minutes, expected);
            }
        }
    }

    #[test]
    fn normalization_preserves_or_reduces_access() {
        let catalog = Catalog::parse(include_str!("services.json")).unwrap();
        let normalize = |value: Value| {
            serde_json::from_str::<Sample>(&value.to_string())
                .unwrap()
                .validate(&catalog, policy(0), true)
                .unwrap()
        };
        let mut scoped = output(
            "allow_services",
            json!(["discord", "discord", "x"]),
            json!(14),
        );
        scoped["message"] = json!("é".repeat(501));
        scoped["counter_offer"] = json!(" ");
        let decision = normalize(scoped);
        assert_eq!(decision.action, Action::AllowServices);
        assert_eq!(decision.services, ["discord", "x"]);
        assert_eq!(decision.minutes, 10);
        assert_eq!(decision.message.chars().count(), 500);
        assert_eq!(decision.counter_offer, None);
        for (action, expected) in [
            ("deny", Action::Deny),
            ("peek", Action::Peek),
            ("counter_offer", Action::CounterOffer),
        ] {
            let mut sample = output(action, json!(["discord", "invented"]), json!(-5));
            sample["message"] = json!(" ");
            sample["counter_offer"] = json!("  Try a different task.  ");
            let decision = normalize(sample);
            assert_eq!(decision.action, expected);
            assert_eq!(decision.minutes, 0);
            assert!(decision.services.is_empty());
            assert!(!decision.message.is_empty());
            assert_eq!(
                decision.counter_offer.as_deref(),
                (expected == Action::CounterOffer).then_some("Try a different task.")
            );
        }
        let full = normalize(output("full_pause", json!(["discord"]), json!(4)));
        assert_eq!(full.minutes, 4);
        assert!(full.services.is_empty());
    }

    #[test]
    fn unsafe_or_unusable_replies_fail_closed_with_specific_reasons() {
        use AttemptFailure::*;
        let mut missing = output("deny", json!([]), json!(0));
        missing.as_object_mut().unwrap().remove("counter_offer");
        let mut extra = output("deny", json!([]), json!(0));
        extra["domains"] = json!(["x.com"]);
        let mut blank = output("counter_offer", json!([]), json!(0));
        blank["counter_offer"] = json!("  ");
        for (sample, reason) in [
            (extra, Parse),
            (missing, Parse),
            (
                output("allow_services", json!(["invented"]), json!(4)),
                ServicesUnknown,
            ),
            (
                output(
                    "allow_services",
                    json!(["discord", "x", "youtube", "facebook"]),
                    json!(4),
                ),
                ServicesTooMany,
            ),
            (output("allow_services", json!([]), json!(4)), ServicesEmpty),
            (
                output("allow_services", json!(["discord"]), json!(-4)),
                MinutesMismatch,
            ),
            (output("full_pause", json!([]), json!(0)), MinutesMismatch),
            (output("peek", json!([]), json!(0)), PeekWithoutTarget),
            (blank, CounterOfferMismatch),
            (output("unexpected", json!([]), json!(0)), Parse),
        ] {
            let reply = completion(&sample);
            let (result, attempts, _) = call_until(
                vec![Reply::new(200, reply.clone()), Reply::new(200, reply)],
                policy(0),
            );
            assert_eq!(result.unwrap_err(), Failure::Invalid, "{sample}");
            assert_eq!(attempts.len(), 2);
            assert!(attempts
                .iter()
                .all(|attempt| attempt.failure == Some(reason)));
        }
    }

    fn success() -> Reply {
        Reply::new(
            200,
            completion(&output("allow_services", json!(["x"]), json!(3))),
        )
    }

    #[test]
    fn retries_transient_http_invalid_envelopes_and_invalid_decisions() {
        use AttemptFailure::*;
        for (reply, reason) in [
            (Reply::new(503, json!({})), Http),
            (Reply::new(429, json!({})), Http),
            (Reply::raw(200, "{"), Envelope),
            (
                Reply::new(
                    200,
                    json!({"choices":[{"finish_reason":"length", "message": {}}]}),
                ),
                FinishReason,
            ),
            (
                Reply::new(
                    200,
                    json!({"choices":[{"finish_reason":"stop", "message": {"content": 5}}]}),
                ),
                NotText,
            ),
            (
                Reply::new(
                    200,
                    completion(&output("allow_services", json!([]), json!(3))),
                ),
                ServicesEmpty,
            ),
        ] {
            let (result, attempts, _) = call_until(vec![reply, success()], policy(0));
            assert_eq!(result.unwrap().minutes, 3);
            assert_eq!(attempts[0].failure, Some(reason));
            assert_eq!(attempts[1].failure, None);
        }
    }

    #[test]
    fn stops_on_permanent_http_errors_refusals_and_retry_after_outside_budget() {
        for status in [400, 401, 403, 404] {
            let (result, attempts, _) = call_until(vec![Reply::new(status, json!({}))], policy(0));
            assert_eq!(result.unwrap_err(), Failure::Unavailable);
            assert_eq!(attempts[0].status_class, Some(4));
        }
        let refusal = json!({"choices": [{"finish_reason":"stop", "message": {"refusal": "no"}}]});
        let (result, attempts, _) = call_until(vec![Reply::new(200, refusal)], policy(0));
        assert_eq!(result.unwrap_err(), Failure::Invalid);
        assert!(attempts[0].refused());
        let reply = Reply {
            retry_after: Some("120"),
            ..Reply::new(429, json!({}))
        };
        assert_eq!(
            call_until(vec![reply], policy(0)).0.unwrap_err(),
            Failure::Unavailable
        );
        let reply = Reply {
            retry_after: Some("1"),
            ..Reply::new(429, json!({}))
        };
        assert!(call_until(vec![reply, success()], policy(0)).2 >= Duration::from_secs(1));
    }

    #[test]
    fn retries_after_timeout_and_cancels_a_hedge_loser() {
        for (timeout, hedge, reason) in [
            (250, 1000, AttemptFailure::Timeout),
            (1000, 200, AttemptFailure::Cancelled),
        ] {
            let slow = Reply {
                body_delay: Duration::from_secs(1),
                ..success()
            };
            let (result, attempts, elapsed) = call(
                vec![slow, success()],
                policy(0),
                Duration::from_millis(timeout),
                Duration::from_millis(hedge),
                Duration::from_millis(1200),
            );
            assert_eq!(result.unwrap().minutes, 3);
            assert_eq!(attempts[0].failure, Some(reason));
            assert_eq!(attempts[1].failure, None);
            assert!(elapsed < Duration::from_secs(1));
        }
    }

    #[test]
    fn retries_at_most_three_times() {
        let unavailable = || Reply::new(503, json!({}));
        let (result, attempts, _) =
            call_until(vec![unavailable(), unavailable(), unavailable()], policy(0));
        assert_eq!(result.unwrap_err(), Failure::Unavailable);
        assert_eq!(attempts.len(), 3);
    }

    #[test]
    fn all_calls_are_bounded_by_overall_deadline() {
        let slow = || Reply {
            body_delay: Duration::from_millis(900),
            ..success()
        };
        let (result, attempts, elapsed) = call(
            vec![slow(), slow()],
            policy(0),
            Duration::from_secs(2),
            Duration::from_millis(100),
            Duration::from_millis(550),
        );
        assert_eq!(result.unwrap_err(), Failure::Timeout);
        assert!(attempts
            .iter()
            .all(|attempt| attempt.failure == Some(AttemptFailure::Timeout)));
        assert!(elapsed < Duration::from_millis(850));
    }

    #[test]
    fn budgets_cover_both_phone_versions() {
        let policy: Value =
            serde_json::from_str(include_str!("../../shared/ask-retry.json")).unwrap();
        let ms = |key: &str| policy[key].as_u64().unwrap();
        let read = ms("phone_read_ms");
        let overall = ms("phone_overall_ms");
        assert!(
            RETRY.attempt_ms < RETRY.laptop_overall_ms
                && RETRY.laptop_overall_ms < read
                && read < overall
        );
        assert_eq!(ask_budget(Some(read), true), ask_budget(None, false));
        let legacy = ask_budget(None, true);
        assert!(legacy < Duration::from_millis(RETRY.legacy_phone_read_ms));
        assert!(legacy + Duration::from_millis(RETRY.legacy_phone_presend_ms)
            < Duration::from_millis(RETRY.legacy_phone_overall_ms));
        assert_eq!(
            ask_budget(Some(1000), true),
            Duration::from_millis(1000 - RETRY.response_margin_ms)
        );
        assert_eq!(ask_budget(Some(u64::MAX), true), ask_budget(None, false));
        assert_eq!(ask_budget(Some(0), true), Duration::ZERO);
    }

    #[test]
    fn reason_summaries_strip_url_components_and_keep_the_local_peek_target() {
        let text = "See https://user:password@x.com:80/name/status/5?token=private and reddit.com/r/secret or //docs.example.com/private";
        assert_eq!(
            summary_text(text),
            "See x.com and reddit.com or docs.example.com"
        );
        assert_eq!(
            summary_text("See username:password@example.com/private and user@docs.example.org"),
            "See example.com and docs.example.org"
        );
        assert_eq!(
            summary_text(
                "Open user:password@localhost:8443/private and docs.example.com./path?secret=1"
            ),
            "Open [link] and docs.example.com"
        );
        assert_eq!(
            peek_target(text).as_deref(),
            Some("https://user:password@x.com:80/name/status/5?token=private")
        );
        assert_eq!(
            summary_text(
                "Open http://[::1]:8042/private and file:///C:/private and 127.0.0.1/private"
            ),
            "Open [link] and [link] and [link]"
        );
    }

    #[test]
    fn catalog_normalizes_wildcards_and_rejects_urls_and_tlds() {
        let catalog = Catalog::parse(r#"{"example":["*.Example.net", "example.net"]}"#).unwrap();
        assert_eq!(
            catalog.domains(&["example".into()]).unwrap(),
            ["example.net"]
        );
        let defaults = Catalog::parse(include_str!("services.json")).unwrap();
        assert!(!defaults.services().contains(&"tailscale"));
        for value in ["com", "*.com", "https://x.com/", "foo..com", "127.0.0.1"] {
            assert!(Catalog::parse(&json!({"test":[value]}).to_string()).is_err());
        }
    }
}
