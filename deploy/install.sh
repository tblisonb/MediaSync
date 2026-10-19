#!/usr/bin/env bash
# Installs or updates the MediaSync server as a system service, from an unpacked release:
#   tar xzf mediasync-server-<version>.tar.gz && sudo mediasync-server-<version>/install.sh [--repo DIR]
# or from the repo root, after building (scripts/update.sh server does both):
#   ./gradlew :server:installDist && sudo deploy/install.sh [--repo DIR]
# The first run creates the mediasync user and the repository (asking for an admin password and
# the tag policy); later runs only update the program. --repo picks the repository folder the
# first time (default /var/lib/mediasync); give it again later only to point at a moved repository.
set -euo pipefail
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
        *) echo "Unknown option: $1" >&2; exit 2 ;;
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
systemctl daemon-reload
systemctl enable mediasync.service
systemctl restart mediasync.service

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
