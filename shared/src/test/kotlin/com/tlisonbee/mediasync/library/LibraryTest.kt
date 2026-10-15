package com.tlisonbee.mediasync.library

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.PictureRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepoPathsTest {
    private fun tags(vararg pairs: Pair<String, String>) = pairs.groupBy({ it.first }, { it.second })

    @Test
    fun `music path follows the template`() {
        val t = tags("albumartist" to "Radiohead", "album" to "OK Computer", "tracknumber" to "1/12", "title" to "Airbag")
        assertEquals("music/Radiohead/OK Computer/01 Airbag", RepoPaths.music(t, "x", multiDisc = false))
        assertEquals("music/Radiohead/OK Computer/1-01 Airbag", RepoPaths.music(t + tags("discnumber" to "1"), "x", multiDisc = true))
    }

    @Test
    fun `fallbacks for missing tags`() {
        assertEquals("music/Unknown Artist/Unknown Album/track07", RepoPaths.music(emptyMap(), "track07", multiDisc = false))
        assertEquals("music/Bjork/Unknown Album/Joga", RepoPaths.music(tags("artist" to "Bjork", "title" to "Joga"), "x", false))
    }

    @Test
    fun `unsafe characters are replaced`() {
        val t = tags("albumartist" to "AC/DC", "album" to "What? Now: \"Live\"...", "title" to "CON", "tracknumber" to "3")
        assertEquals("music/AC_DC/What_ Now_ _Live_/03 CON", RepoPaths.music(t, "x", false))
        assertEquals("CON_", RepoPaths.segment("CON"))
        assertEquals("_", RepoPaths.segment(" .. "))
    }

    @Test
    fun `long names are cut at a character boundary`() {
        val long = "日本".repeat(100)
        val segment = RepoPaths.segment(long)
        assertTrue(segment.toByteArray().size <= 255)
        assertTrue(long.startsWith(segment))
    }

    @Test
    fun `disc numbers`() {
        assertTrue(RepoPaths.saysMultiDisc(tags("discnumber" to "2")))
        assertTrue(RepoPaths.saysMultiDisc(tags("discnumber" to "1/2")))
        assertFalse(RepoPaths.saysMultiDisc(tags("discnumber" to "1")))
        assertFalse(RepoPaths.saysMultiDisc(tags("discnumber" to "1/1")))
    }

    @Test
    fun `device paths are validated`() {
        RepoPaths.validate("music/a/b")
        assertFailsWith<IllegalArgumentException> { RepoPaths.validate("music/../b") }
        assertFailsWith<IllegalArgumentException> { RepoPaths.validate("/music/a") }
        assertFailsWith<IllegalArgumentException> { RepoPaths.validate("music//a") }
    }
}

class TagPolicyTest {
    @Test
    fun `only allowed fields survive`() {
        val policy = TagPolicy()
        val filtered = policy.filter(
            AssetKind.AUDIO,
            mapOf("title" to listOf(" Airbag "), "comment" to listOf("Ripped by EAC"), "artist" to listOf("A", "", "A", "B")),
        )
        assertEquals(mapOf("title" to listOf("Airbag"), "artist" to listOf("A", "B")), filtered)
        assertEquals(emptyMap(), policy.filter(AssetKind.IMAGE, mapOf("title" to listOf("x"))))
        val pictures = listOf(PictureRef("back", "b", "image/jpeg"), PictureRef("front", "f", "image/jpeg"))
        assertEquals(listOf(pictures[1]), policy.filterPictures(AssetKind.AUDIO, pictures))
    }
}

class SubscriptionTest {
    @Test
    fun `rules select by prefix, mode and filter`() {
        val sub = Subscription(
            listOf(
                SubscriptionRule("music/", "/sdcard/Music", SyncMode.TWO_WAY, filter = listOf("not path:music/Podcasts/**", "kind:audio")),
                SubscriptionRule("photos/camera/", "/sdcard/DCIM/Camera", SyncMode.SEND_ONLY),
            ),
        )
        sub.validate()
        assertTrue(sub.receives("music/Radiohead/OK Computer/01 Airbag", AssetKind.AUDIO))
        assertFalse(sub.receives("music/Podcasts/Show/Ep 1", AssetKind.AUDIO))
        assertFalse(sub.receives("music/cover", AssetKind.IMAGE))
        assertFalse(sub.receives("photos/camera/2026/2026-10/IMG_1", AssetKind.IMAGE))
        assertTrue(sub.sends("photos/camera/2026/2026-10/IMG_1", AssetKind.IMAGE))
    }

    @Test
    fun `bad rules are rejected`() {
        assertFailsWith<IllegalArgumentException> { SubscriptionRule("music", "/x").validate() }
        assertFailsWith<IllegalArgumentException> { SubscriptionRule("music/", "/x", filter = listOf("tag:genre=Jazz")).validate() }
    }
}

class ThreeWayMergeTest {
    @Test
    fun `fields merge independently`() {
        val base = mapOf("title" to "a", "artist" to "x", "genre" to "rock")
        val ours = mapOf("title" to "b", "artist" to "x", "genre" to "pop")
        val theirs = mapOf("title" to "a", "artist" to "y", "genre" to "jazz", "date" to "1997")
        val result = ThreeWayMerge.merge(base, ours, theirs)
        assertEquals(mapOf("title" to "b", "artist" to "y", "genre" to "jazz", "date" to "1997"), result.merged)
        assertEquals(setOf("genre"), result.conflicts)
    }

    @Test
    fun `removing a field is a change`() {
        val result = ThreeWayMerge.merge(mapOf("genre" to "rock"), emptyMap(), mapOf("genre" to "rock"))
        assertEquals(emptyMap(), result.merged)
        assertEquals(emptySet(), result.conflicts)
    }

    @Test
    fun `without a base every difference conflicts`() {
        val result = ThreeWayMerge.merge(null, mapOf("a" to 1, "b" to 2), mapOf("a" to 1, "b" to 3))
        assertEquals(mapOf("a" to 1, "b" to 3), result.merged)
        assertEquals(setOf("b"), result.conflicts)
    }
}
