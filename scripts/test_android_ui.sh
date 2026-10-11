#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../android"
capture_previews() {
  mkdir -p ../planner-previews
  # The emulator runner stops the device after this script, so pull now.
  adb pull /sdcard/Pictures/ChatPlannerTest/. ../planner-previews/ || true
}
trap capture_previews EXIT
./gradlew --no-daemon --max-workers=2 connectedDebugAndroidTest
