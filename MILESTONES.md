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
| 2026-10-15 | **Backups**: nightly restic backups of the repository to a Raspberry Pi, append-only, with a weekly check (`deploy/backup/`). On 2026-10-18 this was generalized: backups go to any Linux machine on the tailnet at any path, or to a folder on the server. |
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
| 2026-10-18 | **Release builds of the app**, signed with a dedicated release key, from app 0.5.0 on. The server tarball also carries the backup scripts. |
| 2026-10-18 | **One format**: Cleanup converts tracks of any format (not only WMA) to MP3, and can keep converting new ones as they arrive (server 0.3.0). |
| 2026-10-19 | While a conversion to MP3 runs, the Cleanup page shows it at the top with a progress bar and the time left, kept current as it goes (server 0.3.1). |
| 2026-10-19 | **Games**: disc images and ROMs identified by checksum (No-Intro and Redump) or a PlayStation disc's serial, given their details from LaunchBox, filed by platform and title, and kept on devices by game or whole platform. A separate service downloads the databases; the server stays on loopback (server 0.4.0, desktop 0.2.0, app 0.6.0). |
| 2026-10-19 | The review groups a device's own changes by folder too, so a new folder's files can be accepted apart from the rest. A console BIOS in a `BIOS` folder isn't filed as a game (server 0.4.1, desktop 0.2.1, app 0.6.1). |
| 2026-10-19 | Clearer wording: a second copy of a photo or video is called that, not "same audio", and the Games page tells the game databases' size apart from the library's (server 0.4.2, desktop 0.2.2, app 0.6.2). |
| 2026-10-19 | PS1 discs: a `.cue` joins only the disc it's named after (not any game whose title begins its name), and an ECM-packed image is known by its serial. Doubtful identifications are made again on update (server 0.4.3). |
| 2026-10-21 | `mediasync-desktop move-folder` moves a synced folder, such as onto another drive, without uploading it again; `mediasync-desktop version` names its version and the server's (desktop 0.2.3, server 0.4.4). |
| 2026-10-21 | A synced folder that isn't there (an unmounted drive, a folder moved away) no longer stops the desktop service from starting; it's skipped, and `status` says so (desktop 0.2.4). |
| 2026-10-23 | `mediasync-desktop remove-folder` stops syncing a folder on a PC, leaving its files where they are (desktop 0.2.5). |
| 2026-10-23 | Pictures of every sort live in `images/`: the server moves `photos/` to `images/photos/` (folders kept by hand to `images/other/`) and renames the devices' folders, which follow it. The app syncs any `images/` folder with any phone folder, such as `DCIM` sent to the library only (server 0.5.0, desktop 0.3.0, app 0.7.0). |
| 2026-10-23 | A send-only folder can keep in the library what's deleted on the device, as a phone's camera folder does by default; server 0.5.1 puts back in `images/photos/` the photos 0.5.0 sent to the folder they were first imported from (server 0.5.1, desktop 0.3.1, app 0.7.0). |
| 2026-10-23 | The app's folder fields (Music when pairing, a Pictures folder) have **Browse…**, which opens the phone's folder picker; a folder on an SD card works too (app 0.7.1). |
| 2026-10-27 | The resolve queue groups the entries the server can judge (identical copies, the same track in another format, tags written differently, ID3 genre numbers, pictures sharing a name), each group settled with one button in one revision. Imports differing only in case or spacing, and pictures sharing a name, no longer wait there (server 0.6.0). |
| 2026-10-27 | The same track in two formats can keep only its MP3, in Resolve, for a whole group or one entry; keeping both is still offered (server 0.6.1). |
| 2026-10-27 | Resolve links a track with a copy an older client sent as a plain file (an untagged WMA, say), and drops empty copies, keeping the tagged track whichever side it was on; those pairs join "Same track in another format" (server 0.6.2). |
| 2026-10-27 | The same picture in two album folders is two files, so the album art tidy-up sees both; a copy a device left alone because an older server took it for the other folder's no longer blocks a rename onto its name. Years, counts and sizes in the web UI's tables stay on one line (server 0.6.3, desktop 0.3.2, app 0.7.2). |
| 2026-10-28 | A new home page: what needs a look, cards for music, photos, videos and games with what was added lately, devices with how far behind each is and what it syncs, where the space goes, and recent activity by day, a device's pushes in a row read as one line ("desktop added 412 tracks"). Photos and Videos join the menu (server 0.7.0). |
| 2026-10-28 | Photos opens on the year folders, newest first with Undated last, each with its count and newest photo; it had shown "no photos" once every photo was filed by year (server 0.7.1). |
| 2026-10-28 | Photos becomes Images, for all of images/ (screenshots and other folders as galleries), on the home page too. An image viewer shows each picture as large as the window allows, steps through its folder with ← and →, opens the full resolution with a click, and plays phone clips; the Library opens images in it (server 0.8.0). |
| 2026-10-28 | MediaSync 1.0.0: the server, desktop client and Android app released together at one version, with `scripts/release.sh all` (server 1.0.0, desktop 1.0.0, app 1.0.0). |
