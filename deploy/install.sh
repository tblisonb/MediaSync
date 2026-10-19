#!/usr/bin/env bash
# Usage: sudo install.sh [--repo DIR]
#
# Installs or updates the MediaSync server as a system service (the mediasync service, run by its
# own mediasync user), and publishes it on your tailnet at https://<this machine>.ts.net:8443.
# Also installs mediasync-gamedb, which downloads the game databases that identify games.
#
# The first run creates the mediasync user and the repository, and asks for the web UI's admin
# password and the music tag policy. Later runs only update the program, keeping the repository
# and settings. Run it again after fixing Tailscale, and it publishes the server then.
#
# Run it from an unpacked release, or from the source after building:
#   tar xzf mediasync-server-<version>.tar.gz && sudo mediasync-server-<version>/install.sh
#   ./gradlew :server:installDist && sudo deploy/install.sh     (scripts/update.sh server does both)
#
# Options:
#   --repo DIR   Where the repository lives, chosen on the first run (default /var/lib/mediasync).
#                It can't be in a home folder. Give it again later only to point at a moved
#                repository.
#   -h, --help   Show this help.
#
# Needs: systemd, Java 17 or newer (sudo apt install openjdk-21-jre-headless), Python 3, and
# Tailscale with HTTPS certificates turned on (admin console > DNS).
set -euo pipefail
# -h or --help prints the comment at the top of this file.
for arg in "$@"; do
    [[ $arg != -h && $arg != --help ]] || { awk 'NR > 1 && !/^#/ { exit } NR > 1 { sub(/^# ?/, ""); print }' "$0"; exit 0; }
done
HERE="$(cd "$(dirname "$0")" && pwd)"

DROPIN_DIR=/etc/systemd/system/mediasync.service.d
DROPIN=$DROPIN_DIR/repo.conf
HTTPS_PORT=8443

[[ $EUID -eq 0 ]] || { echo "Run with sudo." >&2; exit 1; }
if [[ -x $HERE/bin/mediasync-server ]]; then
    # An unpacked release: the program is right here.
    DIST=$HERE
else
    cd "$HERE/.."
    DIST=$PWD/server/build/install/mediasync-server
    [[ -x $DIST/bin/mediasync-server ]] || { echo "Build first: ./gradlew :server:installDist" >&2; exit 1; }
    # A failed build leaves the previous one in place; don't install that by mistake.
    STALE=$(find server/src shared/src server/build.gradle.kts shared/build.gradle.kts gradle/libs.versions.toml \
        -type f -newer "$DIST/lib/server.jar" -print -quit)
    [[ -z $STALE ]] || { echo "The build is older than the code ($STALE changed since). Build again: ./gradlew :server:installDist" >&2; exit 1; }
fi
command -v java >/dev/null || { echo "Install a Java runtime first: apt install openjdk-21-jre-headless" >&2; exit 1; }

REPO=""
while [[ $# -gt 0 ]]; do
    case $1 in
        --repo) REPO=$2; shift 2 ;;
        *) echo "Unknown option: $1 (see --help)" >&2; exit 2 ;;
    esac
done
if [[ -z $REPO && -f $DROPIN ]]; then
    REPO=$(sed -n 's/^Environment=MEDIASYNC_REPO=//p' "$DROPIN")
fi
REPO=${REPO:-/var/lib/mediasync}
REPO=$(realpath -m "$REPO")
case $REPO in
    /home/*|/root/*|/run/user/*) echo "The repository can't live in a home folder: the service has no access there." >&2; exit 1 ;;
esac

if ! id mediasync >/dev/null 2>&1; then
    useradd --system --user-group --home-dir /nonexistent --no-create-home --shell /usr/sbin/nologin mediasync
    echo "Created the mediasync system user."
fi

# Program files: owned by root, so the service can't modify its own code.
rm -rf /opt/mediasync.new
cp -r "$DIST" /opt/mediasync.new
chown -R root:root /opt/mediasync.new
chmod -R u=rwX,go=rX /opt/mediasync.new
rm -rf /opt/mediasync.old
if [[ -d /opt/mediasync ]]; then mv /opt/mediasync /opt/mediasync.old; fi
mv /opt/mediasync.new /opt/mediasync
rm -rf /opt/mediasync.old

install -d -o mediasync -g mediasync -m 700 "$REPO"
if [[ ! -f $REPO/admin.json ]]; then
    runuser -u mediasync -- /opt/mediasync/bin/mediasync-server setup --repo "$REPO"
fi

# Devices reach the server over Tailscale, at this machine's tailnet name (also the address in the
# pairing QR code). NO_TAILNET says why it can't be published there, if it can't.
PUBLIC_URL=""
NO_TAILNET=""
if ! command -v tailscale >/dev/null; then
    NO_TAILNET="Tailscale isn't installed on this machine (https://tailscale.com/download)."
else
    read -r TS_STATE TS_NAME TS_CERTS < <(tailscale status --json 2>/dev/null | python3 -c '
import json, sys
d = json.load(sys.stdin)
print(d.get("BackendState") or "-", (d.get("Self") or {}).get("DNSName", "").rstrip(".") or "-", len(d.get("CertDomains") or []))
' || echo "- - 0")
    if [[ $TS_STATE != Running ]]; then
        NO_TAILNET="Tailscale isn't connected: run sudo tailscale up."
    elif [[ $TS_CERTS == 0 ]]; then
        NO_TAILNET="HTTPS certificates are off for your tailnet: turn them on in the Tailscale admin console, on the DNS page."
    else
        PUBLIC_URL="https://$TS_NAME:$HTTPS_PORT"
    fi
fi

install -d -m 755 "$DROPIN_DIR"
cat > "$DROPIN" <<CONF
[Service]
Environment=MEDIASYNC_REPO=$REPO
Environment=MEDIASYNC_PUBLIC_URL=$PUBLIC_URL
ReadWritePaths=$REPO
CONF

install -m 644 "$HERE/mediasync.service" /etc/systemd/system/mediasync.service

# The game databases (game identification) are downloaded by a service of their own, the only part
# of MediaSync that reaches the internet: weekly, and when the web UI asks (it leaves
# gamedb/update-requested). It sees only the repository's gamedb/ folder.
install -d -o mediasync -g mediasync -m 700 "$REPO/gamedb"
install -d -m 755 /etc/systemd/system/mediasync-gamedb.service.d
cat > /etc/systemd/system/mediasync-gamedb.service.d/repo.conf <<CONF
[Service]
Environment=MEDIASYNC_REPO=$REPO
TemporaryFileSystem=$REPO:ro
BindPaths=$REPO/gamedb
CONF
cat > /etc/systemd/system/mediasync-gamedb.path <<UNIT
[Unit]
Description=Download MediaSync's game databases when the web UI asks

[Path]
PathExists=$REPO/gamedb/update-requested

[Install]
WantedBy=multi-user.target
UNIT
install -m 644 "$HERE/mediasync-gamedb.service" /etc/systemd/system/mediasync-gamedb.service
install -m 644 "$HERE/mediasync-gamedb.timer" /etc/systemd/system/mediasync-gamedb.timer

systemctl daemon-reload
systemctl enable mediasync.service
systemctl restart mediasync.service
systemctl enable --now mediasync-gamedb.timer mediasync-gamedb.path
# The first download, in the background (about 150 MB; it takes a minute or so).
[[ -f $REPO/gamedb/index.db ]] || systemctl start --no-block mediasync-gamedb.service

# Publish it on the tailnet, with HTTPS (on 8443, leaving 443 for anything else on this machine).
if [[ -n $PUBLIC_URL ]]; then
    tailscale serve --bg --https=$HTTPS_PORT http://127.0.0.1:8766 >/dev/null
fi

sleep 3
systemctl --no-pager --lines=5 status mediasync.service
echo
if [[ -n $PUBLIC_URL ]]; then
    echo "MediaSync is on your tailnet at $PUBLIC_URL (the web UI; devices pair there)."
else
    echo "MediaSync is running, but other devices can't reach it yet. $NO_TAILNET"
    echo "Then run this script again to publish it."
fi
