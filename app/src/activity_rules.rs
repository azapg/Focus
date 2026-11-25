//! Local activity categorization rules (`rules.json`) and unmatched inbox (`uncategorized.json`).
//! Both live beside activity logs in %LOCALAPPDATA%\FocusDNS so users and agents can edit
//! `rules.json` directly without rebuilding the app.

use serde_json::{json, Value};
use std::{fs, path::Path};

const MAX_BYTES: usize = 256 * 1024;

pub fn default_rules() -> Value {
    serde_json::from_str(include_str!("../finite-breaks.json")).expect("bundled rules must be valid JSON")
}

pub fn load_rules(dir: &Path) -> Result<Value, String> {
    let path = dir.join("rules.json");
    if !path.exists() {
        return Ok(default_rules());
    }
    let text = fs::read_to_string(&path).map_err(|e| format!("could not read rules.json: {e}"))?;
    let mut val: Value =
        serde_json::from_str(&text).map_err(|e| format!("invalid JSON in rules.json: {e}"))?;
    if !val.is_object() {
        return Err("rules.json must be a JSON object".into());
    }
    if val.get("finiteAlternatives").is_none() {
        val["finiteAlternatives"] = default_rules()["finiteAlternatives"].clone();
    }
    Ok(val)
}

pub fn save_rules(dir: &Path, rules: &Value) -> Result<(), String> {
    if !rules.is_object() || !rules.get("rules").is_some_and(Value::is_array) {
        return Err("rules payload must be an object with a rules array".into());
    }
    let bytes = serde_json::to_vec_pretty(rules)
        .map_err(|e| format!("could not serialize rules.json: {e}"))?;
    if bytes.len() > MAX_BYTES {
        return Err("rules.json exceeds 256 KiB limit".into());
    }
    focus_browser_host::atomic_write(&dir.join("rules.json"), &bytes)
        .map_err(|e| format!("could not write rules.json: {e}"))
}

pub fn save_uncategorized(dir: &Path, inbox: &Value) -> Result<(), String> {
    if !inbox.is_object() {
        return Err("uncategorized payload must be a JSON object".into());
    }
    let bytes = serde_json::to_vec_pretty(inbox)
        .map_err(|e| format!("could not serialize uncategorized.json: {e}"))?;
    if bytes.len() > MAX_BYTES {
        return Err("uncategorized.json exceeds 256 KiB limit".into());
    }
    focus_browser_host::atomic_write(&dir.join("uncategorized.json"), &bytes)
        .map_err(|e| format!("could not write uncategorized.json: {e}"))
}

/// Optional JSON object written by an agent or Jev job (`categories.json`, `icons.json`).
/// Missing files are normal and return an empty object; oversized or invalid files are errors.
pub fn load_optional_object(dir: &Path, name: &str, max_bytes: u64) -> Result<Value, String> {
    let path = dir.join(name);
    let Ok(meta) = fs::metadata(&path) else {
        return Ok(json!({}));
    };
    if meta.len() > max_bytes {
        return Err(format!("{name} exceeds {} KiB limit", max_bytes / 1024));
    }
    let text = fs::read_to_string(&path).map_err(|e| format!("could not read {name}: {e}"))?;
    let val: Value = serde_json::from_str(&text).map_err(|e| format!("invalid JSON in {name}: {e}"))?;
    if !val.is_object() {
        return Err(format!("{name} must be a JSON object"));
    }
    Ok(val)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_dir(name: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!("focus-rules-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn missing_rules_returns_bundled_defaults() {
        let dir = temp_dir("default");
        let val = load_rules(&dir).unwrap();
        assert_eq!(val["version"], 1);
        assert_eq!(val, default_rules());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn saves_and_reloads_rules_and_uncategorized_atomically() {
        let dir = temp_dir("roundtrip");
        let payload = json!({
            "version": 1,
            "rules": [
                { "match": "host", "value": "drafting.example", "app": "Drafting", "category": "building" }
            ]
        });
        save_rules(&dir, &payload).unwrap();
        let loaded = load_rules(&dir).unwrap();
        assert_eq!(loaded["rules"], payload["rules"]);
        assert_eq!(loaded["finiteAlternatives"], default_rules()["finiteAlternatives"]);

        let inbox = json!({
            "version": 1,
            "updated_at": 1_800_000_000_000u64,
            "items": [{ "match": "host", "value": "example.dev", "app": "Example", "ms": 120000 }]
        });
        save_uncategorized(&dir, &inbox).unwrap();
        let raw = fs::read_to_string(dir.join("uncategorized.json")).unwrap();
        assert!(raw.contains("example.dev"));
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn explicit_finite_alternatives_replace_defaults() {
        let dir = temp_dir("alternatives");
        for choices in [
            json!([]),
            json!([{"id": "book", "app": "Book site", "goal": "5 pages", "laptopUrl": "https://example.org"}]),
        ] {
            let payload = json!({ "version": 1, "rules": [], "finiteAlternatives": choices });
            save_rules(&dir, &payload).unwrap();
            assert_eq!(load_rules(&dir).unwrap(), payload);
        }
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn invalid_rules_file_returns_error_without_overwriting() {
        let dir = temp_dir("corrupt");
        fs::write(dir.join("rules.json"), "{ not json").unwrap();
        assert!(load_rules(&dir).is_err());
        assert_eq!(fs::read_to_string(dir.join("rules.json")).unwrap(), "{ not json");
        let _ = fs::remove_dir_all(&dir);
    }
}
