#!/usr/bin/env bash
# Usage: player/make-fixtures.sh
#
# Regenerates the MP3s the player's gapless checks play (player/src/androidTest/assets/gapless):
# one continuous 220 Hz tone, 6 s, cut in two at a sample that isn't on an MP3 frame boundary, and
# each half encoded on its own, so any gap or click at the cut shows in the decoded output.
#   with-header-1/2.mp3  320 kbps with FFmpeg's LAME-style header (encoder delay and padding)
#   no-header-1/2.mp3    the same without it, so the check can show it does see gaps
# The second half of each pair is tagged REPLAYGAIN_TRACK_GAIN -6.02 dB (half the amplitude).
#
# Needs: ffmpeg (sudo apt install ffmpeg).
set -euo pipefail
for arg in "$@"; do
    [[ $arg != -h && $arg != --help ]] || { awk 'NR > 1 && !/^#/ { exit } NR > 1 { sub(/^# ?/, ""); print }' "$0"; exit 0; }
done

out="$(cd "$(dirname "$0")" && pwd)/src/androidTest/assets/gapless"
mkdir -p "$out"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
ff() { ffmpeg -hide_banner -loglevel error -y "$@"; }

# The cut: 132301 samples, 114.8 MP3 frames. Keep CUT in step with GaplessAndroidTest.
cut=132301
ff -f lavfi -i "aevalsrc=0.5*sin(2*PI*220*t)|0.5*sin(2*PI*220*t):s=44100:d=6" -c:a pcm_s16le "$work/tone.wav"
ff -i "$work/tone.wav" -af "atrim=end_sample=$cut" "$work/1.wav"
ff -i "$work/tone.wav" -af "atrim=start_sample=$cut,asetpts=N/SR/TB" "$work/2.wav"

rg=(-metadata REPLAYGAIN_TRACK_GAIN="-6.02 dB")
ff -i "$work/1.wav" -c:a libmp3lame -b:a 320k -metadata title="Gapless 1" "$out/with-header-1.mp3"
ff -i "$work/2.wav" -c:a libmp3lame -b:a 320k -metadata title="Gapless 2" "${rg[@]}" "$out/with-header-2.mp3"
ff -i "$work/1.wav" -c:a libmp3lame -b:a 320k -write_xing 0 -metadata title="No header 1" "$out/no-header-1.mp3"
ff -i "$work/2.wav" -c:a libmp3lame -b:a 320k -write_xing 0 -metadata title="No header 2" "${rg[@]}" "$out/no-header-2.mp3"
ls -l "$out"
