use focus_telemetry as telemetry;

/// Count the PowerShell probe's numeric markers without retaining them in the DNS log.
pub fn dns_probe_output(output: &str) -> String {
    let mut diagnostic = Vec::new();
    for line in output.trim().lines() {
        if line == "FOCUS_DNS_PROBE" {
            telemetry::DNS_REQUEST.count(1);
        } else if let Some(bytes) = line.strip_prefix("FOCUS_DNS_RX_BYTES ") {
            if let Ok(bytes) = bytes.parse() {
                telemetry::DNS_RX.count(bytes);
            }
        } else {
            diagnostic.push(line);
        }
    }
    diagnostic.join(" | ")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn probe_markers_do_not_reach_the_diagnostic_log() {
        let output = "FOCUS_DNS_PROBE\r\nFOCUS_DNS_RX_BYTES 123\r\nTEST status=ok\r\n";
        assert_eq!(dns_probe_output(output), "TEST status=ok");
        let output = "FOCUS_DNS_PROBE\nFOCUS_DNS_RX_BYTES invalid\nTEST error: unavailable";
        assert_eq!(dns_probe_output(output), "TEST error: unavailable");
    }
}
