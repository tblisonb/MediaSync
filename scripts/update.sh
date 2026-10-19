#!/usr/bin/env bash
# Usage: scripts/update.sh [server] [desktop]
#
# Builds MediaSync from this source and installs it on this PC, in one step. It installs either
# part the first time, and updates it after that. With no arguments it does both: the server first,
# so the desktop client never runs against an older server.
#
#   server       Build the server, then install it with sudo deploy/install.sh (asks for your
#                password).
#   desktop      Build the desktop client, then install it for you (no sudo). The first time, it
#                pairs this PC with the server.
#   -h, --help   Show this help.
#
# Needs: a JDK 17 or newer (sudo apt install openjdk-21-jdk-headless), or JAVA_HOME set to one.
set -euo pipefail
# -h or --help prints the comment at the top of this file.
for arg in "$@"; do
    [[ $arg != -h && $arg != --help ]] || { awk 'NR > 1 && !/^#/ { exit } NR > 1 { sub(/^# ?/, ""); print }' "$0"; exit 0; }
done
cd "$(dirname "$0")/.."

parts=("$@")
[[ ${#parts[@]} -gt 0 ]] || parts=(server desktop)
for part in "${parts[@]}"; do
    case $part in
        server | desktop) ;;
        *) echo "usage: $0 [server] [desktop] (see --help)" >&2; exit 2 ;;
    esac
done
wants() { [[ " ${parts[*]} " == *" $1 "* ]]; }

[[ -n ${JAVA_HOME:-} || ! -d $HOME/Android/jdk-21 ]] || export JAVA_HOME=$HOME/Android/jdk-21
# Building needs a full JDK (17 or newer), not just a Java runtime.
if [[ ! -x ${JAVA_HOME:-/nonexistent}/bin/javac ]] && ! command -v javac >/dev/null; then
    echo "Building needs a JDK: sudo apt install openjdk-21-jdk-headless (or set JAVA_HOME to one)." >&2
    exit 1
fi
tasks=()
wants server && tasks+=(:server:installDist)
wants desktop && tasks+=(:desktop:installDist)
./gradlew -q "${tasks[@]}"

if wants server; then
    echo "Installing the server…"
    sudo deploy/install.sh
fi
if wants desktop; then
    echo "Installing the desktop client…"
    desktop/build/install/mediasync-desktop/install.sh
fi
