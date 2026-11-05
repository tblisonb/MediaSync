package com.tlisonbee.mediasync.player.tidal

/** An order for a TIDAL list of tracks (a playlist, your favorites); albums keep theirs. */
data class TidalSort(val by: By = By.LIST, val reversed: Boolean = false) {
    enum class By(val label: String) {
        /** As TIDAL lists it: a playlist's own order; favorites newest first. */
        LIST("Playlist order"),

        /** Newest first; reversed, oldest first. */
        ADDED("Recently added"),
        TITLE("Title"),
        ARTIST("Artist"),
        ALBUM("Album"),
    }

    /** [items] in this order; the sort is stable, so ties keep the list's order. */
    fun apply(items: List<TidalItem>): List<TidalItem> {
        val sorted = when (by) {
            By.LIST -> items
            // Entries without a date (none are expected) go last.
            By.ADDED -> items.sortedByDescending { it.addedAt ?: Long.MIN_VALUE }
            By.TITLE -> items.sortedWith(compareBy({ it.track?.titleKey }, { it.track?.artistKey }))
            By.ARTIST -> items.sortedWith(compareBy({ it.track?.artistKey }, { it.track?.albumTitleKey }, { it.track?.discNumber }, { it.track?.trackNumber }))
            By.ALBUM -> items.sortedWith(compareBy({ it.track?.albumTitleKey }, { it.track?.discNumber }, { it.track?.trackNumber }))
        }
        return if (reversed) sorted.asReversed() else sorted
    }
}
