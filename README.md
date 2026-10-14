# MediaSync

Self-hosted version control for music, photos and videos: one repository on this PC, a desktop
client for PCs and an Android app, with tag edits versioned separately from the media they describe.
Design: [MediaSync design](https://claude.ai/code/artifact/6e772e9f-ebc6-411c-9355-dfe95e2b510c).

| Module | What |
| --- | --- |
| `shared/` | Plain Kotlin used everywhere: media hashing (`media/`), tag reading and writing with jaudiotagger (`tags/`) |
| `spike/` | Milestone 0 command-line check, run on the PC |
| `app/` | Android app; for now just the Milestone 0 check screen |

`server/` and `desktop/` arrive with milestones M1 and M2.

## Toolchain

The same as Remind: Temurin JDK 21 in `~/Android/jdk-21` and the Android SDK in `~/Android/Sdk`
(`local.properties` points Gradle at it). `export JAVA_HOME=$HOME/Android/jdk-21` if your shell
doesn't already.

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
