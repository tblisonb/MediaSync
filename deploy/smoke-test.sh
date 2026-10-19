#!/usr/bin/env bash
# Usage: smoke-test.sh SERVER_URL PAIRING_CODE
#
# Checks a running MediaSync server end to end over HTTP, the way a device reaches it. It pairs a
# device named "Smoke test", uploads one small random file and reads it back. It never pushes, so
# the library and its history are untouched. Revoke the "Smoke test" device in the web UI
# afterwards.
#
#   SERVER_URL     The server's address, e.g. https://my-pc.tail1234.ts.net:8443
#   PAIRING_CODE   A code from the web UI: Devices > Pair a device.
#
# Example:
#   deploy/smoke-test.sh https://my-pc.tail1234.ts.net:8443 ABCD-EFGH
#
# Needs: curl and jq (sudo apt install jq).
set -euo pipefail
# -h or --help prints the comment at the top of this file.
for arg in "$@"; do
    [[ $arg != -h && $arg != --help ]] || { awk 'NR > 1 && !/^#/ { exit } NR > 1 { sub(/^# ?/, ""); print }' "$0"; exit 0; }
done
URL=${1:?usage: smoke-test.sh SERVER_URL PAIRING_CODE}
CODE=${2:?usage: smoke-test.sh SERVER_URL PAIRING_CODE}
API=$URL/api/v1
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
command -v jq >/dev/null || { echo "Needs jq: apt install jq" >&2; exit 1; }

step() { printf '%-40s' "$1"; }
ok() { echo "ok${1:+ ($1)}"; }

step "Server answers"
curl -sSf "$API/health" | grep -q '"ok":true' && ok

step "Pairing"
TOKEN=$(curl -sSf -H 'Content-Type: application/json' \
    -d "{\"code\":\"$CODE\",\"name\":\"Smoke test\",\"kind\":\"desktop\"}" "$API/pair" | jq -r .token)
AUTH=(-H "Authorization: Bearer $TOKEN")
ok

step "Changes"
HEAD=$(curl -sSf "${AUTH[@]}" "$API/changes?since=0" | jq -r .head)
ok "head r$HEAD"

step "Resumable upload"
head -c 65536 /dev/urandom > "$TMP/smoke.bin"
HASH=$(sha256sum "$TMP/smoke.bin" | cut -d' ' -f1)
ID=$(curl -sSf "${AUTH[@]}" -H 'Content-Type: application/json' \
    -d "{\"hash\":\"$HASH\",\"size\":65536,\"name\":\"smoke.bin\"}" "$API/uploads" | jq -r .id)
head -c 30000 "$TMP/smoke.bin" > "$TMP/part1"
tail -c +30001 "$TMP/smoke.bin" > "$TMP/part2"
curl -sSf "${AUTH[@]}" -X PATCH -H 'Upload-Offset: 0' -H 'Content-Type: application/octet-stream' --data-binary @"$TMP/part1" "$API/uploads/$ID" >/dev/null
OFFSET=$(curl -sSf "${AUTH[@]}" "$API/uploads/$ID" | jq -r .offset)
[[ $OFFSET == 30000 ]] || { echo "resume offset was $OFFSET"; exit 1; }
DONE=$(curl -sSf "${AUTH[@]}" -X PATCH -H 'Upload-Offset: 30000' -H 'Content-Type: application/octet-stream' --data-binary @"$TMP/part2" "$API/uploads/$ID" | jq -r .done)
[[ $DONE == true ]] || { echo "upload didn't finish"; exit 1; }
ok

step "Ranged download"
curl -sSf "${AUTH[@]}" -H 'Range: bytes=1000-1999' "$API/blobs/$HASH" > "$TMP/range"
cmp -s "$TMP/range" <(tail -c +1001 "$TMP/smoke.bin" | head -c 1000) && ok

step "Events"
# The stream never ends, so curl gives up after 3 seconds with whatever arrived.
EVENTS=$(curl -sS -N --max-time 3 "${AUTH[@]}" "$API/events" 2>/dev/null || true)
grep -q '"head"' <<<"$EVENTS" && ok

echo
echo "All checks passed. Revoke the \"Smoke test\" device in the web UI (Devices)."
