# MediaSync Player: design notes

An Android music player for the library MediaSync keeps on the phone, built in this repository as
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
| **1. Local player** | The index and its scan; browsing by artist, album, folder and playlist, and search; playback in context with a queue, shuffle and repeat; the media notification, lock screen and Bluetooth; covers from the files; picking up where it left off. ReplayGain tags, once MediaSync carries them. |
| **2. MediaSync** | The server's ReplayGain (above). Rescan the changed files right after a sync. Playlists as `.m3u8` files in a synced `Music/Playlists/` folder, shared with fooyin on the desktop. Streaming tracks the phone doesn't hold from the server over Tailscale. |
| **3. TIDAL** | Port the plugin's logins, catalog, cache and edits to Kotlin; TIDAL tracks mixed with local ones in the queue. |
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
distributed; the repository has no licence yet.

### Phase 2 notes

- **Playlists move with their tracks.** MediaSync renames a track when its tags change
  (`Artist/Album/NN Title`), which would break a playlist's paths. The server knows every move, so
  it should rewrite the paths in `music/Playlists/*.m3u8` in the same revision. Until then the
  player falls back to finding a missing entry by its tags.
- **Streaming** needs a server endpoint listing the whole music library, not only what this
  device syncs, and the player gets the server address and the device's token from MediaSync
  through the signature permission. `/blobs/{hash}` already serves files with range requests.
  Streamed tracks play from the stored original, whose tags may be older than the repository's, so
  the player shows the listing's tags.
