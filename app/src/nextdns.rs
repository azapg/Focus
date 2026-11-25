//! Temporary NextDNS allowlist changes. The caller durably stores the lease
//! before granting access and serializes writes and cleanup under the state lock.

use focus_devices::events::{elapsed, EventLog};
use reqwest::blocking::{Client, RequestBuilder};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    cell::Cell,
    collections::{BTreeMap, BTreeSet},
    time::{Duration, Instant},
};

const BASE_URL: &str = "https://api.nextdns.io";
const API_TIMEOUT: Duration = Duration::from_secs(3);
#[derive(Clone, Debug, Deserialize, Serialize)]
pub(crate) struct PauseTrace {
    id: Option<String>,
    phone: bool,
}

impl PauseTrace {
    pub fn new(phone: bool, id: Option<&str>) -> Self {
        Self {
            id: id.filter(|id| focus_devices::events::valid_id(id)).map(str::to_owned)
                .or_else(focus_devices::events::new_id),
            phone,
        }
    }

    pub fn fields(&self) -> Value {
        let mut fields = json!({"origin": if self.phone { "phone" } else { "laptop" }});
        if let Some(id) = &self.id {
            fields["correlation_id"] = json!(id);
        }
        fields
    }
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Lease {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub profile: Option<String>,
    pub until: u64,
    pub started: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub timer: Option<GrantTimer>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub services: Vec<String>,
    pub changes: BTreeMap<String, Prior>,
    #[serde(default)]
    pub in_flight: Option<String>,
    pub closing: bool,
    #[serde(default)]
    pub(crate) trace: Option<PauseTrace>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct GrantTimer {
    pub id: String,
    pub duration_ms: u64,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Prior {
    Missing,
    Inactive,
}

#[derive(Deserialize)]
struct Entry {
    id: String,
    #[serde(default = "active")]
    active: bool,
}
fn active() -> bool {
    true
}

#[derive(Deserialize)]
struct Entries {
    data: Vec<Entry>,
    meta: Option<Meta>,
}

#[derive(Deserialize)]
struct Meta {
    pagination: Option<Pagination>,
}

#[derive(Deserialize)]
struct Pagination {
    cursor: Option<String>,
}

#[derive(Debug)]
pub struct GrantError {
    transport: bool,
    pub message: String,
    pub rejected: bool,
}

pub struct NextDns {
    client: Client,
    endpoint: String,
    profile: String,
    key: String,
    events: Option<(EventLog, Option<PauseTrace>)>,
    last_cause: Cell<&'static str>,
}

impl NextDns {
    pub fn new(profile: &str, key: String) -> Result<Self, String> {
        Self::at(BASE_URL, profile, key)
    }

    fn at(base: &str, profile: &str, key: String) -> Result<Self, String> {
        let profile = focus_browser_host::normalize_nextdns_profile(profile)
            .ok_or("Enter a six-character NextDNS profile ID")?;
        let client = Client::builder()
            .timeout(API_TIMEOUT)
            .retry(reqwest::retry::never())
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .map_err(|_| "Could not start NextDNS client")?;
        Ok(Self {
            client,
            endpoint: format!("{base}/profiles/{profile}/allowlist"),
            profile,
            key,
            events: None,
            last_cause: Cell::new("ok"),
        })
    }

    pub fn observed(mut self, events: EventLog, trace: Option<&PauseTrace>) -> Self {
        self.events = Some((events, trace.cloned()));
        self
    }

    pub fn failure_cause(&self) -> &'static str {
        match self.last_cause.get() {
            "ok" => "dns_apply_failed",
            cause => cause,
        }
    }

    fn event(&self, stage: &str, started: Instant, status: Option<reqwest::StatusCode>, cause: &'static str) {
        self.last_cause.set(cause);
        if let Some((events, trace)) = &self.events {
            let mut fields = trace.as_ref().map_or_else(|| json!({}), PauseTrace::fields);
            if let Some(status) = status {
                fields["http_status"] = json!(status.as_u16());
            }
            events.record("nextdns", stage, if cause == "ok" { "ok" } else { "failed" },
                cause, elapsed(started), fields);
        }
    }

    fn request(&self, request: RequestBuilder, stage: &str, started: Instant)
        -> Result<reqwest::blocking::Response, String>
    {
        request.header("X-Api-Key", &self.key).send().map_err(|error| {
            self.event(stage, started, None,
                if error.is_timeout() && stage == "apply" { "apply_timeout" } else { "nextdns_unreachable" });
            "NextDNS request timed out or could not connect".into()
        })
    }

    fn mutate(&self, request: RequestBuilder, stage: &str) -> Result<(), GrantError> {
        let started = Instant::now();
        let response = self.request(request, stage, started)
            .map_err(|message| GrantError { message, rejected: false, transport: true })?;
        let status = response.status();
        let result = mutation_result(response);
        self.event(stage, started, Some(status), if result.is_ok() { "ok" } else { rejection(status) });
        result
    }

    pub fn current(&self) -> Result<BTreeMap<String, bool>, String> {
        let mut entries = BTreeMap::new();
        let mut seen = BTreeSet::new();
        let mut cursor = None::<String>;
        let mut url = reqwest::Url::parse(&self.endpoint)
            .map_err(|_| "Invalid NextDNS allowlist endpoint")?;
        loop {
            url.set_query(None);
            if let Some(cursor) = &cursor {
                url.query_pairs_mut().append_pair("cursor", cursor);
            }
            let started = Instant::now();
            let response = self.request(self.client.get(url.clone()), "check", started)?;
            let status = response.status();
            if !status.is_success() {
                self.event("check", started, Some(status), rejection(status));
                return Err(format!(
                    "NextDNS allowlist lookup failed ({})",
                    response.status()
                ));
            }
            let body: Entries = response.json().map_err(|_| {
                self.event("check", started, Some(status), "nextdns_rejected");
                "Invalid NextDNS allowlist response"
            })?;
            for entry in body.data {
                if entries.insert(entry.id, entry.active).is_some() {
                    self.event("check", started, Some(status), "nextdns_rejected");
                    return Err("Duplicate entry in NextDNS allowlist response".into());
                }
            }
            cursor = body
                .meta
                .and_then(|meta| meta.pagination)
                .and_then(|page| page.cursor);
            match &cursor {
                Some(cursor) if seen.insert(cursor.clone()) => {
                    self.event("check", started, Some(status), "ok");
                }
                Some(_) => {
                    self.event("check", started, Some(status), "nextdns_rejected");
                    return Err("NextDNS repeated an allowlist pagination cursor".into());
                }
                None => {
                    self.event("check", started, Some(status), "ok");
                    return Ok(entries);
                }
            }
        }
    }

    pub fn is_active(&self, domain: &str) -> Result<bool, String> {
        Ok(self.current()?.get(domain) == Some(&true))
    }

    pub fn verify_key(&self) -> Result<(), String> {
        self.current().map(|_| ())
    }

    pub fn plan(&self, domains: &[String], until: u64) -> Result<Lease, String> {
        let active = self.current()?;
        let changes = domains
            .iter()
            .filter_map(|domain| match active.get(domain) {
                Some(true) => None,
                Some(false) => Some((domain.clone(), Prior::Inactive)),
                None => Some((domain.clone(), Prior::Missing)),
            })
            .collect();
        Ok(Lease {
            profile: Some(self.profile.clone()),
            until,
            started: None,
            timer: None,
            services: Vec::new(),
            changes,
            in_flight: None,
            closing: false,
            trace: None,
        })
    }

    pub fn grant(&self, domain: &str, prior: Prior) -> Result<(), GrantError> {
        let request = match prior {
            Prior::Missing => self
                .client
                .post(&self.endpoint)
                .json(&json!({ "id": domain, "active": true })),
            Prior::Inactive => {
                let url = self.entry_url(domain).map_err(|message| GrantError {
                    message,
                    rejected: true,
                    transport: false,
                })?;
                self.client.patch(url).json(&json!({ "active": true }))
            }
        };
        self.mutate(request, "apply")
    }

    fn entry_url(&self, domain: &str) -> Result<String, String> {
        if !crate::judge::valid_domain(domain) {
            return Err("Invalid domain in stored NextDNS lease".into());
        }
        Ok(format!("{}/{domain}", self.endpoint))
    }

    pub fn restore_entry(&self, domain: &str, prior: Prior) -> Result<(), String> {
        let url = self.entry_url(domain)?;
        let request = match prior {
            Prior::Missing => self.client.delete(url),
            Prior::Inactive => self.client.patch(url).json(&json!({ "active": false })),
        };
        match self.mutate(request, "cleanup") {
            Ok(()) => Ok(()),
            Err(error) if error.transport => Err(error.message),
            Err(error) => {
                let current = self.current()?;
                let restored = match prior {
                    Prior::Missing => !current.contains_key(domain),
                    Prior::Inactive => current.get(domain) != Some(&true),
                };
                if restored {
                    Ok(())
                } else {
                    Err(error.message)
                }
            }
        }
    }
}

fn rejection(status: reqwest::StatusCode) -> &'static str {
    if matches!(status.as_u16(), 401 | 403) { "nextdns_auth" } else { "nextdns_rejected" }
}

fn mutation_result(response: reqwest::blocking::Response) -> Result<(), GrantError> {
    let status = response.status();
    if status == reqwest::StatusCode::NO_CONTENT {
        return Ok(());
    }
    let body = response.json::<Value>().ok();
    if status.is_success()
        && body
            .as_ref()
            .is_some_and(|body| body.get("data").is_some() && body.get("errors").is_none())
    {
        return Ok(());
    }
    let rejected = body
        .as_ref()
        .and_then(|body| body["errors"].as_array())
        .is_some_and(|errors| !errors.is_empty())
        && (status.is_success() || status == reqwest::StatusCode::BAD_REQUEST)
        || matches!(
            status,
            reqwest::StatusCode::UNAUTHORIZED | reqwest::StatusCode::FORBIDDEN
        );
    Err(GrantError {
        transport: false,
        message: format!("NextDNS allowlist mutation failed ({status})"),
        rejected,
    })
}

impl Lease {
    pub fn needs_cleanup(&self, now: u64, early: bool) -> bool {
        early || self.closing || self.started.is_none() || now >= self.until
    }
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct PendingGrant {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub profile: Option<String>,
    pub prior: Prior,
    pub observed: bool,
    #[serde(default)]
    pub restored: bool,
    #[serde(default)]
    pub(crate) trace: Option<PauseTrace>,
}

pub enum JournalUpdate {
    Lease(Option<Lease>),
    Park { domain: String, prior: Prior },
}

/// Run cleanup for an expired, cancelled, or interrupted lease. Durably mark
/// it as closing before changing the profile. Keep it until both the remote
/// restore and local cache flush succeed.
pub fn sweep(
    lease: &Lease,
    now: u64,
    early: bool,
    mut persist: impl FnMut(JournalUpdate) -> Result<(), String>,
    is_active: impl FnOnce(&str) -> Result<bool, String>,
    mut restore: impl FnMut(&str, Prior) -> Result<(), String>,
    flush: impl FnOnce() -> Result<(), String>,
) -> Result<bool, String> {
    if !lease.needs_cleanup(now, early) {
        return Ok(false);
    }
    let mut closing = lease.clone();
    if !closing.closing {
        closing.closing = true;
        persist(JournalUpdate::Lease(Some(closing.clone())))?;
    }
    let mut pending = None;
    if let Some(domain) = closing.in_flight.clone() {
        match is_active(&domain) {
            Ok(true) => {
                // Persist that the outstanding write landed before undoing it.
                closing.in_flight = None;
                persist(JournalUpdate::Lease(Some(closing.clone())))?;
            }
            Ok(false) | Err(_) => {
                pending = closing.changes.get(&domain).map(|prior| (domain, *prior));
            }
        }
    }
    let mut first_error = None;
    for (domain, prior) in closing.changes.clone() {
        if closing.in_flight.as_ref() == Some(&domain) {
            continue;
        }
        match restore(&domain, prior) {
            Ok(()) => {
                closing.changes.remove(&domain);
                persist(JournalUpdate::Lease(Some(closing.clone())))?;
            }
            Err(error) => {
                first_error.get_or_insert(error);
            }
        }
    }
    let flushed = flush();
    if let Some(error) = first_error {
        return Err(error);
    }
    flushed?;
    if let Some((domain, prior)) = pending {
        // Absence in a read is not an acknowledgment for an outstanding write.
        // Leave the intent on disk and retry until the mutation is observed.
        persist(JournalUpdate::Park { domain, prior })?;
    } else {
        persist(JournalUpdate::Lease(None))?;
    }
    Ok(true)
}

// A pending grant may never arrive. Keep checking it without blocking other
// domains. Once observed, durably mark it so failed restores can be retried.
pub fn reconcile_pending(
    domain: &str,
    mut pending: PendingGrant,
    is_active: impl FnOnce(&str) -> Result<bool, String>,
    mut persist: impl FnMut(Option<PendingGrant>) -> Result<(), String>,
    restore: impl FnOnce(&str, Prior) -> Result<(), String>,
    flush: impl FnOnce() -> Result<(), String>,
) -> Result<bool, String> {
    if !pending.observed {
        if !is_active(domain)? {
            return Ok(false);
        }
        pending.observed = true;
        persist(Some(pending.clone()))?;
    }
    if !pending.restored {
        restore(domain, pending.prior)?;
        pending.restored = true;
        persist(Some(pending.clone()))?;
    }
    flush()?;
    persist(None)?;
    Ok(true)
}

#[cfg(test)]
mod tests {
    const TEST_PROFILE: &str = "a1b2c3";
    use super::*;
    use crate::mock_http::{MockHttp, Reply};
    use std::cell::RefCell;

    #[test]
    fn nextdns_grants_then_restores_only_the_owned_changes_after_expiry() {
        let mock = MockHttp::start(vec![
            Reply::new(
                200,
                json!({"data":[{"id":"permanent.example","active":true},{"id":"inactive.example","active":false}]}),
            ),
            Reply::new(200, json!({"data":null})),
            Reply::new(200, json!({"data":null})),
            Reply::new(200, json!({"data":null})),
            Reply::new(200, json!({"data":null})),
        ]);
        let api = NextDns::at(&mock.url, TEST_PROFILE, "dummy-nextdns-key".into()).unwrap();
        let domains = [
            "new.example".into(),
            "inactive.example".into(),
            "permanent.example".into(),
        ];
        let mut lease = api.plan(&domains, 120_000).unwrap();
        for (domain, prior) in &lease.changes {
            api.grant(domain, *prior).unwrap();
        }
        lease.started = Some(60_000);
        assert!(!sweep(
            &lease,
            119_999,
            false,
            |_| unreachable!(),
            |_| unreachable!(),
            |_, _| unreachable!(),
            || unreachable!()
        )
        .unwrap());

        // Simulates state.json reload after the laptop restarts following expiry.
        let reloaded: Lease = serde_json::from_value(json!(lease)).unwrap();
        let state = RefCell::new(Some(reloaded.clone()));
        let persist = |updated| {
            let JournalUpdate::Lease(lease) = updated else {
                unreachable!()
            };
            *state.borrow_mut() = lease;
            Ok(())
        };
        assert!(sweep(
            &reloaded,
            150_000,
            false,
            persist,
            |_| unreachable!(),
            |domain, prior| {
                assert!(state.borrow().as_ref().unwrap().closing);
                api.restore_entry(domain, prior)
            },
            || Ok(())
        )
        .unwrap());
        assert!(state.into_inner().is_none());

        let requests = mock.requests();
        let path = format!("/profiles/{TEST_PROFILE}/allowlist");
        let expected = [
            (format!("GET {path} HTTP/1.1"), Value::Null),
            (
                format!("PATCH {path}/inactive.example HTTP/1.1"),
                json!({"active":true}),
            ),
            (
                format!("POST {path} HTTP/1.1"),
                json!({"id":"new.example","active":true}),
            ),
            (
                format!("PATCH {path}/inactive.example HTTP/1.1"),
                json!({"active":false}),
            ),
            (format!("DELETE {path}/new.example HTTP/1.1"), Value::Null),
        ];
        for (request, (line, body)) in requests.iter().zip(expected) {
            assert_eq!(request.line, line);
            assert_eq!(request.body, body);
            assert!(request.headers.contains("x-api-key: dummy-nextdns-key"));
        }
    }

    #[test]
    fn retry_restores_only_entries_left_in_the_journal() {
        let lease = Lease {
            profile: Some(TEST_PROFILE.into()),
            until: 1000,
            started: Some(1),
            timer: None,
            services: Vec::new(),
            changes: BTreeMap::from([
                ("first.example".into(), Prior::Missing),
                ("second.example".into(), Prior::Inactive),
            ]),
            closing: false,
            trace: None,
            in_flight: None,
        };
        let state = RefCell::new(Some(lease.clone()));
        let persist = |update| {
            let JournalUpdate::Lease(next) = update else {
                unreachable!()
            };
            *state.borrow_mut() = next;
            Ok(())
        };
        assert!(sweep(
            &lease,
            1000,
            false,
            persist,
            |_| unreachable!(),
            |domain, _| {
                if domain == "second.example" {
                    Err("unavailable".into())
                } else {
                    Ok(())
                }
            },
            || Ok(())
        )
        .is_err());
        let remaining = state.borrow().clone().unwrap();
        assert_eq!(
            remaining.changes,
            BTreeMap::from([("second.example".into(), Prior::Inactive)])
        );
        assert!(sweep(
            &remaining,
            1100,
            false,
            persist,
            |_| unreachable!(),
            |domain, _| {
                assert_eq!(domain, "second.example");
                Ok(())
            },
            || Ok(())
        )
        .unwrap());
        assert!(state.into_inner().is_none());
    }

    #[test]
    fn interrupted_grant_is_parked_until_a_late_write_is_observed_and_undone() {
        let mock = MockHttp::start(vec![
            Reply::new(200, json!({"data":[]})),
            Reply::new(200, json!({"data":[]})),
            Reply::new(200, json!({"data":[{"id":"test.example","active":true}]})),
            Reply::new(200, json!({"errors":[{"code":"temporary"}]})),
            Reply::new(200, json!({"data":[{"id":"test.example","active":true}]})),
            Reply::new(200, json!({"data":null})),
        ]);
        let api = NextDns::at(&mock.url, TEST_PROFILE, "test-key".into()).unwrap();
        let lease = Lease {
            profile: Some(TEST_PROFILE.into()),
            until: 600_000,
            started: None,
            timer: None,
            services: Vec::new(),
            in_flight: Some("test.example".into()),
            closing: false,
            trace: None,
            changes: BTreeMap::from([("test.example".into(), Prior::Missing)]),
        };
        let state = RefCell::new(Some(lease.clone()));
        let pending = RefCell::new(None);
        assert!(sweep(
            &lease,
            1000,
            false,
            |update| {
                match update {
                    JournalUpdate::Lease(next) => *state.borrow_mut() = next,
                    JournalUpdate::Park { domain, prior } => {
                        assert_eq!(domain, "test.example");
                        *state.borrow_mut() = None;
                        *pending.borrow_mut() = Some(PendingGrant {
                            profile: Some(TEST_PROFILE.into()),
                            prior,
                            observed: false,
                            restored: false,
                            trace: None,
                        });
                    }
                }
                Ok(())
            },
            |domain| api.is_active(domain),
            |domain, prior| api.restore_entry(domain, prior),
            || Ok(()),
        )
        .unwrap());
        assert!(state.into_inner().is_none());
        for (flush_ok, expected) in [
            (true, Ok(false)),
            (true, Err(())),
            (false, Err(())),
            (true, Ok(true)),
        ] {
            let reloaded: PendingGrant =
                serde_json::from_value(json!(pending.borrow().as_ref().unwrap())).unwrap();
            let cleaned = reconcile_pending(
                "test.example",
                reloaded,
                |domain| api.is_active(domain),
                |updated| {
                    *pending.borrow_mut() = updated;
                    Ok(())
                },
                |domain, prior| api.restore_entry(domain, prior),
                || {
                    if flush_ok {
                        Ok(())
                    } else {
                        Err("flush failed".into())
                    }
                },
            )
            .map_err(|_| ());
            assert_eq!(cleaned, expected);
        }
        assert!(pending.into_inner().is_none());
        assert_eq!(mock.requests().len(), 6);
    }

    #[test]
    fn api_error_envelope_is_a_rejected_grant() {
        let mock = MockHttp::start(vec![Reply::new(
            200,
            json!({"errors":[{"code":"invalid"}]}),
        )]);
        let api = NextDns::at(&mock.url, TEST_PROFILE, "test-key".into()).unwrap();
        assert!(
            api.grant("test.example", Prior::Missing)
                .unwrap_err()
                .rejected
        );
        assert_eq!(mock.requests().len(), 1);
    }

    #[test]
    fn planning_preserves_active_entries_on_later_pages() {
        let mock = MockHttp::start(vec![
            Reply::new(
                200,
                json!({"data":[], "meta":{"pagination":{"cursor":"next-page"}}}),
            ),
            Reply::new(200, json!({"data":[{"id":"test.example","active":true}]})),
        ]);
        let api = NextDns::at(&mock.url, TEST_PROFILE, "test-key".into()).unwrap();
        assert!(api
            .plan(&["test.example".into()], 60_000)
            .unwrap()
            .changes
            .is_empty());
        assert!(mock.requests()[1].line.contains("?cursor=next-page"));
    }
}
