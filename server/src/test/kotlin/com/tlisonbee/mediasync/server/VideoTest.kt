package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetDto
import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.PushChange
import com.tlisonbee.mediasync.library.SubscriptionRule
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoTest {
    /** Pushes a file from [source] (a path on the device) as new, the way the clients do. */
    private suspend fun TestDevice.import(source: String, kind: AssetKind = AssetKind.VIDEO): AssetDto {
        val name = source.substringAfterLast('/')
        val file = TestDevice.fakeFile(name)
        upload(file)
        val path = "videos/" + source.removePrefix("/home/test/Videos/").substringBeforeLast('.')
        return push(PushChange(path = path, kind = kind, mediaHash = file.mediaHash, sourcePath = source)).results.single().asset
    }

    private fun ServerFixture.path(id: String) = repo.headVersion(id)!!.path

    private suspend fun ServerFixture.pc(): TestDevice =
        pair("PC").apply { subscribe(SubscriptionRule("videos/", "/home/test/Videos")) }

    @Test
    fun moviesAndEpisodesAreFiledFromTheirNames() = withServer {
        val pc = pc()
        val movie = pc.import("/home/test/Videos/Movies/Megamind.2010.1080p.BluRay.x264.YIFY.mp4")
        assertEquals("videos/movies/Megamind (2010)/Megamind (2010)", movie.path)
        assertEquals("movies", movie.category)
        assertEquals(mapOf("title" to listOf("Megamind"), "year" to listOf("2010")), movie.tags)

        val episode = pc.import("/home/test/Videos/TV Shows/Fringe/Fringe.S01E01.mp4")
        assertEquals("videos/shows/Fringe/Season 01/Fringe - S01E01", episode.path)

        val clip = pc.import("/home/test/Videos/Misc/clip.mp4")
        assertEquals("videos/Misc/clip", clip.path, "other videos keep their path")

        // Renaming a filed video on a device doesn't move it; changing its fields does.
        pc.push(pc.retag(movie.assetId, movie.tags).copy(path = "videos/movies/Megamind (2010)/renamed"))
        assertEquals("videos/movies/Megamind (2010)/Megamind (2010)", path(movie.assetId))
    }

    @Test
    fun subtitlesAndPostersJoinTheirVideoAndFollowIt() = withServer {
        val pc = pc()
        // Subtitles sort before the video, and may come in an earlier push.
        val english = pc.import("/home/test/Videos/Movies/Inception (2010).en.srt", AssetKind.OTHER)
        val plain = pc.import("/home/test/Videos/Movies/Inception (2010).srt", AssetKind.OTHER)
        val movie = pc.import("/home/test/Videos/Movies/Inception (2010).mp4")
        val poster = pc.import("/home/test/Videos/Movies/Inception (2010)-poster.jpg", AssetKind.IMAGE)
        val folder = "videos/movies/Inception (2010)/Inception (2010)"
        assertEquals(folder, movie.path)
        assertEquals("$folder.en", path(english.assetId))
        assertEquals("$folder.und", path(plain.assetId), "a subtitle without a language")
        assertEquals("$folder-poster", path(poster.assetId))
        assertTrue(repo.openIssues().isEmpty(), "sharing a name with the video for a moment is no collision")

        // A new title moves the movie, and its sidecars with it.
        repo.editTags(repo.webDeviceId(), "Retitled", mapOf(movie.assetId to mapOf("title" to listOf("Inception Redux"))))
        val moved = "videos/movies/Inception Redux (2010)/Inception Redux (2010)"
        assertEquals(moved, path(movie.assetId))
        assertEquals("$moved.en", path(english.assetId))
        assertEquals("$moved-poster", path(poster.assetId))
        assertEquals(listOf(english.assetId, plain.assetId, poster.assetId).toSet(), repo.sidecarsOf(repo.headVersion(movie.assetId)!!).map { it.first.assetId }.toSet())
    }

    @Test
    fun aDeviceKeepsOnlyTheVideosPickedForIt() = withServer {
        val pc = pc()
        val movie = pc.import("/home/test/Videos/Movies/Inception (2010).mp4")
        val subtitle = pc.import("/home/test/Videos/Movies/Inception (2010).en.srt", AssetKind.OTHER)
        val other = pc.import("/home/test/Videos/Movies/Elf.mp4")
        val phone = pair("Phone", com.tlisonbee.mediasync.api.DeviceKind.ANDROID)
        phone.subscribe(SubscriptionRule("videos/", "/sdcard/Movies/MediaSync", pick = true))

        assertTrue(phone.pull().flatMap { it.assets }.isEmpty(), "nothing picked yet")
        val list = phone.get("/picks?prefix=videos/").body<List<com.tlisonbee.mediasync.api.PickItem>>()
        assertEquals(listOf("Elf", "Inception (2010)"), list.map { it.label })

        val picked = phone.put("/picks", com.tlisonbee.mediasync.api.PickRequest(pick = listOf(movie.assetId))).body<com.tlisonbee.mediasync.api.PickResponse>()
        assertEquals(setOf(movie.assetId, subtitle.assetId), picked.changed.toSet())
        val listed = phone.pull().flatMap { it.assets }.map { it.assetId }.toSet()
        assertEquals(setOf(movie.assetId, subtitle.assetId), listed, "the movie and its subtitle, not Elf")
        assertTrue(other.assetId !in listed)

        phone.put("/picks", com.tlisonbee.mediasync.api.PickRequest(unpick = listOf(movie.assetId)))
        val after = phone.pull()
        assertTrue(after.first().reset)
        assertTrue(after.flatMap { it.assets }.isEmpty(), "a full listing without it: the phone removes it")

        // A video the phone brings in itself stays on it.
        val file = TestDevice.fakeFile("clip.mp4")
        phone.upload(file)
        val clip = phone.push(PushChange(path = "videos/clip", kind = AssetKind.VIDEO, mediaHash = file.mediaHash, sourcePath = "/sdcard/Movies/MediaSync/clip.mp4")).results.single().asset
        assertTrue(clip.assetId in repo.picks(phone.id))
    }

    @Test
    fun theWebUiEditsVideosAddsSubtitlesAndPicks() = withServer {
        val pc = pc()
        val movie = pc.import("/home/test/Videos/Movies/Absolute Cinema/The Justin Show - Episode 1.mp4")
        assertEquals("videos/shows/The Justin Show/Season 01/The Justin Show - S01E01", movie.path)
        val phone = pair("Phone", com.tlisonbee.mediasync.api.DeviceKind.ANDROID)
        phone.subscribe(SubscriptionRule("videos/", "/sdcard/Movies/MediaSync", pick = true))
        val web = browser().also {
            it.submitForm("/login", io.ktor.http.parameters { append("password", ADMIN_PASSWORD) })
        }
        val folder = "videos/shows/The Justin Show/Season 01"
        assertTrue("Edit these videos" in web.get("/browse?path=" + java.net.URLEncoder.encode(folder, Charsets.UTF_8)).bodyAsText())
        val editor = web.get("/videos/edit?path=" + java.net.URLEncoder.encode(folder, Charsets.UTF_8)).bodyAsText()
        assertTrue("Keep all on Phone" in editor)

        // It's a short film, not an episode.
        web.submitForm("/videos/edit", io.ktor.http.parameters {
            append("folder", folder)
            append("rev.${movie.assetId}", movie.rev.toString())
            append("${movie.assetId}.category", "movies")
            append("${movie.assetId}.show", "")
            append("${movie.assetId}.season", "")
            append("${movie.assetId}.episode", "")
            append("${movie.assetId}.title", "The Justin Show Pilot")
            append("${movie.assetId}.year", "2014")
        })
        val filed = "videos/movies/The Justin Show Pilot (2014)/The Justin Show Pilot (2014)"
        assertEquals(filed, path(movie.assetId))
        assertEquals("movies", repo.headVersion(movie.assetId)!!.category)

        // Subtitles from the browser.
        val upload = web.submitFormWithBinaryData("/assets/${movie.assetId}/sidecars", io.ktor.client.request.forms.formData {
            append("language", "en")
            append("file", "1\n00:00:01,000 --> 00:00:02,000\nHello\n".toByteArray(), io.ktor.http.Headers.build {
                append(io.ktor.http.HttpHeaders.ContentDisposition, "filename=\"pilot.srt\"")
            })
        })
        assertEquals(io.ktor.http.HttpStatusCode.Found, upload.status)
        val sidecar = repo.liveAssetAt("$filed.en")!!
        assertTrue("The Justin Show Pilot (2014).en" in web.get("/assets/${movie.assetId}").bodyAsText(), "listed on the video's page")

        // Keep it on the phone, then take the subtitles away again.
        web.submitForm("/assets/${movie.assetId}/picks", io.ktor.http.parameters { append("keep.${phone.id}", "on") })
        assertTrue(movie.assetId in repo.picks(phone.id))
        assertEquals(setOf(movie.assetId, sidecar), phone.pull().flatMap { it.assets }.map { it.assetId }.toSet())
        web.submitForm("/assets/$sidecar/delete", io.ktor.http.parameters { append("back", "/assets/${movie.assetId}") })
        assertTrue(repo.headVersion(sidecar)!!.deleted)
    }

    @Test
    fun videosHaveAGalleryWithMovingStillsAndPlayInTheViewer() = withServer {
        val pc = pc()
        val fixture = javaClass.getResourceAsStream("/clip.mkv")!!.readBytes()
        val file = TestDevice.file("Inception (2010).mkv", fixture)
        pc.upload(file)
        val movie = pc.push(
            PushChange(path = "videos/movies/Inception (2010)/Inception (2010)", kind = AssetKind.VIDEO, mediaHash = file.mediaHash, sourcePath = "/home/test/Videos/Movies/Inception (2010).mkv"),
        ).results.single().asset
        pc.import("/home/test/Videos/Movies/Inception (2010).en.srt", AssetKind.OTHER)
        val episode = pc.import("/home/test/Videos/TV Shows/Fringe/Fringe.S01E01.mp4")
        val blob = movie.renditions.single().blob
        val web = browser().also {
            it.submitForm("/login", io.ktor.http.parameters { append("password", ADMIN_PASSWORD) })
        }

        // Videos opens on videos/, and a film's tile opens the film itself, moving on hover.
        val top = web.get("/videos").bodyAsText()
        assertTrue("/videos?path=videos%2Fmovies" in top && "/videos?path=videos%2Fshows" in top, top)
        val movies = web.get("/videos?path=videos/movies").bodyAsText()
        assertTrue("/view?id=${movie.assetId}" in movies, movies)
        assertTrue("data-frames=\"/web/frames/$blob\"" in movies && "/static/previews.js" in movies)
        val season = web.get("/videos?path=" + java.net.URLEncoder.encode("videos/shows/Fringe/Season 01", Charsets.UTF_8)).bodyAsText()
        assertTrue("/view?id=${episode.assetId}" in season, season)
        val library = web.get("/browse?path=" + java.net.URLEncoder.encode("videos/movies/Inception (2010)", Charsets.UTF_8)).bodyAsText()
        assertTrue("Watch these videos" in library && "/view?id=${movie.assetId}" in library, library)

        // The viewer plays it, steps past its subtitles, and goes back up to the films.
        val viewer = web.get("/view?id=${movie.assetId}").bodyAsText()
        assertTrue("<video" in viewer && "src=\"/web/blobs/$blob\"" in viewer && "1 of 1" in viewer, viewer)
        assertTrue("href=\"/videos?path=videos%2Fmovies\" rel=\"up\"" in viewer, viewer)
        assertEquals(HttpStatusCode.OK, web.get("/web/blobs/$blob").status)

        if (!Ffmpeg.available) return@withServer
        assertTrue("poster=\"/web/thumbs/$blob\"" in viewer)
        assertTrue("its sound is Dolby Digital (AC-3)" in viewer && "160×90" in viewer && "H.264" in viewer, "why it may play silently, and what it is")
        val thumb = web.get("/web/thumbs/$blob")
        assertEquals(HttpStatusCode.OK, thumb.status)
        assertEquals(160, ImageIO.read(thumb.bodyAsBytes().inputStream()).width)
        val first = web.get("/web/frames/$blob/0").bodyAsBytes()
        val last = web.get("/web/frames/$blob/${FRAMES - 1}").bodyAsBytes()
        assertTrue(ImageIO.read(first.inputStream()) != null && ImageIO.read(last.inputStream()) != null)
        assertFalse(first.contentEquals(last), "stills from across the video, not one")
        assertTrue(Files.exists(repo.dir.frame(blob, 0)), "kept for next time")
        assertEquals(HttpStatusCode.NotFound, web.get("/web/frames/$blob/$FRAMES").status)
        // Not a real video: no stills, and no error either.
        assertEquals(HttpStatusCode.NotFound, web.get("/web/thumbs/${episode.renditions.single().blob}").status)
        assertEquals(HttpStatusCode.NotFound, web.get("/web/frames/${episode.renditions.single().blob}/0").status)
    }

    @Test
    fun aDeviceCanEditFieldsAndKindsThroughTheApi() = withServer {
        val pc = pc()
        val clip = pc.import("/home/test/Videos/Movies/Gang Movie.mp4")
        val subtitle = pc.import("/home/test/Videos/Movies/Gang Movie.en.srt", AssetKind.OTHER)
        assertEquals("videos/movies/Gang Movie/Gang Movie", path(clip.assetId))

        // A home movie, not a film: it leaves the movies folder, with its subtitles.
        val edit = com.tlisonbee.mediasync.api.EditRequest("Home movies", mapOf(clip.assetId to mapOf("category" to listOf("other"))))
        val rev = pc.post("/edits", edit).body<com.tlisonbee.mediasync.api.EditResponse>().rev!!
        assertEquals("videos/other/Gang Movie", path(clip.assetId))
        assertEquals("videos/other/Gang Movie.en", path(subtitle.assetId))
        assertEquals("Home movies", repo.revision(rev)!!.message)

        // Only within the device's subscription.
        val music = pair("Music only").apply { subscribeMusic() }
        assertEquals(io.ktor.http.HttpStatusCode.Forbidden, music.post("/edits", edit).status)
    }
}
