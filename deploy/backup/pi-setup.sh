#!/usr/bin/env bash
# Sets up the Raspberry Pi as the backup target: restic's rest-server in append-only mode on the
# backup drive, published on the tailnet with HTTPS by `tailscale serve`. On the Pi, from a copy of
# this folder:
#   sudo bash pi-setup.sh mediasync.htpasswd
# The htpasswd file is the server PC's login; deploy/backup/install.sh writes it there as
# /etc/mediasync/backup.htpasswd. Safe to run again: it updates the program and the login.
set -euo pipefail
cd "$(dirname "$0")"

VERSION=0.14.0
SHA256=cef139cbe8b27b16bda731d17f093b0aa466b8c60b136c12d78b6f2bff3daf22
DATA=/mnt/storage/restic

[[ $EUID -eq 0 ]] || { echo "Run with sudo." >&2; exit 1; }
HTPASSWD=${1:-}
[[ -f $HTPASSWD ]] || { echo "Usage: sudo bash pi-setup.sh mediasync.htpasswd" >&2; exit 2; }
mountpoint -q /mnt/storage || { echo "The backup drive isn't mounted at /mnt/storage." >&2; exit 1; }
[[ $(uname -m) == aarch64 ]] || { echo "This expects 64-bit Raspberry Pi OS (aarch64)." >&2; exit 1; }

if ! command -v tailscale >/dev/null; then
    curl -fsSL https://tailscale.com/install.sh | sh
fi
if ! tailscale status >/dev/null 2>&1; then
    echo "Join the tailnet: open the link below and approve this Pi."
    tailscale up
fi

if [[ $(/usr/local/bin/rest-server --version 2>/dev/null) != *"$VERSION"* ]]; then
    TMP=$(mktemp -d)
    trap 'rm -rf "$TMP"' EXIT
    NAME=rest-server_${VERSION}_linux_arm64
    curl -fsSL -o "$TMP/$NAME.tar.gz" "https://github.com/restic/rest-server/releases/download/v$VERSION/$NAME.tar.gz"
    echo "$SHA256  $TMP/$NAME.tar.gz" | sha256sum --check --quiet
    tar -xzf "$TMP/$NAME.tar.gz" -C "$TMP"
    install -o root -g root -m 755 "$TMP/$NAME/rest-server" /usr/local/bin/rest-server
    echo "Installed rest-server $VERSION."
fi

if ! id restic >/dev/null 2>&1; then
    useradd --system --user-group --home-dir /nonexistent --no-create-home --shell /usr/sbin/nologin restic
fi
install -d -o restic -g restic -m 700 "$DATA"
install -d -m 755 /etc/rest-server
install -o root -g restic -m 640 "$HTPASSWD" /etc/rest-server/htpasswd

install -m 644 rest-server.service /etc/systemd/system/rest-server.service
systemctl daemon-reload
systemctl enable rest-server.service
systemctl restart rest-server.service

tailscale serve --bg --https=443 http://127.0.0.1:8000 >/dev/null
NAME=$(tailscale status --json | python3 -c 'import json, sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))')
sleep 1
systemctl --no-pager --lines=3 status rest-server.service
echo
echo "Backups are served at https://$NAME"
echo "On the server PC, run: sudo deploy/backup/install.sh --pi ${NAME%%.*}"
