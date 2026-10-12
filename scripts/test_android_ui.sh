#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../android"
capture_previews() {
  mkdir -p ../planner-previews
  # Capture while the emulator is still running. run-as works for preview APKs.
  adb exec-out run-as cn.chatplanner.control tar -C /sdcard/Android/data/cn.chatplanner.control/files -cf - previews 2>/dev/null | tar -xf - -C ../planner-previews/ || true
}
trap capture_previews EXIT
./gradlew --no-daemon --max-workers=2 connectedDebugAndroidTest
