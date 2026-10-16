#!/usr/bin/env bash
# Sets up the nightly backup of the MediaSync repository to the Pi (see pi-setup.sh). From the repo root:
#   sudo deploy/backup/install.sh --pi raspberrypi
# The first run creates the backup's password and the Pi login, and writes the login for the Pi to
# /etc/mediasync/backup.htpasswd. Once the Pi is set up with it, run this again: it creates the
# backup repository and starts the timers. Later runs only update the units.
set -euo pipefail
cd "$(dirname "$0")/../.."

CONF=/etc/mediasync/backup
HTPASSWD=/etc/mediasync/backup.htpasswd
UNITS=(mediasync-backup.service mediasync-backup.timer mediasync-backup-check.service mediasync-backup-check.timer)

[[ $EUID -eq 0 ]] || { echo "Run with sudo." >&2; exit 1; }
SERVER_DROPIN=/etc/systemd/system/mediasync.service.d/repo.conf
[[ -f $SERVER_DROPIN ]] || { echo "Install the server first: sudo deploy/install.sh" >&2; exit 1; }
REPO=$(sed -n 's/^Environment=MEDIASYNC_REPO=//p' "$SERVER_DROPIN")

PI=""
while [[ $# -gt 0 ]]; do
    case $1 in
        --pi) PI=$2; shift 2 ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

if ! command -v restic >/dev/null; then
    apt-get install -y restic
fi

install -d -m 755 /etc/mediasync
install -d -m 700 "$CONF"
if [[ ! -f $CONF/password ]]; then
    (umask 077 && openssl rand -base64 32 > "$CONF/password")
fi
if [[ ! -f $CONF/repository ]]; then
    [[ -n $PI ]] || { echo "Give the Pi's tailnet name: --pi raspberrypi" >&2; exit 2; }
    DOMAIN=$(tailscale status --json | python3 -c 'import json, sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip(".").split(".", 1)[1])')
    LOGIN=$(openssl rand -hex 24)
    (umask 077 && echo "rest:https://mediasync:$LOGIN@$PI.$DOMAIN/mediasync/" > "$CONF/repository")
    # rest-server takes {SHA} hashes; unsalted SHA-1 is fine for a 192-bit random login.
    echo "mediasync:{SHA}$(printf %s "$LOGIN" | openssl dgst -sha1 -binary | base64)" > "$HTPASSWD"
    chmod 644 "$HTPASSWD"
fi

install -d -m 755 /etc/systemd/system/mediasync-backup.service.d
cat > /etc/systemd/system/mediasync-backup.service.d/repo.conf <<CONF
[Service]
Environment=MEDIASYNC_REPO=$REPO
ReadWritePaths=$REPO
CONF
for unit in "${UNITS[@]}"; do
    install -m 644 "deploy/backup/$unit" "/etc/systemd/system/$unit"
done
systemctl daemon-reload

export RESTIC_REPOSITORY_FILE=$CONF/repository RESTIC_PASSWORD_FILE=$CONF/password
set +e
restic cat config --no-cache >/dev/null 2>&1
STATUS=$?
set -e
case $STATUS in
    0) ;;
    10)
        restic init --no-cache
        echo
        echo "The backup's password is below. Save it in your password manager: without it the backup"
        echo "can't be read if this PC is lost. (It's also in $CONF/password.)"
        echo
        echo "    $(cat "$CONF/password")"
        echo
        ;;
    *)
        echo "Can't reach the backup on the Pi yet (restic exit code $STATUS)."
        echo "Set up the Pi with $HTPASSWD (deploy/backup/pi-setup.sh), then run this again."
        exit 1
        ;;
esac

systemctl enable --now mediasync-backup.timer mediasync-backup-check.timer
systemctl --no-pager list-timers mediasync-backup.timer mediasync-backup-check.timer
echo
echo "To back up now:  sudo systemctl start --no-block mediasync-backup"
echo "Progress:        journalctl -fu mediasync-backup"
