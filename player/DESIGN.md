# Segue (the MediaSync player): design notes

Segue (named 2026-11-04) is an Android music player for the library MediaSync keeps on the phone, built in this repository as
its own app (`:player`, `com.tlisonbee.mediasync.player`). Local playback comes first; TIDAL comes
after it. These notes hold the decisions, what was found, and the plan; the [README](../README.md)
will cover using it once there's something to use.

## Decisions

- **Its own app, in this repository.** A music player has a different life from a sync app: a
  playback service, a media notification, and being opened many times a day. Keeping it a separate
  APK keeps MediaSync small, while living here lets it reuse `:shared` (jaudiotagger tag reading,
  the API client, server versions) and `scripts/release.sh`.
- **Signed with MediaSync's release key.** Both apps then share a signature, so MediaSync can offer
  the player things no other app gets (a `signature` permission): "these files changed" after a
  sync, and the pairing for streaming from the server. The player never pairs on its own.
- **Kotlin, Jetpack Compose and Media3** (ExoPlayer with a `MediaSessionService`), like the app.
  Media3 gives gapless playback, the media notification, lock screen and Bluetooth controls, and
  audio focus.
- **Plain files with All files access**, as MediaSync does: it reads `Music/` directly (much faster
  than the Storage Access Framework for thousands of files) and can read and write the playlists in
  it, which `READ_MEDIA_AUDIO` alone doesn't allow. Fine for a sideloaded app.
- **Its own index**, in SQLite (`SQLiteOpenHelper`, like the app's `AppIndex`): one row per track
  with its tags, keyed by path and refreshed by size and modification time. The library is already
  clean (MediaSync files every track by its tags), so the player trusts the tags and needs no
  editor or fix-ups.
- **Tapping a track plays it in its context:** the album, playlist, folder or search result plays
  from that track, and the queue is separate (**Play next**, **Add to queue**). Saved playlists
  never change by playing them.
- **Versioned and released on its own,** as `player-v…`, like the other parts. Once it streams from
  the server it gets a `MIN_SERVER` like the other apps.
- **Not for now:** Android Auto (Media3's `MediaLibraryService` would make it cheap later), TIDAL
  (after local playback; see below), tag editing, and a Play Store build.

## What was found

**The library on the phone** (checked from this PC's copy, `/mnt/other/media/music`, 2026-11-02):
9,025 MP3s and no other audio, plus covers and a few PDFs. In a sample of 300: all but one are
44.1 kHz, two thirds are 320 kbps, and none has ReplayGain tags.

**Gapless MP3 depends on the encoder's header.** An MP3 starts and ends with padding the encoder
added, and only a LAME (or FFmpeg "Lavc") header in the first frame says how much. With it,
ExoPlayer trims the padding and albums play without gaps; without it, no player can. In the sample:
55% have the header with the delay and padding (43% LAME, 12% FFmpeg), 41% have no header at all,
and the rest a header without them. So gapless works for roughly half the library; the rest has a
short gap (a few tens of milliseconds) between tracks, as it would in any player. Re-encoding can't fix that
without losing quality, so the player accepts it.

**ReplayGain comes from the server** (decided 2026-11-02). MediaSync's tag policy only knows
title, artists, album, album artist, date, genre, track and disc (`TagField.MUSIC`), and strips
everything else from the files, so ReplayGain tags can't reach the phone today. The plan: the
server measures each track and album with the ffmpeg it already has (EBU R128 loudness), and
MediaSync writes standard `REPLAYGAIN_TRACK_GAIN/PEAK` and `REPLAYGAIN_ALBUM_GAIN/PEAK` tags into
every device's files as part of the tag policy. fooyin on the desktop reads the same tags, so both
players agree. It needs the policy and `AudioTags` to learn the four fields (`TXXX` frames in MP3),
a one-off background scan of the library on the server (a few hours), and a small tag-only change
on every track, reviewed and synced like any other. The alternatives were measuring on the phone
(hours of decoding there, nothing shared with the desktop) or no levelling.

**TIDAL** (for later): TIDAL's Android Player SDK gives third-party apps only 30-second previews
(`FULL_REQUIRES_HIGHER_ACCESS_TIER`), so the player would use the fooyin-tidal plugin's split: the
official API for search, favorites and playlists, and the private v1 API's DASH streams, which
ExoPlayer plays as they are. The playback login the plugin uses is TIDAL's own Android client.

## Plan

| Phase | What |
| --- | --- |
| **0. Checks** | The module and a test app. On the phone: ExoPlayer plays consecutive MP3s with the LAME/Lavc header without a gap and without restarting its audio output; a per-track gain applies exactly at each track boundary (what ReplayGain needs); how fast `:shared`'s tag reading scans `Music/`. See "Phase 0 checks" below. |
| **1. Local player** (built) | The index and its scan; browsing by artist, album, folder and playlist, and search; playback in context with a queue, shuffle and repeat; the media notification, lock screen and Bluetooth; covers from the files; picking up where it left off. ReplayGain tags, once MediaSync carries them. See "Phase 1" below. |
| **2. MediaSync** | The server's ReplayGain (above). Rescan the changed files right after a sync. Playlists as `.m3u8` files in a synced `Music/Playlists/` folder, shared with fooyin on the desktop. Streaming tracks the phone doesn't hold from the server over Tailscale. |
| **3. TIDAL** (logins, browsing, playback built) | Port the plugin's logins, catalog, cache and edits to Kotlin; TIDAL tracks mixed with local ones in the queue. |
| **4. Polish** | Widget, equalizer, sleep timer, releases. Android Auto when there's a use for it. |

### Phase 0 checks

Instrumented tests, run on the phone with `./gradlew :player:connectedDebugAndroidTest` (they touch
only the player's own package) and read with `adb logcat -s PlayerPhase0`:

- `GaplessAndroidTest` plays a 220 Hz tone cut in two at a sample that isn't on an MP3 frame
  boundary, each half encoded on its own (`player/make-fixtures.sh`), records what reaches the audio
  output, and checks that all 264,600 frames come out with no step at the cut and one audio track
  throughout. A control pair without the encoder header must show the gap, so the check is known
  to see one. A third run tags the second half -6.02 dB and checks the level halves within a
  period of the cut.
- `ScanSpeedAndroidTest` lists `Music/` and reads the tags of 400 random files, to estimate the
  index's first scan.

Volume levelling uses `GainProcessor` in ExoPlayer's audio sink. Since Media3 1.11 the sink tells
its processors, each time it flushes them, which playlist item the following audio belongs to
(`AudioProcessor.StreamMetadata`), so the gain is looked up per item there, with no custom renderer.

**Results** (motorola edge 40 pro, Android 15, 2026-11-02):

- **Android's own MP3 decoder isn't gapless.** With it, each track lost about 128 samples at the
  start and its last few hundred samples faded into silence, so the cut clicked (a step of 12,881
  against 515 elsewhere). ExoPlayer's trimming was right; the decoder's output wasn't.
- **Media3's FFmpeg decoder is.** With it preferred (`PlayerRenderers`), the output matched the tone
  sample for sample (at most 9 off out of 32,767) across the cut, with one audio track throughout
  and no underruns. The only extra frames (228) are silence after the last track. Google publishes
  that module only as source; the player uses Jellyfin's build (`org.jellyfin.media3:media3-ffmpeg-decoder`
  1.9.0+1, about 1.4 MB of native code per ABI), which loads and plays with Media3 1.11.1. If it ever
  stops matching Media3, build the module from Media3's source with the NDK instead.
- **Real tracks:** Boston's "My Destination" into "A New World" (a segue, LAME-encoded) played on the
  phone matched ffmpeg's decoding on the PC to within 1 (out of 32,767) everywhere, and exactly
  around the track change. `realFilesFromArguments` does this for any files.
- **The gain switches at the track change:** the level halved within one period of the cut, and
  the gain processor saw the second item when the sink flushed for it.
- **Scanning:** 9,080 audio files in `Music/`, listed in 7.7 s; reading tags with `:shared`'s
  `AudioTags` took 5.4 ms a file (none failed, 48% with a cover), so a first scan takes about a
  minute. Later scans only read files whose size or date changed.

**Licence:** Jellyfin's FFmpeg build is GPL-3.0, so the player's APK is a GPL-3.0 work when it's
distributed. The repository is GPL-3.0-or-later (decided 2026-11-03), which fits.

### Phase 1: the local player

What's built, and how:

- **The index** (`library/`): `Library` walks `Music/` (one `stat` per file, through
  `Files.walkFileTree`), reads the tags of new and changed files with `:shared`'s `AudioTags`, and
  keeps them in `library.db`. All tracks are held in memory (`LibraryData`), from which albums
  (by album artist and album), artists (with the tracks they have on other artists' albums),
  folders and search are worked out. The scan runs each time the app opens (at most every five
  minutes); a full scan from Settings reads every file again.
- **Covers** come from the file's embedded picture, else a `cover.jpg`/`folder.jpg` beside it, and
  are stored once per picture at 720 px in the app's own storage; lists, Now Playing and the
  notification read those copies.
- **ReplayGain** tags are read now (`AudioTags.Inspection.replayGain`: `TXXX` frames, Vorbis
  comments, iTunes fields) and applied by `GainProcessor` in Track or Album mode (Settings; Track by
  default). Until the server writes them (phase 2), tracks play as they are.
- **Playback** is a `MediaSessionService` (`playback/`), which gives the media notification, the
  lock screen, Bluetooth and headset buttons, and audio focus. Screens talk to it through a
  `MediaController` and send only paths; the service fills in each item from the library.
- **The queue.** Tapping a track plays its list (album, artist, folder, playlist, search results)
  from it. **Play next** inserts after the current track, **Add to queue** after whatever is
  already queued; queued tracks are marked, play before the rest of the list, and keep doing so
  when shuffle is turned on or off. Shuffle uses `QueueShuffleOrder`, so a new shuffle always
  starts from the current track and nothing is skipped.
- **Picking up where it left off:** the queue, the track, the position, shuffle and repeat are
  saved (`files/queue/`), put back when the service starts, and used for a headset's play button
  after the app was closed (`onPlaybackResumption`).
- **Playlists** are `.m3u`/`.m3u8` files anywhere in `Music/`. The player makes new ones in
  `Music/Playlists/`, writing paths relative to the playlist with `#EXTINF` "Artist - Title"
  lines, which is also how it finds an entry whose file MediaSync has since renamed. MediaSync
  already syncs them as plain files, so a playlist made on the phone reaches the server and the
  desktop (where fooyin reads them) on the next sync, and shows up in its review first.

**Checked on the phone** (2026-11-04, `PlaybackAndroidTest`, which reads only the music and plays
muted): the first scan of 9,031 tracks took 127 s, covers included (983 albums, 96% of tracks with a
cover); a scan with nothing new takes 1.6 s, and building the library in memory 0.3 s. Played a
454-track album through the service from its third track, turned shuffle on (all 453 others
still to come), added one with Play next and two with Add to queue (they played next, in that
order, ahead of the shuffled rest), turned shuffle off (the queued three, then the album from
where it was); the media notification was up, and the saved queue matched. By hand: browsing,
Now Playing, the queue and search, and the queue coming back at the same position after the app
was reinstalled.

To run it: `./gradlew :player:installDebug :player:installDebugAndroidTest`, then
`adb shell am instrument -w -e class com.tlisonbee.mediasync.player.PlaybackAndroidTest com.tlisonbee.mediasync.player.test/androidx.test.runner.AndroidJUnitRunner`.
The test lets the player in with All files access and leaves it on (taking it back kills the
process before the result is reported): `adb shell appops set com.tlisonbee.mediasync.player MANAGE_EXTERNAL_STORAGE default`.

### Phase 2: loudness from the server

Built (server 1.5.0, apps 1.3.0). `Loudness.kt` measures each track's media with ffmpeg's EBU R128
meter (about 1 s a track, a few at a time: 20 minutes or so for this library) and keeps a
histogram of its 400 ms blocks' loudness in the `loudness` table, so an album's gated loudness is
worked out from its tracks' histograms without decoding anything again. The four values are tags
(`TagField.REPLAYGAIN`, kept by every music policy as `TagPolicy.MEASURED`), written in revisions
of up to 1,000 tracks ("Measured the loudness of … tracks"), and again when a track joins or
leaves an album. It runs at startup and after every push of music, and can be turned off in
Settings. Devices write them like any other tag (`TXXX` frames in MP3); older apps ignore them and
never send their removal. Most of the library's MP3s have enough tag padding (95% of a sample have
200 bytes or more) for the tags to be rewritten in place.

### Phase 2: rescanning after a sync

MediaSync tells the player which files each sync changed (`FILES_CHANGED`, sent to the player's
package under the `com.tlisonbee.mediasync.permission.PLAYER` permission, which is `signature`:
only apps signed with MediaSync's key send or receive it; adb's shell is turned away). A player
that's running scans at once; one that isn't finds the changes when it next opens. Both apps
declare the permission, so either can be installed first.

### Phase 3: TIDAL

Built (Segue 0.3.0, `tidal/`), ported from the fooyin plugin (its docs/design.md has what was
learned about the APIs):

- **Two logins**, in Settings: the account (your developer app, PKCE; its redirect
  `http://localhost:47130/callback` is caught by the login page itself, so the same developer app
  as fooyin's works) and playback (a client of TIDAL's Android app, PKCE ending on
  `https://tidal.com/android/login/auth`). Both run in a web view that catches the redirect; if
  TIDAL's page won't work there, the browser and the address it ended on pasted back do the same.
  Tokens are in `files/tidal/{account,session}.json`, refreshed a minute before they expire.
  Client IDs and secrets are typed in by you and never in the repository.
- **Browsing** (v2): a TIDAL tab with search (tracks, albums, artists, playlists) and your
  favorite tracks, albums and artists and your playlists (mixes too); albums, playlists and
  artists open as pages. Lists come page by page and are kept for the session (10 minutes). Covers
  are TIDAL's 640-pixel images, kept with the others (keys `t…`).
- **Playback** (v1): a TIDAL track is `tidal://track/<id>` with the DASH type; `TidalDataSource`
  answers that address with the track's `playbackinfo` manifest when ExoPlayer gets to the track
  (it prepares playlist items lazily), so a long TIDAL queue asks for one at a time and segment
  addresses are fresh. Best quality TIDAL has (24-bit FLAC where there is one). Checked on the
  phone (`TidalDashAndroidTest`, with a manifest from the plugin's probe): 16- and 24-bit tracks
  play through the player's audio path and seek. A track whose signed addresses ran out (paused
  for long) is prepared again once with a fresh manifest.
- **One queue**: TIDAL tracks mix with local and server ones. The ones played are remembered
  (`files/tidal/tracks.json`), so a saved queue comes back with them.

Still to come: editing playlists and favorites (and finding your own files on TIDAL to add them),
a disk cache of the lists, and TIDAL tracks in Segue's own playlists.

Instrumented tests can run against the installed release build, without uninstalling it:
`./gradlew -Pplayer.testBuildType=release :player:assembleReleaseAndroidTest`, then install that
test APK and `am instrument` as above.

### Phase 2 notes

- **Playlists move with their tracks** (built, server 1.5.0). MediaSync renames a track when its
  tags change (`Artist/Album/NN Title`), which would break a playlist's paths, so the revision that
  moves a file rewrites the relative entries naming it in every `.m3u`/`.m3u8` under music/
  (`Playlists.kt`); devices get the new playlist with the move. Absolute paths and URLs are left
  alone. The player also still finds a missing entry by its `#EXTINF` title.
- **Streaming** (built: server 1.5.0, app 1.3.0, player 0.2.0). `GET /api/v1/music` lists every
  live track, whatever the device syncs (`?since=<rev>` answers "unchanged" cheaply). MediaSync
  lends the player its pairing (server address, this phone's token, its music folder) through
  `PlayerAccessProvider`, readable only with the signature permission. The player keeps the
  listing on disk (`files/remote/music.json`), shows the tracks the phone lacks (by where MediaSync
  would put them) with a cloud mark, and streams them from `/blobs/{hash}` with the token added
  only for that server; the player's User-Agent isn't `MediaSync-<kind>/…`, so the server doesn't
  take it for the app's version. Streamed files carry their tags from upload, so the listing's
  tags are the ones shown, and their covers are fetched as they're shown or played. Settings can
  hide them. Checked on the phone up to the network: the pairing is read and the provider refuses
  other apps; the first real stream waits for the server update.
