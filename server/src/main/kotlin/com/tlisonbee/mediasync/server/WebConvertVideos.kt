package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.library.RepoPaths
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.progress
import kotlinx.html.span
import kotlinx.html.summary
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.html.ul

/** One format's videos as Cleanup lists them. */
internal class ConvertibleFormat(val format: String, val paths: List<String>, val bytes: Long)

/** What Cleanup's "Convert videos" section shows. */
internal class VideoSection(val formats: List<ConvertibleFormat>, val job: VideoConversion?, val free: Long)

internal fun Repository.videoSection(): VideoSection {
    val formats = convertibleVideos().map { (format, videos) ->
        val bytes = db.read { videos.sumOf { v -> v.renditions.firstOrNull()?.let(::media)?.size ?: 0L } }
        ConvertibleFormat(format, videos.map { it.path.removePrefix(RepoPaths.VIDEOS) + "." + format }, bytes)
    }.sortedByDescending { it.paths.size }
    return VideoSection(formats, videoConversion.get(), freeSpace())
}

internal fun FlowContent.videoSection(section: VideoSection, later: List<String>) {
    val running = section.job?.finished == false
    if (section.formats.isEmpty() && later.isEmpty() && !running) return
    h2 { id = "convert-videos"; +"Convert videos to HEVC" }
    p("muted") {
        +"Videos in older formats (WebM’s VP8 and VP9, AVI, WMV…) re-encoded the way rip-disc encodes a disc: HEVC "
        +"(x265, 10-bit, CRF 22) in an MKV file, with AAC sound, keeping their subtitles, chapters and tags. That usually takes "
        +"a third or more off the size with hardly a visible difference, though every re-encode loses a little. It’s slow, "
        +"about the video’s own length for 1080p, so ffmpeg runs at the lowest priority. Each folder is saved as a revision "
        +"once it’s done; reverting it brings the originals back. MP4 and MKV videos are left as they are."
    }
    p("muted") {
        +"Browsers without HEVC support (Firefox without hardware decoding, the desktop app’s Library tab) can’t play "
        +"converted videos in the web UI; phones, TVs and VLC can."
    }
    p {
        +"The repository’s disk has ${gigabytes(section.free)} free. The new files need room there, beside the originals, until "
        +"those are pruned (mediasync-server prune), and converting stops by itself if less than ${gigabytes(VIDEO_SPACE_MARGIN)} would be left. "
        +"On devices each video’s file is replaced, so they gain the space."
    }
    if (!Ffmpeg.available) p("error") { +"Converting needs ffmpeg on the server: sudo apt install ffmpeg" }
    val job = section.job
    if (job != null && running) {
        p {
            +"Converting now; its progress is at the top of this page. "
            a(href = "/cleanup/videos") { +"See its progress page" }
        }
        stopForm()
    } else if (section.formats.isEmpty()) {
        p("empty") { +"Every video is an MP4 or MKV." }
    } else if (Ffmpeg.available) {
        form(action = "/cleanup/videos", method = FormMethod.post) {
            table("leftovers") {
                thead { tr { th { }; th { +"Format" }; th { +"Videos" }; th { +"Size" } } }
                tbody {
                    section.formats.forEach { f ->
                        tr {
                            td {
                                input(type = InputType.checkBox, name = "format") {
                                    value = f.format
                                    checked = true
                                    attributes["aria-label"] = ".${f.format} videos"
                                }
                            }
                            td { +".${f.format}" }
                            td {
                                details {
                                    summary { +"${f.paths.size} ${if (f.paths.size == 1) "video" else "videos"}" }
                                    ul("list") { f.paths.take(300).forEach { li { +it } } }
                                    if (f.paths.size > 300) p("muted") { +"And ${f.paths.size - 300} more." }
                                }
                            }
                            td { +gigabytes(f.bytes) }
                        }
                    }
                }
            }
            p {
                label {
                    input(type = InputType.checkBox, name = "later") { checked = true }
                    +" Also convert videos in the ticked formats when they’re added later"
                }
            }
            div("actions") {
                button(classes = "primary") { +"Convert the ticked formats to HEVC" }
            }
        }
    }
    if (later.isNotEmpty()) {
        form(action = "/cleanup/videos-later", method = FormMethod.post, classes = "actions") {
            span { +"Videos added in ${later.joinToString { ".$it" }} are converted to HEVC as they arrive, and after a restart. " }
            button { +"Stop converting them" }
        }
    }
}

private fun FlowContent.stopForm() = form(action = "/cleanup/videos/stop", method = FormMethod.post, classes = "actions") {
    button { +"Stop converting" }
    span("muted") { +" Videos already saved stay converted; the one under way is dropped." }
}

internal fun HTML.videoConversionPage(job: VideoConversion) = page("Converting videos", refresh = if (job.finished) null else 10) {
    h1 { +"Converting videos to HEVC" }
    if (!job.finished) {
        videoProgress(job)
        job.current?.let { p { +"Now: "; span("muted") { +it } } }
        p("muted") { +"This page follows along. You can leave it, too: it carries on, and the Cleanup page shows how far it’s got." }
        stopForm()
    } else {
        p { +videoProgressText(job) }
    }
    job.error?.let { p("error") { +it } }
    if (job.revs.isNotEmpty()) {
        p("notice") {
            +"Saved as "
            job.revs.forEachIndexed { i, rev ->
                if (i > 0) +", "
                a(href = "/revisions/$rev") { +"r$rev" }
            }
            +". Devices get the MKV files at their next sync, and drop the originals."
        }
    }
    if (job.failed.isNotEmpty()) {
        p("error") { +"${job.failed.size} couldn’t be converted and stay as they are:" }
        ul("list") { job.failed.forEach { li { +it } } }
    }
    p { a(href = "/cleanup#convert-videos") { +"Back to Cleanup" } }
}

/** A video conversion's bar, by seconds of video, and how far along it is. */
internal fun FlowContent.videoProgress(job: VideoConversion) = div("progress") {
    progress {
        attributes["value"] = job.secondsSoFar.toInt().toString()
        attributes["max"] = job.totalSeconds.toInt().coerceAtLeast(1).toString()
        attributes["aria-label"] = "Videos converted"
    }
    p("progress-text") { +videoProgressText(job) }
}

/** "12 of 223 converted (5%), 5.2 GB → 3.3 GB, about 50 hours left", and the same once it's stopped or done. */
internal fun videoProgressText(job: VideoConversion): String {
    val failed = job.failed.size.takeIf { it > 0 }?.let { ", $it couldn’t be" }.orEmpty()
    val sizes = if (job.done > 0) ", ${gigabytes(job.bytesBefore)} → ${gigabytes(job.bytesAfter)}" else ""
    val percent = if (job.totalSeconds <= 0) job.done * 100 / job.total.coerceAtLeast(1) else (job.secondsSoFar * 100 / job.totalSeconds).toInt()
    return when {
        job.finished && job.stopped -> "Stopped: ${job.done} of ${job.total} converted$sizes$failed."
        job.finished && job.error != null -> "Stopped by itself: ${job.done} of ${job.total} converted$sizes$failed."
        job.finished -> "Finished: ${job.done} of ${job.total} converted$sizes$failed."
        else -> "${job.done} of ${job.total} converted ($percent%)$sizes$failed" + job.millisLeft()?.let { ", ${timeLeft(it)}" }.orEmpty() + "."
    }
}
