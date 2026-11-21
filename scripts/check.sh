#!/usr/bin/env bash
# Runs exactly what CI runs. Fix formatting first with: ./gradlew spotlessApply
# (never in the same Gradle call as compile: run it on its own, then this script).
#
# KSP sometimes fails to initialise (kspDebugKotlin / PluginEnabler with
# ExceptionInInitializerError or "Could not initialize class ksp."). When the log shows that,
# the gate retries once in a fresh JVM (--no-daemon --stacktrace) with the same tasks. Any other
# failure fails at once.
set -euo pipefail
cd "$(dirname "$0")/.."
# Fresh worktrees on the VM have no local.properties; point Gradle at the shared SDK.
if [[ ! -f local.properties && -z "${ANDROID_HOME:-}" && -d /root/local-dev/android-sdk ]]; then
  export ANDROID_HOME=/root/local-dev/android-sdk
fi

tasks=(spotlessCheck lintDebug testDebugUnitTest assembleDebug "$@")
log="$(mktemp -t replyly-check.XXXXXX.log)"
trap 'rm -f "$log"' EXIT

is_ksp_init_flake() {
  grep -Eq 'kspDebugKotlin|PluginEnabler' "$1" &&
    grep -Eq 'ExceptionInInitializerError|Could not initialize class ksp\.' "$1"
}

status=0
./gradlew "${tasks[@]}" 2>&1 | tee "$log" || status=$?
if [[ $status -ne 0 ]] && is_ksp_init_flake "$log"; then
  echo "check.sh: KSP init flake detected; retrying once with --no-daemon --stacktrace" >&2
  status=0
  ./gradlew --no-daemon --stacktrace "${tasks[@]}" || status=$?
fi
exit "$status"
