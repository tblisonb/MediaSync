#!/usr/bin/env bash
# Builds and installs MediaSync on this PC in one step:
#
#   scripts/update.sh            # the server, then the desktop client
#   scripts/update.sh server     # or: desktop
#
# The server goes first, so the desktop client never runs against an older server. The server
# part asks for your password (it runs sudo deploy/install.sh); the desktop client needs none.
set -euo pipefail
cd "$(dirname "$0")/.."

parts=("$@")
[[ ${#parts[@]} -gt 0 ]] || parts=(server desktop)
for part in "${parts[@]}"; do
    case $part in
        server | desktop) ;;
        *) echo "usage: $0 [server] [desktop]" >&2; exit 2 ;;
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
