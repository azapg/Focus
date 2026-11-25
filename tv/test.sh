#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build/unit
javac -d build/unit src/org/nightingalelab/focus/tv/Rules.java src/org/nightingalelab/focus/tv/UsageTimeline.java tests/CoreTest.java src/org/nightingalelab/focus/tv/PairingCode.java tests/PairingTest.java src/org/nightingalelab/focus/tv/ApprovalPairing.java tests/ApprovalTest.java
java -cp build/unit CoreTest
java -cp build/unit PairingTest
java -cp build/unit ApprovalTest
