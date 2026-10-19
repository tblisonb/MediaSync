#!/usr/bin/env bash
# Usage: sudo backup/install.sh --target NAME [--port PORT]
#        sudo backup/install.sh --local DIR
#        sudo backup/install.sh                  (later runs)
#
# Sets up nightly backups of the MediaSync repository with restic (optional), on the server. The
# script is in backup/ in the server's release folder, /opt/mediasync/backup/ once installed, and
# deploy/backup/ in the source. A backup runs every night around 03:30, and a check that reads back
# 5% of the data runs every Sunday.
#
#   --target NAME   Back up to another machine on your tailnet, NAME being its tailnet name (e.g.
#                   raspberrypi). Recommended: it's append-only, so nothing on this PC can delete
#                   or rewrite the backups. The first run writes that machine's login to
#                   /etc/mediasync/backup.htpasswd. Copy that file and target-setup.sh to the
#                   machine and set it up (target-setup.sh --help), then run this again.
#   --port PORT     The machine's HTTPS port, if target-setup.sh was given another --https-port
#                   than 443.
#   --local DIR     Back up to a folder on this PC instead, best on another drive. It's simpler,
#                   but not append-only, and lost along with this PC.
#   -h, --help      Show this help.
#
# The first time it reaches the backup, it shows restic's password: save it, because without it the
# backups can't be read. The password stays in /etc/mediasync/backup/password, and where the
# backups go is in /etc/mediasync/backup/repository. Later runs need no options: they only update
# the units. To back up somewhere else, remove /etc/mediasync/backup/repository and run this again
# (the password stays the same).
#
# Back up now:  sudo systemctl start --no-block mediasync-backup
# Follow it:    journalctl -fu mediasync-backup
set -euo pipefail
# -h or --help prints the comment at the top of this file.
for arg in "$@"; do
    [[ $arg != -h && $arg != --help ]] || { awk 'NR > 1 && !/^#/ { exit } NR > 1 { sub(/^# ?/, ""); print }' "$0"; exit 0; }
done
HERE="$(cd "$(dirname "$0")" && pwd)"

CONF=/etc/mediasync/backup
HTPASSWD=/etc/mediasync/backup.htpasswd
UNITS=(mediasync-backup.service mediasync-backup.timer mediasync-backup-check.service mediasync-backup-check.timer)
USAGE="Usage: sudo $0 --target NAME [--port PORT] | --local DIR (see --help)"

[[ $EUID -eq 0 ]] || { echo "Run with sudo." >&2; exit 1; }
SERVER_DROPIN=/etc/systemd/system/mediasync.service.d/repo.conf
[[ -f $SERVER_DROPIN ]] || { echo "Install the MediaSync server first." >&2; exit 1; }
REPO=$(sed -n 's/^Environment=MEDIASYNC_REPO=//p' "$SERVER_DROPIN")

TARGET=""
PORT=""
LOCAL=""
while [[ $# -gt 0 ]]; do
    case $1 in
        --target | --pi) TARGET=$2; shift 2 ;;
        --port) PORT=$2; shift 2 ;;
        --local) LOCAL=$2; shift 2 ;;
        *) echo "Unknown option: $1" >&2; echo "$USAGE" >&2; exit 2 ;;
    esac
done
[[ -z $TARGET || -z $LOCAL ]] || { echo "Choose --target or --local, not both." >&2; exit 2; }
[[ -z $PORT || $PORT =~ ^[0-9]+$ ]] || { echo "--port takes a number." >&2; exit 2; }

if ! command -v restic >/dev/null; then
    if command -v apt-get >/dev/null; then
        apt-get install -y restic
    else
        echo "Install restic first (https://restic.net), then run this again." >&2
        exit 1
    fi
fi

# A backup folder on this PC must be somewhere the service can write, and apart from the repository.
check_local() {
    [[ $1 =~ ^/[A-Za-z0-9/._-]+$ ]] || { echo "Choose a folder whose path has no spaces or special characters." >&2; exit 2; }
    case $1 in
        /home/*|/root/*|/run/user/*) echo "The backup can't live in a home folder: the service has no access there." >&2; exit 1 ;;
        "$REPO" | "$REPO"/*) echo "The backup can't be inside the repository it backs up." >&2; exit 1 ;;
    esac
    [[ $REPO != "$1"/* ]] || { echo "The backup folder can't contain the repository." >&2; exit 1; }
    [[ -d $(dirname "$1") ]] || { echo "$(dirname "$1") doesn't exist. Is the drive mounted?" >&2; exit 1; }
}

install -d -m 755 /etc/mediasync
install -d -m 700 "$CONF"
if [[ ! -f $CONF/password ]]; then
    (umask 077 && openssl rand -base64 32 > "$CONF/password")
fi
if [[ ! -f $CONF/repository ]]; then
    if [[ -n $LOCAL ]]; then
        LOCAL=$(realpath -m "$LOCAL")
        check_local "$LOCAL"
        (umask 077 && echo "$LOCAL" > "$CONF/repository")
    elif [[ -n $TARGET ]]; then
        DOMAIN=$(tailscale status --json | python3 -c 'import json, sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip(".").split(".", 1)[1])')
        LOGIN=$(openssl rand -hex 24)
        (umask 077 && echo "rest:https://mediasync:$LOGIN@$TARGET.$DOMAIN${PORT:+:$PORT}/mediasync/" > "$CONF/repository")
        # rest-server takes {SHA} hashes; unsalted SHA-1 is fine for a 192-bit random login.
        echo "mediasync:{SHA}$(printf %s "$LOGIN" | openssl dgst -sha1 -binary | base64)" > "$HTPASSWD"
        chmod 644 "$HTPASSWD"
    else
        echo "$USAGE" >&2
        exit 2
    fi
elif [[ -n $TARGET || -n $LOCAL ]]; then
    echo "Already set up to back up to $(sed 's|//mediasync:[^@]*@|//|' "$CONF/repository")."
    echo "To back up somewhere else, remove $CONF/repository and run this again."
    exit 1
fi

# A folder on this PC: the services run as mediasync, which can write only to the folders they
# list, and never write to the system disk when the backup's drive is missing.
LOCAL_DIR=""
if [[ $(cat "$CONF/repository") != rest:* ]]; then
    LOCAL_DIR=$(cat "$CONF/repository")
    check_local "$LOCAL_DIR"
    install -d -o mediasync -g mediasync -m 700 "$LOCAL_DIR"
fi

install -d -m 755 /etc/systemd/system/mediasync-backup.service.d /etc/systemd/system/mediasync-backup-check.service.d
cat > /etc/systemd/system/mediasync-backup.service.d/repo.conf <<CONF
[Service]
Environment=MEDIASYNC_REPO=$REPO
ReadWritePaths=$REPO
CONF
LOCAL_DROPIN=local.conf
if [[ -n $LOCAL_DIR ]]; then
    for unit in mediasync-backup mediasync-backup-check; do
        cat > "/etc/systemd/system/$unit.service.d/$LOCAL_DROPIN" <<CONF
[Unit]
RequiresMountsFor=$LOCAL_DIR
ConditionPathIsDirectory=$LOCAL_DIR

[Service]
ReadWritePaths=$LOCAL_DIR
CONF
    done
else
    rm -f /etc/systemd/system/mediasync-backup.service.d/$LOCAL_DROPIN /etc/systemd/system/mediasync-backup-check.service.d/$LOCAL_DROPIN
fi
for unit in "${UNITS[@]}"; do
    install -m 644 "$HERE/$unit" "/etc/systemd/system/$unit"
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
        # restic ran as root here; the backups run as mediasync.
        [[ -z $LOCAL_DIR ]] || chown -R mediasync:mediasync "$LOCAL_DIR"
        echo
        echo "The backup's password is below. Save it in your password manager: without it the backup"
        echo "can't be read if this PC is lost. (It's also in $CONF/password.)"
        echo
        echo "    $(cat "$CONF/password")"
        echo
        ;;
    *)
        echo "Can't reach the backup yet (restic exit code $STATUS)."
        if [[ -z $LOCAL_DIR ]]; then
            echo "Copy $HTPASSWD to the backup machine and set it up with target-setup.sh, then run this again."
        fi
        exit 1
        ;;
esac

systemctl enable --now mediasync-backup.timer mediasync-backup-check.timer
systemctl --no-pager list-timers mediasync-backup.timer mediasync-backup-check.timer
echo
echo "To back up now:  sudo systemctl start --no-block mediasync-backup"
echo "Progress:        journalctl -fu mediasync-backup"
