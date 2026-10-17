#!/usr/bin/env bash
# Generates the small audio files the tag round-trip tests run on (shared, and the Android
# instrumented test). Each is 2 s of tones, tagged the way rippers tag things, including the junk
# (comments, encoder fields) the tag policy strips. Needs ffmpeg: sudo apt install ffmpeg
set -euo pipefail

out="$(cd "$(dirname "$0")/.." && pwd)/shared/src/test/resources/fixtures"
mkdir -p "$out"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

ff() { ffmpeg -hide_banner -loglevel error -y "$@"; }

ff -f lavfi -i "sine=frequency=440:duration=2" -f lavfi -i "sine=frequency=660:duration=2" \
    -filter_complex amerge=inputs=2 -ar 44100 "$work/tone.wav"
ff -f lavfi -i "color=c=0x3F51B5:s=64x64" -frames:v 1 "$work/cover.jpg"

tags=(
    -metadata title="Fixture Tone" -metadata artist="Fixture Artist" -metadata album="Fixture Album"
    -metadata album_artist="Fixture Album Artist" -metadata date=2026 -metadata genre=Test
    -metadata track=3 -metadata disc=1
    -metadata comment="Ripped by SomeRipper 1.0" -metadata encoded_by="SomeRipper"
)
with_cover=(-i "$work/cover.jpg" -map 0:a -map 1:v -disposition:v attached_pic -c:v copy)

ff -i "$work/tone.wav" "${with_cover[@]}" -c:a libmp3lame -b:a 128k -id3v2_version 3 -write_id3v1 1 "${tags[@]}" "$out/tone.mp3"
ff -i "$work/tone.wav" -c:a libmp3lame -q:a 4 -id3v2_version 4 "${tags[@]}" "$out/tone-vbr-id3v24.mp3"
ff -i "$work/tone.wav" "${with_cover[@]}" -c:a flac "${tags[@]}" "$out/tone.flac"
ff -i "$work/tone.wav" "${with_cover[@]}" -c:a aac -b:a 128k "${tags[@]}" "$out/tone-aac.m4a"
ff -i "$work/tone.wav" -c:a alac "${tags[@]}" "$out/tone-alac.m4a"
ff -i "$work/tone.wav" -c:a libvorbis -q:a 3 "${tags[@]}" "$out/tone.ogg"
ff -i "$work/tone.wav" -c:a libopus -b:a 96k "${tags[@]}" "$out/tone.opus"
# Only the old fixed-size tag at the end, as rips from around 2000 often have. ffmpeg's own ID3v1
# leaves most fields out, so the 128 bytes are written here: ID3v1.1, track 3, genre 17 (Rock).
ff -i "$work/tone.wav" -c:a libmp3lame -b:a 128k -id3v2_version 0 -write_xing 0 "$out/tone-id3v1.mp3"
printf 'TAG%-30s%-30s%-30s%-4s%-28s\0\x03\x11' "Fixture Tone" "Fixture Artist" "Fixture Album" 2026 "Ripped by SomeRipper 1.0" \
    >> "$out/tone-id3v1.mp3"
# Windows Media Audio, which the server can convert to MP3.
ff -i "$work/tone.wav" "${with_cover[@]}" -c:a wmav2 -b:a 128k "${tags[@]}" "$out/tone.wma"

ls -l "$out"
