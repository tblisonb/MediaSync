#!/usr/bin/env bash
# Usage: scripts/release.sh app|desktop|server
#        scripts/release.sh all [INTRO.md]
#
# Tags one part of MediaSync and publishes it as a GitHub release, with its build attached:
#   app       app-v<versionName>: the APK, a release build signed with the release key
#   desktop   desktop-v<version>: a tarball with its own Java (bundledDist)
#   server    server-v<version>: the server tarball, which installs itself
#
# all releases the three together as v<version>, one release with all three builds attached. The
# parts must share that version; each also gets its own tag, so a later release of one part lists
# what changed since this. INTRO.md, if given, opens the release notes.
#
# First bump the version in that part's build.gradle.kts (for the app, versionCode too), commit and
# push. It releases what master has on GitHub, after that part's tests pass, and refuses a version
# that's already tagged. The release notes list the commits that touched the part (or :shared)
# since its last release.
#
# Needs: gh, logged in. For the app, the release key in ~/.android (see make-release-key.sh).
set -euo pipefail
# -h or --help prints the comment at the top of this file.
for arg in "$@"; do
    [[ $arg != -h && $arg != --help ]] || { awk 'NR > 1 && !/^#/ { exit } NR > 1 { sub(/^# ?/, ""); print }' "$0"; exit 0; }
done
cd "$(dirname "$0")/.."

version_of() {
    case $1 in
        app) sed -n 's/^ *versionName = "\(.*\)"$/\1/p' app/build.gradle.kts ;;
        desktop | server) sed -n 's/^version = "\(.*\)"$/\1/p' "$1/build.gradle.kts" ;;
    esac
}

untagged() {
    if git rev-parse -q --verify "refs/tags/$1" >/dev/null; then
        echo "$1 already exists: bump the version in $2/build.gradle.kts." >&2
        exit 1
    fi
}

# Tests and builds a part; prints the file to attach.
build() {
    local part=$1 version=$2
    case $part in
        app)
            # A release build, signed with the release key (scripts/make-release-key.sh), so every
            # release installs over the one before it.
            [[ -f $HOME/.android/mediasync-release.properties ]] ||
                { echo "No release key: restore ~/.android/mediasync-release.{jks,properties}, or make one with scripts/make-release-key.sh." >&2; exit 1; }
            ./gradlew -q :shared:test :app:assembleRelease >&2
            [[ -f app/build/outputs/apk/release/app-release.apk ]] || { echo "The release build isn't signed." >&2; exit 1; }
            cp app/build/outputs/apk/release/app-release.apk "app/build/outputs/apk/release/mediasync-$version.apk"
            echo "app/build/outputs/apk/release/mediasync-$version.apk"
            ;;
        desktop)
            ./gradlew -q :desktop:test :desktop:bundledDist >&2
            ls desktop/build/distributions/mediasync-desktop-"$version"-linux-*.tar.gz
            ;;
        server)
            ./gradlew -q :server:test :server:distTar >&2
            echo "server/build/distributions/mediasync-server-$version.tar.gz"
            ;;
    esac
}

# The commits that touched a part (or :shared) since its last release.
changes() {
    local part=$1 previous paths=("$1" shared)
    previous=$(git describe --tags --abbrev=0 --match "$part-v*" 2>/dev/null || true)
    [[ $part == server ]] && paths+=(deploy)
    [[ -n $previous ]] || echo "First tagged release."$'\n'
    git log --no-merges --format='- %s' ${previous:+"$previous..HEAD"} -- "${paths[@]}"
}

part=${1:-}
case $part in
    app | desktop | server | all) ;;
    *) echo "usage: $0 app|desktop|server|all (see --help)" >&2; exit 2 ;;
esac

[[ $(git rev-parse --abbrev-ref HEAD) == master ]] || { echo "Release from master." >&2; exit 1; }
[[ -z $(git status --porcelain --untracked-files=no) ]] || { echo "Commit your changes first." >&2; exit 1; }
git fetch -q origin master
[[ $(git rev-parse HEAD) == $(git rev-parse origin/master) ]] || { echo "master differs from origin/master: push or pull first." >&2; exit 1; }
export JAVA_HOME=${JAVA_HOME:-$HOME/Android/jdk-21}

if [[ $part != all ]]; then
    version=$(version_of "$part")
    [[ -n $version ]] || { echo "no version in $part/build.gradle.kts" >&2; exit 1; }
    tag="$part-v$version"
    untagged "$tag" "$part"
    asset=$(build "$part" "$version")
    notes=$(changes "$part")
    git tag -a "$tag" -m "MediaSync $part $version"
    git push -q origin "$tag"
    gh release create "$tag" "$asset" --verify-tag --title "MediaSync $part $version" --notes "$notes"
    exit 0
fi

intro=${2:-}
[[ -z $intro || -f $intro ]] || { echo "no file $intro" >&2; exit 1; }
version=$(version_of server)
for p in app desktop server; do
    [[ $(version_of "$p") == "$version" ]] || { echo "$p is at $(version_of "$p"), not $version: give all three the same version." >&2; exit 1; }
    untagged "$p-v$version" "$p"
done
untagged "v$version" server

assets=()
for p in server desktop app; do assets+=("$(build "$p" "$version")"); done

notes=${intro:+$(cat "$intro")$'\n\n'}
for p in server desktop app; do
    title=$p
    [[ $p == app ]] && title="Android app"
    notes+="## ${title^}"$'\n\n'"$(changes "$p")"$'\n\n'
done

for p in app desktop server; do git tag -a "$p-v$version" -m "MediaSync $p $version"; done
git tag -a "v$version" -m "MediaSync $version"
git push -q origin "v$version" "app-v$version" "desktop-v$version" "server-v$version"
gh release create "v$version" "${assets[@]}" --verify-tag --latest --title "MediaSync $version" --notes "$notes"
