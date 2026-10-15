#!/usr/bin/env bash
# Installs or updates the MediaSync desktop client for the current user (no sudo needed):
#   ./gradlew :desktop:installDist && desktop/build/install/mediasync-desktop/install.sh
# The first run pairs this PC with the server, previews what the first sync will do to your
# files, and starts syncing only once you say so.
set -euo pipefail

SRC="$(cd "$(dirname "$0")" && pwd)"
DATA="${XDG_DATA_HOME:-$HOME/.local/share}"
DEST="$DATA/mediasync-desktop"
UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
STATE_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/mediasync"
CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/mediasync"

if [[ ! -x "$SRC/runtime/bin/java" ]] && ! command -v java >/dev/null; then
    echo "This build has no bundled Java runtime; install Java 17+ (e.g. openjdk-21-jre-headless)." >&2
    exit 1
fi

# Program files
if [[ "$SRC" != "$DEST" ]]; then
    rm -rf "$DEST.new"
    cp -r "$SRC" "$DEST.new"
    rm -rf "$DEST"
    mv "$DEST.new" "$DEST"
fi
mkdir -p "$HOME/.local/bin" "$STATE_DIR" "$CACHE_DIR"
ln -sfn "$DEST/bin/mediasync-desktop" "$HOME/.local/bin/mediasync-desktop"

# Icon and launcher entry (also used by Plasma to group the notifications)
install -Dm644 "$DEST/share/mediasync.svg" "$DATA/icons/hicolor/scalable/apps/mediasync.svg"
install -d "$DATA/applications"
cat > "$DATA/applications/mediasync.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=MediaSync
GenericName=Media library sync
Comment=Open your MediaSync library in the browser
Exec=$DEST/bin/mediasync-desktop open
Icon=mediasync
Terminal=false
Categories=AudioVideo;Utility;
DESKTOP

# Service: runs with the Plasma session. `mediasync-desktop setup` adds a drop-in that makes the
# synced folders writable; everything else in the home folder stays read-only to it.
install -d "$UNIT_DIR"
cat > "$UNIT_DIR/mediasync-desktop.service" <<UNIT
[Unit]
Description=MediaSync desktop client
PartOf=graphical-session.target
After=graphical-session.target

[Service]
ExecStart=$DEST/bin/mediasync-desktop run
Restart=on-failure
RestartSec=30
RuntimeDirectory=mediasync

NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=read-only
ReadWritePaths=$STATE_DIR $CACHE_DIR
PrivateTmp=yes

[Install]
WantedBy=graphical-session.target
UNIT

systemctl --user daemon-reload
CLI="$DEST/bin/mediasync-desktop"
if "$CLI" status >/dev/null 2>&1; then
    systemctl --user restart mediasync-desktop.service
    echo "Updated and restarted. Logs: journalctl --user -u mediasync-desktop -f"
elif [[ -t 0 ]]; then
    "$CLI" setup
    echo
    "$CLI" preview
    echo
    read -r -p "Start syncing now? This renames and retags files as listed above. [y/N] " answer
    if [[ ${answer,,} == y* ]]; then
        systemctl --user enable --now mediasync-desktop.service
        echo "Syncing. Follow along with: journalctl --user -u mediasync-desktop -f"
    else
        echo "Not started. When you're ready: systemctl --user enable --now mediasync-desktop"
    fi
else
    echo "Installed. Next: $CLI setup, then systemctl --user enable --now mediasync-desktop"
fi
case ":$PATH:" in
    *":$HOME/.local/bin:"*) ;;
    *) echo "Note: ~/.local/bin isn't on your PATH, so use the full path: $CLI" ;;
esac
