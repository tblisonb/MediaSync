#!/usr/bin/env bash
# Installs or updates the MediaSync server as a system service. From the repo root:
#   ./gradlew :server:installDist && sudo deploy/install.sh [--repo DIR]
# The first run creates the mediasync user and the repository (asking for an admin password and
# the tag policy); later runs only update the program. --repo picks the repository folder the
# first time (default /var/lib/mediasync); give it again later only to point at a moved repository.
set -euo pipefail
cd "$(dirname "$0")/.."

DIST=server/build/install/mediasync-server
DROPIN_DIR=/etc/systemd/system/mediasync.service.d
DROPIN=$DROPIN_DIR/repo.conf
HTTPS_PORT=8443

[[ $EUID -eq 0 ]] || { echo "Run with sudo." >&2; exit 1; }
[[ -x $DIST/bin/mediasync-server ]] || { echo "Build first: ./gradlew :server:installDist" >&2; exit 1; }
# A failed build leaves the previous one in place; don't install that by mistake.
STALE=$(find server/src shared/src server/build.gradle.kts shared/build.gradle.kts gradle/libs.versions.toml \
    -type f -newer "$DIST/lib/server.jar" -print -quit)
[[ -z $STALE ]] || { echo "The build is older than the code ($STALE changed since). Build again: ./gradlew :server:installDist" >&2; exit 1; }
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

install -d -m 755 "$DROPIN_DIR"
cat > "$DROPIN" <<CONF
[Service]
Environment=MEDIASYNC_REPO=$REPO
ReadWritePaths=$REPO
CONF

install -m 644 deploy/mediasync.service /etc/systemd/system/mediasync.service
systemctl daemon-reload
systemctl enable mediasync.service
systemctl restart mediasync.service

# Publish it on the tailnet, beside Remind (which has port 443).
if command -v tailscale >/dev/null; then
    tailscale serve --bg --https=$HTTPS_PORT http://127.0.0.1:8766 >/dev/null
    URL="https://$(tailscale status --json | python3 -c 'import json, sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))'):$HTTPS_PORT"
    echo "On the tailnet at $URL"
fi

sleep 3
systemctl --no-pager --lines=5 status mediasync.service
