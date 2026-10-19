#!/usr/bin/env bash
# Sets up a machine on your tailnet to hold MediaSync's backups: restic's rest-server in
# append-only mode, keeping them in DIR, published on the tailnet with HTTPS by `tailscale serve`.
# Any Linux machine with systemd works (a Raspberry Pi, a NAS, an old PC). On that machine, from a
# copy of this folder:
#   sudo bash target-setup.sh --path /mnt/backup/restic [--https-port 443] mediasync.htpasswd
# The htpasswd file is the server's login; the server's backup/install.sh writes it as
# /etc/mediasync/backup.htpasswd. DIR is best on its own drive: if it's missing (the drive isn't
# mounted), the service doesn't start rather than filling the system disk. Safe to run again: it
# updates the program, the login and the folder.
set -euo pipefail
cd "$(dirname "$0")"

VERSION=0.14.0
declare -A SHA256=(
    [amd64]=4c9c95bc079a0334e81fad379b19dc5c3353c71c2c88d652cafce2081c2b1c66
    [arm64]=cef139cbe8b27b16bda731d17f093b0aa466b8c60b136c12d78b6f2bff3daf22
    [armv7]=1b01b6a98b59d68ae8cf472189a92c2c17d0a49f8a3542fc986ea7a921e55742
    [armv6]=da53133964532371d3eb0c29ed4372ba1eef3296df4447f7715bae5bfafb584c
    [386]=de12ba817a8153be2ba4cbe6ae51ad2ca4c26d8210084659c1f421839bdb49c7
)
USAGE="Usage: sudo bash target-setup.sh --path DIR [--https-port PORT] mediasync.htpasswd"

[[ $EUID -eq 0 ]] || { echo "Run with sudo." >&2; exit 1; }
DATA=""
HTTPS_PORT=443
HTPASSWD=""
while [[ $# -gt 0 ]]; do
    case $1 in
        --path) DATA=$2; shift 2 ;;
        --https-port) HTTPS_PORT=$2; shift 2 ;;
        -*) echo "Unknown option: $1" >&2; echo "$USAGE" >&2; exit 2 ;;
        *) HTPASSWD=$1; shift ;;
    esac
done
[[ -n $DATA && -f $HTPASSWD ]] || { echo "$USAGE" >&2; exit 2; }
[[ $HTTPS_PORT =~ ^[0-9]+$ ]] || { echo "--https-port takes a number." >&2; exit 2; }
DATA=$(realpath -m "$DATA")
[[ $DATA =~ ^[A-Za-z0-9/._-]+$ ]] || { echo "Choose a folder whose path has no spaces or special characters." >&2; exit 2; }
case $DATA in
    /home/*|/root/*|/run/user/*) echo "The backups can't live in a home folder: the service has no access there." >&2; exit 1 ;;
esac
[[ -d $(dirname "$DATA") ]] || { echo "$(dirname "$DATA") doesn't exist. Is the drive mounted?" >&2; exit 1; }

case $(uname -m) in
    x86_64) ARCH=amd64 ;;
    aarch64 | arm64) ARCH=arm64 ;;
    armv7l) ARCH=armv7 ;;
    armv6l) ARCH=armv6 ;;
    i386 | i686) ARCH=386 ;;
    *) echo "No rest-server download for $(uname -m)." >&2; exit 1 ;;
esac

if ! command -v tailscale >/dev/null; then
    curl -fsSL https://tailscale.com/install.sh | sh
fi
if ! tailscale status >/dev/null 2>&1; then
    echo "Join the tailnet: open the link below and approve this machine."
    tailscale up
fi

if [[ $(/usr/local/bin/rest-server --version 2>/dev/null) != *"$VERSION"* ]]; then
    TMP=$(mktemp -d)
    trap 'rm -rf "$TMP"' EXIT
    NAME=rest-server_${VERSION}_linux_$ARCH
    curl -fsSL -o "$TMP/$NAME.tar.gz" "https://github.com/restic/rest-server/releases/download/v$VERSION/$NAME.tar.gz"
    echo "${SHA256[$ARCH]}  $TMP/$NAME.tar.gz" | sha256sum --check --quiet
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

sed "s|@DATA@|$DATA|g" rest-server.service > /etc/systemd/system/rest-server.service
chmod 644 /etc/systemd/system/rest-server.service
systemctl daemon-reload
systemctl enable rest-server.service
systemctl restart rest-server.service

tailscale serve --bg --https="$HTTPS_PORT" http://127.0.0.1:8000 >/dev/null
NAME=$(tailscale status --json | python3 -c 'import json, sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))')
sleep 1
systemctl --no-pager --lines=3 status rest-server.service
echo
PORT_ARG=""
[[ $HTTPS_PORT == 443 ]] || PORT_ARG=" --port $HTTPS_PORT"
echo "Backups are kept in $DATA and served at https://$NAME$([[ $HTTPS_PORT == 443 ]] || echo ":$HTTPS_PORT")"
echo "On the MediaSync server, run its backup/install.sh again: sudo …/backup/install.sh --target ${NAME%%.*}$PORT_ARG"
