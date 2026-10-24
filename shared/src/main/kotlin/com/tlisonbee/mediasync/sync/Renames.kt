package com.tlisonbee.mediasync.sync

/**
 * Follows the folders the server renamed, as server 0.5.0 renamed photos/ to images/photos/ when
 * it moved the photos there: the index moves along, and the server gets the rules back, which
 * makes its next listing a full one, so whatever the move took out of this device's scope leaves
 * it. The files stay where they are. Returns the new config for the caller to save, or null when
 * nothing was renamed. Run it before a sync, never during one.
 */
fun followRenames(config: ClientConfig, api: ApiClient, index: FileIndex): ClientConfig? {
    val renamed = config.renamedIn(api.subscription())
    if (renamed.isEmpty()) return null
    val updated = config.renaming(renamed)
    api.putSubscription(updated.subscription())
    index.transaction {
        for ((old, folder) in renamed) {
            for (row in index.inFolder(old)) {
                val base = row.base?.let { if (it.path.startsWith(old)) it.copy(path = folder.prefix + it.path.removePrefix(old)) else it }
                index.put(row.copy(prefix = folder.prefix, base = base))
            }
        }
    }
    return updated
}
