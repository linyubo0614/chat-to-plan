#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../android"
capture_previews() {
  mkdir -p ../planner-previews
  # Instrumentation cleanup removes the APK and its private files; MediaStore previews survive it.
  adb pull /sdcard/Pictures/ChatPlannerPreviews/ ../planner-previews/ || true
}
trap capture_previews EXIT
./gradlew --no-daemon --max-workers=2 connectedDebugAndroidTest
