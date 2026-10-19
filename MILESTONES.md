# Milestones

How MediaSync was built, step by step, and how to run each milestone's checks. For setting it up
and using it, see the [README](README.md). The design behind it is in the author's
[design notes](https://claude.ai/code/artifact/6e772e9f-ebc6-411c-9355-dfe95e2b510c), which may be
private.

## Milestone 0: can tags be rewritten without touching the media?

The whole design rests on tags being separable from the audio. A retag must leave the media
hash unchanged, so that it syncs as a small tag change rather than a new file.

`RoundTripCheck` copies a file and rewrites its tags several times through `TagWriter`. It first
writes test values (non-ASCII text, two artists, a cover), then the original tags again. It checks
that the media hash never changes and that every field reads back. Originals are only ever read.

```sh
./gradlew :shared:test                                   # hasher tests, plus the fixture round trip
./gradlew :spike:run --args="path/to/music [max files]"  # the check over a real music folder
scripts/make-fixtures.sh                                 # regenerate the test fixtures (needs ffmpeg)
```

On the phone:

```sh
./gradlew :app:installDebug          # then open MediaSync, allow All files access, and run the check
adb pull /sdcard/Android/data/com.tlisonbee.mediasync/files/m0-report.txt
./gradlew connectedDebugAndroidTest  # the fixture round trip on the device
```

What the media hash covers is defined per format, in each parser in `shared/.../media/`:

- **MP3:** the frames, from the first real frame to the trailing tags.
- **FLAC:** STREAMINFO plus the frames.
- **MP4:** the contents of `mdat`.
- **Ogg:** the packets, minus the comment packet.

Anything unrecognised is hashed whole, and MediaSync never writes to it.

## Milestone 1: the server

The repository holds blobs by hash, assets with their versions (path, tags and the blobs of their
renditions), and revisions. It comes with the sync API devices use, and a web UI with status, the
library browser, history with revert and per-field restore, the resolve queue, devices (pairing
by QR code or by code, subscriptions, revoking) and the tag policy. The maintenance commands are
`fsck`, `prune`, `snapshot` and `pair-code`. It runs as a sandboxed system service, published on the
tailnet by `tailscale serve`.

```sh
./gradlew :server:test                     # sync API, web UI and maintenance tests
scripts/update.sh server                   # build, then install or update the service
deploy/smoke-test.sh https://<server>.<tailnet>.ts.net:8443 ABCD-EFGH   # check it over the tailnet
```

The pairing code for the smoke test comes from Devices > Pair a device. The smoke test pairs a
device named "Smoke test", uploads one small random file and reads it back. It never pushes, so
the library and its history are untouched. Revoke the device afterwards.

## Milestone 2: the desktop client

A systemd user service keeps `~/Music` in sync through the sync engine in `shared/.../sync/`.

- **Files:** names follow the tags, tags outside the policy are removed, and files MediaSync can't
  tag sync as plain files.
- **Safety:** large deletions are held back, and a missing folder is never read as a deletion.
- **Desktop:** Plasma notifications, and a CLI (`status`, `sync`, `log`, `diff`, `revert`, `open`).
- **First sync:** the installer pairs the PC and previews the first sync before it starts.

```sh
./gradlew :desktop:test          # two clients and a real server, end to end
scripts/update.sh desktop        # build, then install or update it for this user
./gradlew :desktop:bundledDist   # desktop/build/distributions/*.tar.gz, with its own Java, for other PCs
```

## After milestone 2

| When | What |
| --- | --- |
| 2026-10-15 | **The Android app**, with the sync engine moved into `:shared` so both clients run the same code. It pairs by QR code and previews the first sync of `Music/`. |
| 2026-10-15 | **Backups**: nightly restic backups of the repository to a Raspberry Pi, append-only, with a weekly check (`deploy/backup/`). |
| 2026-10-15 | **Cleanup** in the web UI: name variants, an album tag editor, and stray files moved to their album. |
| 2026-10-15 | **Ask before syncing**: devices check, notify you, and apply only what you approve. This includes the review page on the desktop and the review on the phone's main screen. |
| 2026-10-15 | **Videos**: movie and episode details, filing, subtitles and posters, and per-device picks. Clients can sync more than one folder. |
| 2026-10-16 | Devices can edit fields and kinds. The review page shows a job's progress. |
| 2026-10-16 | Cleanup deletes leftover files and tidies album art. |
| 2026-10-17 | Devices delete clutter when the server asks. MP3s with only an ID3v1 tag are read. WMA tracks can be converted to MP3. |
| 2026-10-17 | **Releases**: each part is versioned and released from GitHub with `scripts/release.sh`. |
| 2026-10-18 | A **Plasma tray icon** for the desktop client, and `scripts/update.sh` for building and installing in one step. |
| 2026-10-18 | The Android app shows what it's doing from the moment a button is pressed. |
| 2026-10-18 | **Photos**: dated from the camera or their name, filed by year, and dated in the web UI with thumbnails. |
| 2026-10-18 | **Setup for everyone**: the server tarball installs itself and checks Tailscale, the build no longer needs the Android SDK, and the README covers setup from scratch. |
