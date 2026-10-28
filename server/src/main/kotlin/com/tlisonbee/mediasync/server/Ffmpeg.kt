package com.tlisonbee.mediasync.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit

/** A video as ffprobe reads it: codecs by ffmpeg's names (h264, hevc, aac, dts…). */
class Probe(val seconds: Double?, val video: String?, val width: Int?, val height: Int?, val audio: List<String>) {
    /**
     * Why a browser might not play it, for the viewer to say. Current Firefox and Chrome open MP4,
     * WebM and MKV, and play H.264, VP8, VP9 and AV1 pictures (HEVC too, mostly) with AAC, MP3, Opus,
     * Vorbis or FLAC sound. Sound they can't play is the one to warn of: the picture plays without
     * it, where a picture they can't play fails, and the viewer says so then.
     */
    fun doubts(): List<String> = buildList {
        if (video == "hevc") add("its picture is HEVC (H.265), which not every browser plays")
        else if (video != null && video !in PLAYED_VIDEO) add("its picture is ${codecName(video)}, which browsers don't play")
        val unplayed = audio.filter { it !in PLAYED_AUDIO }.distinct()
        if (audio.isNotEmpty() && unplayed.size == audio.distinct().size) {
            add("its sound is ${unplayed.joinToString(" and ") { codecName(it) }}, which browsers don't play, so it plays silently")
        }
    }

    companion object {
        private val PLAYED_VIDEO = setOf("h264", "vp8", "vp9", "av1")
        private val PLAYED_AUDIO = setOf("aac", "mp3", "opus", "vorbis", "flac")
        private val NAMES = mapOf(
            "h264" to "H.264", "hevc" to "HEVC", "av1" to "AV1", "vp8" to "VP8", "vp9" to "VP9", "mpeg2video" to "MPEG-2", "mpeg4" to "MPEG-4",
            "aac" to "AAC", "mp3" to "MP3", "opus" to "Opus", "vorbis" to "Vorbis", "flac" to "FLAC", "ac3" to "Dolby Digital (AC-3)",
            "eac3" to "Dolby Digital Plus (E-AC-3)", "truehd" to "Dolby TrueHD", "dts" to "DTS",
        )

        fun codecName(codec: String) = NAMES[codec] ?: codec.uppercase()
    }
}

internal object Ffmpeg {
    val available: Boolean by lazy {
        runCatching {
            val process = ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0
        }.getOrDefault(false)
    }

    /** What ffprobe reads of [input]: its length, its size and the codecs of its picture and sound; null if it can't. */
    fun probe(input: Path): Probe? = runCatching {
        val process = ProcessBuilder(
            "ffprobe", "-v", "error", "-show_entries", "format=duration:stream=codec_type,codec_name,width,height:stream_disposition=attached_pic",
            "-of", "json", input.toString(),
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = process.inputStream.readBytes().decodeToString()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() != 0) return null
        val json = Json.parseToJsonElement(out).jsonObject
        val format = json["format"]?.jsonObject
        val streams = json["streams"]?.jsonArray.orEmpty().map { it.jsonObject }
        fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull
        // A cover picture is a video stream too; the picture is the first that isn't one.
        val picture = streams.firstOrNull {
            it.text("codec_type") == "video" && it["disposition"]?.jsonObject?.get("attached_pic")?.jsonPrimitive?.intOrNull != 1
        }
        Probe(
            seconds = format?.text("duration")?.toDoubleOrNull(),
            video = picture?.text("codec_name"),
            width = picture?.get("width")?.jsonPrimitive?.intOrNull,
            height = picture?.get("height")?.jsonPrimitive?.intOrNull,
            audio = streams.filter { it.text("codec_type") == "audio" }.mapNotNull { it.text("codec_name") },
        )
    }.getOrNull()

    /**
     * The frame of [input] at the keyframe nearest [seconds], fitting [size] pixels, as a JPEG at
     * [out]: a keyframe needs no decoding of the frames before it, so even a film takes well under a
     * second. Near the end there may be no keyframe left to find, and then it decodes its way there.
     */
    fun still(input: Path, seconds: Double, size: Int, out: Path): Boolean = runCatching {
        for (keyframe in listOf(listOf("-skip_frame", "nokey"), emptyList())) {
            val command = listOf("ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y") + keyframe + listOf(
                "-ss", "%.3f".format(Locale.ROOT, seconds), "-noaccurate_seek", "-i", input.toString(),
                "-map", "0:V:0", "-frames:v", "1", "-vf", "scale=w='min($size,iw)':h='min($size,ih)':force_original_aspect_ratio=decrease",
                "-q:v", "4", "-f", "image2", "-c:v", "mjpeg", out.toString(),
            )
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            if (!process.waitFor(1, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                return false
            }
            if (process.exitValue() == 0 && Files.size(out) > 0) return true
        }
        false
    }.getOrDefault(false)

    /**
     * [input] as an MP3 in a temporary file under [work], with its tags and cover; null if ffmpeg
     * can't (a DRM-protected WMA, say). A cover MP3 can't hold is left out rather than the track.
     */
    fun toMp3(input: Path, work: Path): Path? {
        val out = Files.createTempFile(work, "convert-", ".mp3")
        val withCover = listOf("-map", "0:v:0?", "-c:v", "copy", "-disposition:v", "attached_pic")
        for (picture in listOf(withCover, emptyList())) {
            val command = listOf("ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-i", input.toString(), "-map", "0:a:0") +
                picture + listOf("-c:a", "libmp3lame", "-q:a", "0", "-map_metadata", "0", "-id3v2_version", "3", "-f", "mp3", out.toString())
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                break
            }
            if (process.exitValue() == 0 && Files.size(out) > 0) return out
        }
        Files.deleteIfExists(out)
        return null
    }
}
