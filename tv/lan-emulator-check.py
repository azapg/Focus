#!/usr/bin/env python3
"""Run the real Android TLS/API checks only on an explicitly selected disposable emulator."""
import argparse, json, pathlib, subprocess, sys, time
ROOT = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
from install import TV

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        raise SystemExit('This check only runs on a disposable emulator, never a physical TV.')
    tv = TV(args.serial)
    if 'sdk' not in tv.adb('shell', 'getprop', 'ro.product.model'):
        raise SystemExit('Expected Android SDK emulator')
    # Keep one explicit target throughout; never restart the shared ADB server.
    for apk in ['focus-tv.apk', 'test-fixture.apk', 'test-device.apk']:
        tv.install(ROOT/'build'/apk, update=True)
    tv.adb('shell', 'appops', 'set', 'org.nightingalelab.focus.tv', 'GET_USAGE_STATS', 'allow')
    tv.adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', 'org.nightingalelab.focus.tv/.RestrictionService')
    tv.adb('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1')
    tv.adb('shell', 'am', 'start', '-W', '-n', 'org.nightingalelab.focus.tv/.MainActivity')
    # Instrumentation restarts the target; detach our emulator-only service first
    # so Android does not retain it as a crashed accessibility connection.
    tv.adb('shell', 'settings', 'delete', 'secure', 'enabled_accessibility_services')
    tv.adb('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '0')
    time.sleep(2)
    raw = tv.adb('shell', 'am', 'instrument', '-w', 'org.nightingalelab.focus.tv.tests/.LanChecks', timeout=90)
    line = next((line.split('result=',1)[1] for line in raw.splitlines() if 'INSTRUMENTATION_RESULT: result=' in line), None)
    if line is None:
        raise SystemExit('No instrumentation result: '+raw)
    result = json.loads(line)
    report = {'platform':'Android API 36.1 phone emulator (not TV hardware)', 'dns_settings_changed':False, 'checks':result, 'passed':'error' not in result and result.get('cleanup_complete') is True and 'INSTRUMENTATION_CODE: -1' in raw}
    output = ROOT/'evidence'/'private'/'lan-emulator-check.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps(report, indent=2))
    if not report['passed']:
        raise SystemExit(1)

if __name__ == '__main__':
    main()
