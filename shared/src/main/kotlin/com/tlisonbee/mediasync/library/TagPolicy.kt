package com.tlisonbee.mediasync.library

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.Limits
import com.tlisonbee.mediasync.api.PictureRef
import com.tlisonbee.mediasync.api.Tags
import com.tlisonbee.mediasync.tags.TagField
import kotlinx.serialization.Serializable
import java.text.Normalizer

/**
 * Which tag fields and picture types are kept, per kind of asset. Everything else is dropped on
 * import and at every sync, so ripper comments and the like never spread.
 */
@Serializable
data class TagPolicy(
    val fields: Map<AssetKind, List<String>> = mapOf(AssetKind.AUDIO to TagField.MUSIC),
    val pictures: Map<AssetKind, List<String>> = mapOf(AssetKind.AUDIO to listOf(FRONT_COVER)),
) {
    /** The allowed fields, in a stable order, with values trimmed, NFC-normalized and blanks dropped. */
    fun filter(kind: AssetKind, tags: Tags): Tags {
        val allowed = fields[kind].orEmpty()
        return allowed.mapNotNull { field ->
            val values = tags[field].orEmpty().map(::cleanValue).filter { it.isNotEmpty() }.distinct().take(Limits.MAX_TAG_VALUES)
            if (values.isEmpty()) null else field to values
        }.toMap()
    }

    /** The allowed pictures, at most one of each type. */
    fun filterPictures(kind: AssetKind, pictures: List<PictureRef>): List<PictureRef> {
        val allowed = this.pictures[kind].orEmpty()
        return pictures.filter { it.type in allowed }.distinctBy { it.type }.sortedBy { allowed.indexOf(it.type) }
    }

    companion object {
        const val FRONT_COVER = "front"

        fun cleanValue(value: String): String =
            Normalizer.normalize(value.trim(), Normalizer.Form.NFC).take(Limits.MAX_TAG_VALUE)
    }
}
