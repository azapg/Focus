#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
unit_build="${FOCUS_ANDROID_TEST_BUILD:-build/unit}"
mkdir -p "$unit_build"
python3 generate-ask-policy.py "$unit_build/generated"
javac -d "$unit_build" \
  src/org/nightingalelab/focus/phone/BrowserUrlParser.java \
  src/org/nightingalelab/focus/phone/ActivityClassifier.java \
  src/org/nightingalelab/focus/phone/PausePolicy.java \
  src/org/nightingalelab/focus/phone/InsightsEngine.java \
  src/org/nightingalelab/focus/phone/FocusStateDetector.java \
  src/org/nightingalelab/focus/phone/HabitNudgePolicy.java \
  src/org/nightingalelab/focus/phone/FocusStats.java \
  src/org/nightingalelab/focus/phone/UsageTimeline.java \
  src/org/nightingalelab/focus/phone/PairingCode.java \
  src/org/nightingalelab/focus/phone/ApprovalPairing.java \
  src/org/nightingalelab/focus/phone/UpdateManifest.java \
  src/org/nightingalelab/focus/phone/GatewayRetry.java \
  "$unit_build/generated/org/nightingalelab/focus/phone/AskPolicy.java" \
  ../shared/android/org/nightingalelab/focus/habits/Habits.java \
  ../shared/android/org/nightingalelab/focus/habits/Wallet.java \
  ../shared/android/org/nightingalelab/focus/habits/FocusRewards.java \
  tests/PhoneCoreTest.java tests/FocusStateDetectorTest.java tests/HabitsCoreTest.java tests/WalletCoreTest.java tests/FocusRewardsTest.java tests/HabitNudgePolicyTest.java tests/GatewayRetryTest.java
java -cp "$unit_build" PhoneCoreTest
java -cp "$unit_build" FocusStateDetectorTest

java -cp "$unit_build" org.nightingalelab.focus.habits.HabitsCoreTest
java -cp "$unit_build" org.nightingalelab.focus.habits.WalletCoreTest
java -cp "$unit_build" org.nightingalelab.focus.habits.FocusRewardsTest
java -cp "$unit_build" org.nightingalelab.focus.phone.HabitNudgePolicyTest
java -cp "$unit_build" org.nightingalelab.focus.phone.GatewayRetryTest
