package com.tlisonbee.mediasync.desktop

import com.tlisonbee.mediasync.server.rename
import com.tlisonbee.mediasync.server.revision
import com.tlisonbee.mediasync.sync.Review
import com.tlisonbee.mediasync.sync.ReviewAction
import com.tlisonbee.mediasync.tags.AudioTags
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jaudiotagger.tag.FieldKey

/** Syncs that wait for approval: check stages and reports, apply does only what was accepted. */
class ReviewTest {
    private val album = Fixtures.album("Radiohead", "OK Computer", "Airbag", "Paranoid Android")

    /** A desktop that imported the album, and a laptop that synced it before switching to review. */
    private fun TestWorld.twoPcs(): Pair<TestClient, TestClient> {
        val desktop = client("Desktop")
        album.forEachIndexed { i, tags -> Fixtures.track(desktop.file("in/$i.flac"), tags, variant = i) }
        desktop.sync()
        val laptop = client("Laptop").apply { sync() }
        return desktop to laptop
    }

    private fun Review.accept(key: String) = decide(group(key)!!, ReviewAction.ACCEPT)

    private fun title(client: TestClient, path: String) = AudioTags.read(client.file(path)).fields["title"]

    @Test
    fun serverChangesWaitUntilAccepted() = TestWorld().use { world ->
        val (desktop, laptop) = world.twoPcs()
        Fixtures.retag(desktop.file("Radiohead/OK Computer/01 Airbag.flac"), mapOf(FieldKey.TITLE to "Airbag (Remastered)"))
        val rev = desktop.sync().rev!!

        val review = laptop.engine.check()
        val group = review.incoming.single()
        assertEquals("in:r$rev", group.key)
        assertEquals("Radiohead/OK Computer/01 Airbag.flac", group.items.single().path)
        assertTrue("moves to Radiohead/OK Computer/01 Airbag (Remastered)" in group.items.single().detail)
        assertTrue("title: Airbag → Airbag (Remastered)" in group.items.single().detail, group.items.single().detail)
        assertTrue(review.outgoing.isEmpty())
        assertEquals(listOf("Airbag"), title(laptop, "Radiohead/OK Computer/01 Airbag.flac"), "nothing written yet")
        assertEquals(review.version, laptop.engine.check().version, "a second check finds the same")

        laptop.engine.apply(review.accept(group.key))
        assertEquals(listOf("Airbag (Remastered)"), title(laptop, "Radiohead/OK Computer/01 Airbag (Remastered).flac"))
        assertTrue(laptop.engine.check().isEmpty)
    }

    @Test
    fun localChangesWaitAndCanBeUndone() = TestWorld().use { world ->
        val (_, laptop) = world.twoPcs()
        val head = world.repo.head()
        Fixtures.retag(laptop.file("Radiohead/OK Computer/02 Paranoid Android.flac"), mapOf(FieldKey.TITLE to "Paranoid"))
        Fixtures.track(laptop.file("new/x.flac"), Fixtures.album("Muse", "Absolution", "Hysteria").single(), variant = 7)

        val review = laptop.engine.check()
        assertEquals(listOf("out:new", "out:retagged"), review.outgoing.map { it.key })
        assertEquals("title: Paranoid Android → Paranoid", review.group("out:retagged")!!.items.single().detail)
        assertEquals(head, world.repo.head(), "nothing pushed")

        // Undo the retag; keep the new file off the server.
        laptop.engine.apply(review.decide(review.group("out:retagged")!!, ReviewAction.UNDO))
        laptop.engine.apply(review.decide(review.group("out:new")!!, ReviewAction.UNDO))
        assertEquals(listOf("Paranoid Android"), title(laptop, "Radiohead/OK Computer/02 Paranoid Android.flac"))
        assertTrue(Files.exists(laptop.file("new/x.flac")), "an undone new file stays, but isn't synced")
        assertTrue(laptop.engine.check().isEmpty)
        assertEquals(head, world.repo.head())
        laptop.sync()
        assertEquals(head, world.repo.head(), "not even by a full sync")
    }

    @Test
    fun acceptedLocalChangesArePushed() = TestWorld().use { world ->
        val (desktop, laptop) = world.twoPcs()
        Fixtures.retag(laptop.file("Radiohead/OK Computer/02 Paranoid Android.flac"), mapOf(FieldKey.GENRE to "Alternative"))
        val review = laptop.engine.check()
        val report = laptop.engine.apply(review.acceptAll())
        assertEquals(1, report.pushed)
        assertTrue(laptop.engine.check().isEmpty, "its own push isn't offered back")
        desktop.sync()
        assertEquals(listOf("Alternative"), AudioTags.read(desktop.file("Radiohead/OK Computer/02 Paranoid Android.flac")).fields["genre"])
    }

    @Test
    fun aServerChangeCanBeRevertedFromTheReview() = TestWorld().use { world ->
        val (_, laptop) = world.twoPcs()
        val rev = world.repo.rename(world.repo.webDeviceId(), "artist", setOf("Radiohead"), "RADIOHEAD")!!
        val review = laptop.engine.check()
        val group = review.incoming.single()
        assertEquals(2, group.items.size)
        assertTrue("Renamed artist" in group.title)

        laptop.engine.apply(review.decide(group, ReviewAction.REVERT))
        assertTrue(world.repo.revision(world.repo.head())!!.message.startsWith("Reverted r$rev"))
        assertTrue(laptop.engine.check().isEmpty, "the revert changes nothing here")
        assertTrue(Files.exists(laptop.file("Radiohead/OK Computer/01 Airbag.flac")))
    }

    @Test
    fun aFileChangedOnBothSidesIsMergedThroughThePush() = TestWorld().use { world ->
        val (desktop, laptop) = world.twoPcs()
        Fixtures.retag(desktop.file("Radiohead/OK Computer/01 Airbag.flac"), mapOf(FieldKey.GENRE to "Rock"))
        desktop.sync()
        Fixtures.retag(laptop.file("Radiohead/OK Computer/01 Airbag.flac"), mapOf(FieldKey.YEAR to "1997"))

        val review = laptop.engine.check()
        assertTrue(review.incoming.single().items.single().conflict)
        assertTrue(review.group("out:retagged")!!.items.single().conflict)
        val incomingOnly = laptop.engine.apply(review.accept(review.incoming.single().key))
        assertTrue(incomingOnly.warnings.single().contains("changed here too"))
        assertFalse(laptop.engine.check().incoming.isEmpty(), "still waiting")

        laptop.engine.apply(laptop.engine.review().accept("out:retagged"))
        val tags = AudioTags.read(laptop.file("Radiohead/OK Computer/01 Airbag.flac")).fields
        assertEquals(listOf("Rock"), tags["genre"])
        assertEquals(listOf("1997"), tags["date"])
        assertTrue(laptop.engine.check().isEmpty)
    }

    @Test
    fun aFullCheckFindsFilesThatAreMissingHere() = TestWorld().use { world ->
        val (_, laptop) = world.twoPcs()
        // A file that never arrived here, with nothing new on the server to say so.
        val airbag = laptop.file("Radiohead/OK Computer/01 Airbag.flac")
        laptop.index.delete(airbag.toString())
        Files.delete(airbag)
        assertTrue(laptop.engine.check().isEmpty)

        val review = laptop.engine.check(full = true)
        assertEquals("new file (flac)", review.incoming.single().items.single().detail)
        laptop.engine.apply(review.acceptAll())
        assertTrue(Files.exists(airbag))
        assertTrue(laptop.engine.check(full = true).isEmpty, "the rest matched")
    }

    @Test
    fun deletionsAndAFullSyncAfterReviewing() = TestWorld().use { world ->
        val (desktop, laptop) = world.twoPcs()
        Files.delete(desktop.file("Radiohead/OK Computer/01 Airbag.flac"))
        desktop.sync()
        Fixtures.retag(desktop.file("Radiohead/OK Computer/02 Paranoid Android.flac"), mapOf(FieldKey.TITLE to "PA"))
        desktop.sync()

        val review = laptop.engine.check()
        assertEquals(listOf("deleted", "title: Paranoid Android → PA"), review.incoming.flatMap { g -> g.items.map { it.detail.substringBefore(';') } })
        // Leaving review: a plain sync applies what was staged.
        laptop.sync()
        assertEquals(listOf("Radiohead/OK Computer/02 PA.flac"), laptop.files())
        assertTrue(laptop.engine.check().isEmpty)
    }

    /** Notifications the service showed, with their buttons. */
    private class RecordingNotifier : Notifier {
        val shown = CopyOnWriteArrayList<Pair<String, List<NotificationAction>>>()

        override fun show(key: String, summary: String, body: String, onClick: (() -> Unit)?, actions: List<NotificationAction>) {
            shown += summary to actions
        }
    }

    private fun <T : Any> waitFor(what: String, get: () -> T?): T {
        val until = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < until) {
            get()?.let { return it }
            Thread.sleep(100)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun theServiceAsksAndTheReviewPageDecides() = TestWorld().use { world ->
        val (desktop, laptop) = world.twoPcs()
        val notes = RecordingNotifier()
        val service = Service(laptop.dirs, laptop.config, laptop.token) { notes }
        val thread = Thread { service.run() }.apply { isDaemon = true; start() }
        try {
            val url = waitFor("the page") { runCatching { Files.readString(laptop.dirs.reviewUrl).trim() }.getOrNull() }
            val http = HttpClient.newHttpClient()
            fun get(u: String) = http.send(HttpRequest.newBuilder(URI(u)).build(), HttpResponse.BodyHandlers.ofString())
            fun post(u: String, form: String) = http.send(
                HttpRequest.newBuilder(URI(u)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            waitFor("the first check") { service.lastCheck() }
            assertTrue("Nothing waits" in get(url).body())

            Fixtures.retag(desktop.file("Radiohead/OK Computer/01 Airbag.flac"), mapOf(FieldKey.TITLE to "Airbag (Live)"))
            val rev = desktop.sync().rev!!
            assertEquals(303, post(url + "check", "").statusCode())
            waitFor("a notification") { notes.shown.lastOrNull()?.takeIf { "1 change from the server" in it.first } }
            val page = get(url).body()
            assertTrue("title: Airbag → Airbag (Live)" in page)
            assertTrue(Files.exists(laptop.file("Radiohead/OK Computer/01 Airbag.flac")), "nothing applied before a decision")
            val version = Regex("""value="in:r$rev"><input type="hidden" name="version" value="(\w+)"""").find(page)!!.groupValues[1]

            val stale = post(url + "act", "group=in%3Ar$rev&version=0000&action=accept")
            assertTrue("changed since" in URLDecoder.decode(stale.headers().firstValue("Location").get(), Charsets.UTF_8))
            post(url + "act", "group=in%3Ar$rev&version=$version&action=accept")
            waitFor("the accepted change") { Files.exists(laptop.file("Radiohead/OK Computer/01 Airbag (Live).flac")).takeIf { it } }
            assertTrue("Nothing waits" in get(url).body())

            // The notification's Accept all.
            Fixtures.retag(desktop.file("Radiohead/OK Computer/02 Paranoid Android.flac"), mapOf(FieldKey.TITLE to "PA"))
            desktop.sync()
            post(url + "check", "")
            val (_, actions) = waitFor("a second notification") { notes.shown.lastOrNull()?.takeIf { "1 change" in it.first && notes.shown.size >= 2 } }
            actions.single { it.label == "Accept all" }.run()
            waitFor("accept all") { Files.exists(laptop.file("Radiohead/OK Computer/02 PA.flac")).takeIf { it } }

            // Only with the secret, and only addressed to 127.0.0.1.
            val port = URI(url).port
            assertEquals(404, get("http://127.0.0.1:$port/guess/").statusCode())
            Socket("127.0.0.1", port).use { socket ->
                socket.getOutputStream().write("GET ${URI(url).path} HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n".toByteArray())
                assertTrue(socket.getInputStream().bufferedReader().readLine().contains(" 421 "))
            }
        } finally {
            service.stop()
            thread.join(10_000)
        }
    }

    @Test
    fun aPickingDeviceReceivesWhatIsPickedAndLetsGoOfIt() = TestWorld().use { world ->
        val pc = world.client("PC", videos = false)
        val videos = pc.music.resolveSibling("Videos")
        Files.createDirectories(videos.resolve("Movies"))
        Files.write(videos.resolve("Movies/Inception (2010).mp4"), ByteArray(50_000) { (it * 7).toByte() })
        Files.writeString(videos.resolve("Movies/Inception (2010).en.srt"), "1\n00:00:01,000 --> 00:00:02,000\nHi\n")
        Files.write(videos.resolve("Movies/Elf.mp4"), ByteArray(40_000) { (it * 3).toByte() })
        pc.sync()
        val movie = "movies/Inception (2010)/Inception (2010)"
        assertTrue(Files.exists(videos.resolve("$movie.mp4")), "filed on the PC")
        assertTrue(Files.exists(videos.resolve("$movie.en.srt")), "with its subtitles")

        val phone = world.client("Phone", videos = true)
        val phoneVideos = phone.music.resolveSibling("Videos")
        assertTrue(phone.engine.check().isEmpty, "nothing picked")
        val inception = phone.api.picks().single { it.label == "Inception (2010)" }
        phone.api.setPicks(com.tlisonbee.mediasync.api.PickRequest(pick = listOf(inception.assetId)))

        val arriving = phone.engine.check()
        assertEquals(listOf("new file (mp4)", "new file (srt)"), arriving.incoming.flatMap { g -> g.items.map { it.detail } }.sorted())
        phone.engine.apply(arriving.acceptAll())
        assertTrue(Files.exists(phoneVideos.resolve("$movie.mp4")))
        assertTrue(Files.exists(phoneVideos.resolve("$movie.en.srt")))
        assertFalse(Files.exists(phoneVideos.resolve("movies/Elf/Elf.mp4")))

        phone.api.setPicks(com.tlisonbee.mediasync.api.PickRequest(unpick = listOf(inception.assetId)))
        val leaving = phone.engine.check()
        assertEquals(2, leaving.group("in:left")!!.items.size)
        phone.engine.apply(leaving.acceptAll())
        assertFalse(Files.exists(phoneVideos.resolve("$movie.mp4")))
        assertTrue(Files.exists(videos.resolve("$movie.mp4")), "still on the PC")
    }
}
