#!/usr/bin/env bash
# Usage: scripts/make-release-key.sh
#
# Makes the key that signs MediaSync's Android releases. Run it once, ever: it refuses to replace
# a key that exists. It writes two files, both readable only by you:
#   ~/.android/mediasync-release.jks          the key
#   ~/.android/mediasync-release.properties   its password, for Gradle (random, never printed)
#
# Back up both files, as KEYS.md describes. Every update has to be signed with this same key:
# without it, phones can only take a new release by uninstalling the app.
#
# Needs: a JDK (keytool) and openssl.
set -euo pipefail
# -h or --help prints the comment at the top of this file.
for arg in "$@"; do
    [[ $arg != -h && $arg != --help ]] || { awk 'NR > 1 && !/^#/ { exit } NR > 1 { sub(/^# ?/, ""); print }' "$0"; exit 0; }
done

DIR=$HOME/.android
STORE=$DIR/mediasync-release.jks
PROPS=$DIR/mediasync-release.properties
ALIAS=mediasync

[[ ! -e $STORE && ! -e $PROPS ]] || { echo "$STORE or $PROPS already exists; not replacing the release key." >&2; exit 1; }
[[ -n ${JAVA_HOME:-} || ! -d $HOME/Android/jdk-21 ]] || export JAVA_HOME=$HOME/Android/jdk-21
KEYTOOL=${JAVA_HOME:+$JAVA_HOME/bin/}keytool
command -v "$KEYTOOL" >/dev/null || { echo "keytool comes with a JDK: sudo apt install openjdk-21-jdk-headless" >&2; exit 1; }

mkdir -p "$DIR"
umask 077
# PKCS12 keeps one password for the store and the key.
MEDIASYNC_KEY_PASSWORD=$(openssl rand -base64 30 | tr -d '/+=' | cut -c1-32)
export MEDIASYNC_KEY_PASSWORD
"$KEYTOOL" -genkeypair -keystore "$STORE" -storetype PKCS12 -alias "$ALIAS" \
    -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=MediaSync" \
    -storepass:env MEDIASYNC_KEY_PASSWORD -keypass:env MEDIASYNC_KEY_PASSWORD 2>/dev/null
cat > "$PROPS" <<PROPS
# MediaSync's Android release key (see scripts/make-release-key.sh). Keep this file private.
storeFile=$STORE
storePassword=$MEDIASYNC_KEY_PASSWORD
keyAlias=$ALIAS
keyPassword=$MEDIASYNC_KEY_PASSWORD
PROPS

echo "Made the release key:"
echo "  $STORE"
echo "  $PROPS (its password)"
"$KEYTOOL" -list -v -keystore "$STORE" -storepass:env MEDIASYNC_KEY_PASSWORD | grep -m1 'SHA256:' | sed 's/^\s*/  Certificate /'
echo
echo "Back up the key and its password now: KEYS.md says what to save and how."
