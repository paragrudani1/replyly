#!/usr/bin/env bash
# Re-records the screenshot PNGs in app/src/test/screenshots (docs/SCREENSHOTS.md).
#   scripts/record-screenshots.sh                                  # every screenshot test
#   scripts/record-screenshots.sh --tests '*PanelScreenshotTest'   # one class
# Then look at the changed PNGs (git status / git diff --stat) and commit them with the code.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -f local.properties && -z "${ANDROID_HOME:-}" && -d /root/local-dev/android-sdk ]]; then
  export ANDROID_HOME=/root/local-dev/android-sdk
fi

args=("$@")
if [[ ${#args[@]} -eq 0 ]]; then
  args=(--tests '*ScreenshotTest')
fi
./gradlew recordRoborazziDebug "${args[@]}"
git status --short -- app/src/test/screenshots
