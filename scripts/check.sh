#!/usr/bin/env bash
# Runs exactly what CI runs. Fix formatting first with: ./gradlew spotlessApply
set -euo pipefail
cd "$(dirname "$0")/.."
# Fresh worktrees on the VM have no local.properties; point Gradle at the shared SDK.
if [[ ! -f local.properties && -z "${ANDROID_HOME:-}" && -d /root/local-dev/android-sdk ]]; then
  export ANDROID_HOME=/root/local-dev/android-sdk
fi
exec ./gradlew spotlessCheck lintDebug testDebugUnitTest assembleDebug "$@"
