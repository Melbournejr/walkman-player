#!/usr/bin/env bash
set -euo pipefail

# Run before and after routing playback to a USB DAC. Uses only Android's read-only diagnostics.
adb_cmd=(adb)
if [[ $# == 1 ]]; then
    adb_cmd+=(-s "$1")
elif [[ $# != 0 ]]; then
    echo "Usage: $0 [adb-device-serial]" >&2
    exit 2
fi

"${adb_cmd[@]}" get-state >/dev/null
echo "USB devices"
"${adb_cmd[@]}" shell dumpsys usb
echo "Audio ports, profiles and active patches"
"${adb_cmd[@]}" shell dumpsys media.audio_policy
echo "Audio mixer threads and track formats"
"${adb_cmd[@]}" shell dumpsys media.audio_flinger
