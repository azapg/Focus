//! Replay recorded laptop activity without starting Tauri or changing protection.

use chrono::{DateTime, Local};
use focus_dns::{
    activity::Span,
    activity_rules,
    focus_state::{classifier::Classifier, history, Config, Detector, Signals, TICK_MS},
};
use std::{error::Error, fs, path::Path};

// Current foreground spans checkpoint every minute. Leave room for older
// logs with longer visits while ruling out corrupt spans that hold replay open.
const MAX_SPAN_MS: u64 = 24 * 60 * 60 * 1000;

fn replay(dir: &Path) -> Result<(), Box<dyn Error>> {
    let config = Config::load(dir)?;
    let mut classifier = Classifier::new(&activity_rules::load_rules(dir)?)?;
    let mut spans = Vec::new();
    let mut discarded = 0;
    let max_timestamp = DateTime::<chrono::Utc>::MAX_UTC.timestamp_millis() as u64 - MAX_SPAN_MS;
    for entry in fs::read_dir(dir.join("activity"))? {
        let entry = entry?;
        let path = entry.path();
        if path.extension().and_then(|ext| ext.to_str()) != Some("jsonl") {
            continue;
        }
        for line in fs::read_to_string(path)?
            .lines()
            .filter(|line| !line.trim().is_empty())
        {
            match serde_json::from_str::<Span>(line) {
                Ok(span)
                    if span.e > span.s
                        && span.e <= max_timestamp
                        && span.e - span.s <= MAX_SPAN_MS =>
                {
                    spans.push(classifier.classify(&span)?)
                }
                _ => discarded += 1,
            }
        }
    }
    spans.sort_by_key(|span| (span.start, span.end));
    let Some(first) = spans.first() else {
        return Err("no usable laptop activity spans".into());
    };
    let end = spans.iter().map(|span| span.end).max().unwrap();

    let mut panels =
        history::panel_timestamps(&history::read_optional(&dir.join(history::PANEL_LOG))?);
    let oldest_structured = panels.iter().min().copied().unwrap_or(u64::MAX);
    let old_log = history::read_optional(&dir.join("focusdns.log"))?;
    panels.extend(history::legacy_panel_timestamps(&old_log).filter(|&t| t < oldest_structured));
    panels.sort_unstable();
    let pickups = match history::read_optional(&dir.join("devices.json")) {
        Ok(text) if text.is_empty() => None,
        Ok(text) => match serde_json::from_str(&text) {
            Ok(registry) => history::inferred_pickups(&registry),
            Err(_) => {
                eprintln!("focus-replay: ignoring invalid optional devices.json");
                None
            }
        },
        Err(error) => {
            eprintln!("focus-replay: could not read optional devices.json: {error}");
            None
        }
    };
    let pauses =
        history::pause_restarts(&history::read_optional(&dir.join("pauses.jsonl"))?, &config);
    println!(
        "{} activity spans, {} skipped lines, {} panel opens, {} inferred phone pickups, {} quick pause restarts",
        spans.len(), discarded, panels.len(), pickups.as_ref().map_or("unavailable".into(), |p| p.len().to_string()),
        pauses.len()
    );

    let mut detector = Detector::new(config.clone());
    let mut now = first.start.div_ceil(TICK_MS) * TICK_MS;
    let mut left = 0;
    let mut right = 0;
    let mut transitions = 0;
    while now <= end + 4 * TICK_MS {
        while spans.get(right).is_some_and(|span| span.start <= now) {
            right += 1;
        }
        while left < right && spans[left].end <= now.saturating_sub(config.lookback_ms()) {
            left += 1;
        }
        let signals = Signals::measure(
            &config,
            now,
            &spans[left..right],
            &panels,
            pickups.as_deref(),
            &pauses,
        );
        if let Some(event) = detector.step(now, &signals) {
            if let (Some(from), Some(to)) = (event.from, event.to) {
                let timestamp = DateTime::from_timestamp_millis(event.at as i64)
                    .ok_or("timestamp out of range")?;
                println!(
                    "{}  {from:?} → {to:?}  {}",
                    timestamp.with_timezone(&Local).to_rfc3339(),
                    event.reason
                );
                transitions += 1;
            }
        }
        // Skip gaps after reporting the session end, even across missing days.
        now += TICK_MS;
        if !signals.active {
            if let Some(next) = spans.get(right) {
                now = now.max(next.start.div_ceil(TICK_MS) * TICK_MS);
            }
        }
    }
    println!("{transitions} focus transitions");
    Ok(())
}

fn main() -> std::process::ExitCode {
    let args: Vec<_> = std::env::args_os().skip(1).collect();
    match args.as_slice() {
        [flag] if flag == "--defaults" => println!(
            "{}",
            serde_json::to_string_pretty(&Config::default()).unwrap()
        ),
        [dir] => {
            if let Err(error) = replay(Path::new(dir)) {
                eprintln!("focus-replay: {error}");
                return std::process::ExitCode::FAILURE;
            }
        }
        _ => {
            eprintln!("Usage: focus-replay <FocusDNS-data-directory> | --defaults");
            return std::process::ExitCode::FAILURE;
        }
    }
    std::process::ExitCode::SUCCESS
}
