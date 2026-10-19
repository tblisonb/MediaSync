#!/usr/bin/env bash
# Tags one part of MediaSync and publishes it as a GitHub release, with its build attached:
#
#   scripts/release.sh app       # app-v<versionName>: the APK
#   scripts/release.sh desktop   # desktop-v<version>: a tarball with its own Java (bundledDist)
#   scripts/release.sh server    # server-v<version>: the server's distribution tarball
#
# Bump the version in that part's build.gradle.kts first (for the app, versionCode too). It
# releases what master has on GitHub, after that part's tests pass, and refuses a version that
# is already tagged. The notes list the commits that touched the part (or :shared) since its
# last release. Needs gh, logged in.
set -euo pipefail
cd "$(dirname "$0")/.."

part=${1:-}
case $part in
    app) version=$(sed -n 's/^ *versionName = "\(.*\)"$/\1/p' app/build.gradle.kts) ;;
    desktop | server) version=$(sed -n 's/^version = "\(.*\)"$/\1/p' "$part/build.gradle.kts") ;;
    *) echo "usage: $0 app|desktop|server" >&2; exit 2 ;;
esac
[[ -n $version ]] || { echo "no version in $part/build.gradle.kts" >&2; exit 1; }
tag="$part-v$version"

[[ $(git rev-parse --abbrev-ref HEAD) == master ]] || { echo "Release from master." >&2; exit 1; }
[[ -z $(git status --porcelain --untracked-files=no) ]] || { echo "Commit your changes first." >&2; exit 1; }
git fetch -q origin master
[[ $(git rev-parse HEAD) == $(git rev-parse origin/master) ]] || { echo "master differs from origin/master: push or pull first." >&2; exit 1; }
if git rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
    echo "$tag already exists: bump the version in $part/build.gradle.kts." >&2
    exit 1
fi

export JAVA_HOME=${JAVA_HOME:-$HOME/Android/jdk-21}
case $part in
    app)
        # Signed with this PC's debug key, like ./gradlew installDebug, so it installs over that.
        ./gradlew -q :shared:test :app:assembleDebug
        asset=app/build/outputs/apk/debug/mediasync-$version.apk
        cp app/build/outputs/apk/debug/app-debug.apk "$asset"
        ;;
    desktop)
        ./gradlew -q :desktop:test :desktop:bundledDist
        asset=$(ls desktop/build/distributions/mediasync-desktop-"$version"-linux-*.tar.gz)
        ;;
    server)
        ./gradlew -q :server:test :server:distTar
        asset=server/build/distributions/mediasync-server-$version.tar.gz
        ;;
esac

previous=$(git describe --tags --abbrev=0 --match "$part-v*" 2>/dev/null || true)
paths=("$part" shared)
[[ $part == server ]] && paths+=(deploy)
notes=$(git log --no-merges --format='- %s' ${previous:+"$previous..HEAD"} -- "${paths[@]}")
[[ -n $previous ]] || notes="First tagged release."$'\n\n'"$notes"

git tag -a "$tag" -m "MediaSync $part $version"
git push -q origin "$tag"
gh release create "$tag" "$asset" --verify-tag --title "MediaSync $part $version" --notes "$notes"
