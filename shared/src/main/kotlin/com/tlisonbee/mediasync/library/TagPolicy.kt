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
    val fields: Map<AssetKind, List<String>> = DEFAULT_FIELDS,
    val pictures: Map<AssetKind, List<String>> = mapOf(AssetKind.AUDIO to listOf(FRONT_COVER)),
) {
    /** The allowed fields, in a stable order, with values trimmed, NFC-normalized and blanks dropped. */
    fun filter(kind: AssetKind, tags: Tags): Tags {
        val allowed = allowed(kind)
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

    /**
     * A kind the saved settings don't mention (videos, in settings from before them) gets the
     * default fields; MediaSync's own fields are always kept.
     */
    fun allowed(kind: AssetKind): List<String> = ((fields[kind] ?: DEFAULT_FIELDS[kind].orEmpty()) + OWN_FIELDS[kind].orEmpty()).distinct()

    companion object {
        const val FRONT_COVER = "front"
        val DEFAULT_FIELDS = mapOf(AssetKind.AUDIO to TagField.MUSIC, AssetKind.VIDEO to TagField.VIDEO, AssetKind.GAME to TagField.GAME)

        /** Fields MediaSync keeps itself and never writes into the files: a photo's (or a clip's) date. */
        val OWN_FIELDS = mapOf(AssetKind.IMAGE to listOf(TagField.DATE), AssetKind.VIDEO to listOf(TagField.DATE))

        fun cleanValue(value: String): String =
            Normalizer.normalize(value.trim(), Normalizer.Form.NFC).take(Limits.MAX_TAG_VALUE)
    }
}
