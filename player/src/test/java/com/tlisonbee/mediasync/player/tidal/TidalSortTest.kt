package com.tlisonbee.mediasync.player.tidal

import com.tlisonbee.mediasync.player.library.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class TidalSortTest {
    private fun item(id: String, title: String, artist: String, album: String, added: Long?) = TidalItem(
        TidalItem.Kind.TRACK, id, title, artist, album, addedAt = added,
        track = Track(TidalTracks.PREFIX + id, 0, 0, title, listOf(artist), album, artist, "", "", null, null, null, null),
    )

    // A playlist in its own order: b was added last, c first.
    private val list = listOf(
        item("a", "Creep", "Radiohead", "Pablo Honey", 2000),
        item("b", "Africa", "Toto", "Toto IV", 3000),
        item("c", "The Boxer", "Simon & Garfunkel", "Bridge over Troubled Water", 1000),
        item("d", "Undated", "Zed", "Zed", null),
    )

    private fun ids(sort: TidalSort) = sort.apply(list).joinToString("") { it.id }

    @Test
    fun orders() {
        assertEquals("abcd", ids(TidalSort()))
        assertEquals("bacd", ids(TidalSort(TidalSort.By.ADDED)))
        assertEquals("dcab", ids(TidalSort(TidalSort.By.ADDED, reversed = true)))
        // "The" is ignored when sorting, as in the library.
        assertEquals("bcad", ids(TidalSort(TidalSort.By.TITLE)))
        assertEquals("acbd", ids(TidalSort(TidalSort.By.ARTIST)))
        assertEquals("dcba", ids(TidalSort(reversed = true)))
    }
}
