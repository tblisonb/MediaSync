# MediaSync

Self-hosted version control for music, photos and videos: one repository on this PC, a desktop
client for PCs and an Android app, with tag edits versioned separately from the media they describe.
Design: [MediaSync design](https://claude.ai/code/artifact/6e772e9f-ebc6-411c-9355-dfe95e2b510c).

| Module | What |
| --- | --- |
| `shared/` | Plain Kotlin used everywhere: media hashing (`media/`), tag reading and writing with jaudiotagger (`tags/`), the sync API types (`api/`), tag policy, path rules, subscriptions and the three-way merge (`library/`) |
| `server/` | Ktor server: repository, sync API, web UI, and the `setup`, `fsck`, `prune` and `snapshot` commands |
| `spike/` | Milestone 0 command-line check, run on the PC |
| `app/` | Android app; for now just the Milestone 0 check screen |
| `deploy/` | systemd unit, install script and an end-to-end smoke test |

`desktop/` arrives with milestone M2.

## Toolchain

The same as Remind: Temurin JDK 21 in `~/Android/jdk-21` and the Android SDK in `~/Android/Sdk`
(`local.properties` points Gradle at it). Gradle needs a full JDK, and this PC's system Java
(`/usr/lib/jvm/java-21-openjdk-amd64`) is only a runtime, so `export JAVA_HOME=$HOME/Android/jdk-21`
first, or install the JDK package once: `sudo apt install openjdk-21-jdk-headless`.

## Milestone 0: can tags be rewritten without touching the media?

`RoundTripCheck` copies a file, rewrites its tags several times through `TagWriter` (test values with
non-ASCII text, two artists, a cover; then the original tags again) and checks the media hash never
changes and every field reads back. Originals are only ever read.

```sh
./gradlew :shared:test                                   # hasher tests, plus the fixture round trip
./gradlew :spike:run --args="path/to/music [max files]"  # the check over a folder on this PC
scripts/make-fixtures.sh                                 # regenerate test fixtures (needs ffmpeg)
```

On the phone:

```sh
./gradlew installDebug             # then open MediaSync, allow all-files access, Run check
adb pull /sdcard/Android/data/com.tlisonbee.mediasync/files/m0-report.txt
./gradlew connectedDebugAndroidTest  # the fixture round trip on the device
```

What the media hash covers per format is in each parser in `shared/.../media/`: MP3 frames from the
first real frame to the trailing tags, FLAC STREAMINFO plus frames, MP4 `mdat` contents, Ogg packets
minus the comment packet. Anything unrecognised is hashed whole and is never written to.

## Milestone 1: the server

```sh
./gradlew :server:test                                # sync API, web UI and maintenance tests
./gradlew :server:installDist && sudo deploy/install.sh   # install or update the service (needs a JDK; see Toolchain)
deploy/smoke-test.sh https://<server>.<tailnet>.ts.net:8443 ABCD-EFGH   # check it over the tailnet
```

The first `install.sh` creates the `mediasync` system user and the repository, by default in
`/var/lib/mediasync` (`--repo DIR` picks another folder, which can't be in a home folder). It asks for
the web UI's admin password and the music tag policy, starts the service on `127.0.0.1:8766`, and
publishes it with `tailscale serve` at `https://<this machine>.ts.net:8443` (Remind keeps 443).
Later runs only replace the program in `/opt/mediasync`.

The web UI is at that address: status, library browser, history with revert and per-field restore,
the resolve queue, devices (pairing by QR code or code, subscriptions, revoking) and the tag policy.
Get a pairing code for the smoke test from Devices > Pair a device.

Run the maintenance commands as the service's user:

```sh
sudo -u mediasync /opt/mediasync/bin/mediasync-server fsck --full --repo /var/lib/mediasync
sudo -u mediasync /opt/mediasync/bin/mediasync-server prune --older-than 1y --repo /var/lib/mediasync
sudo -u mediasync /opt/mediasync/bin/mediasync-server snapshot --repo /var/lib/mediasync   # before a backup
sudo -u mediasync /opt/mediasync/bin/mediasync-server pair-code --repo /var/lib/mediasync  # without the web UI
```

Logs: `journalctl -u mediasync`. The API is under `/api/v1` (types in `shared/.../api/Api.kt`);
`server/src/test/.../TestDevice.kt` is a scripted client showing how a device drives it.
