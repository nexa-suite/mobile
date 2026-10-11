#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || -z "$1" ]]; then
  printf '%s\n' 'Usage: scripts/reverse-local-api.sh <device-serial>' >&2
  exit 2
fi

device_serial=$1
if ! command -v adb >/dev/null 2>&1; then
  printf '%s\n' 'adb is required to configure a physical Android device.' >&2
  exit 127
fi

device_state=$(adb -s "$device_serial" get-state 2>/dev/null || true)
if [[ "$device_state" != 'device' ]]; then
  printf '%s\n' 'The requested device serial is not connected and ready.' >&2
  exit 1
fi

adb -s "$device_serial" reverse tcp:8080 tcp:8080
printf 'Local API reverse configured for device %s on port 8080.\n' "$device_serial"
