#!/usr/bin/env bash
set -euo pipefail

# AGP 9.4.1 serial filtering mutates an immutable device collection. Select a
# device by requiring an isolated ADB inventory, rather than changing AGP or
# allowing tests to install on another running emulator/device.
if [[ $# -lt 1 ]]; then
  echo 'Usage: scripts/verify-connected-local.sh <only-connected-device> [Gradle options]' >&2
  exit 2
fi
expected_serial=$1
shift
for argument in "$@"; do
  if [[ "$argument" == --serial* ]]; then
    echo 'AGP serial filtering is unsupported by this local runner.' >&2
    exit 2
  fi
done
connected_serials=$(adb devices | awk 'NR > 1 && NF { print $1 ":" $2 }')
if [[ "$connected_serials" != "$expected_serial:device" ]]; then
  echo 'Exactly the requested device must be connected and ready; other devices are preserved.' >&2
  exit 2
fi
unset ANDROID_SERIAL
project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$project_dir"
exec ./gradlew connectedDebugAndroidTest --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8' \
  --dependency-verification strict "$@"
