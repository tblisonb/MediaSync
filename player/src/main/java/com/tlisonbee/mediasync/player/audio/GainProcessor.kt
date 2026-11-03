package com.tlisonbee.mediasync.player.audio

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Multiplies the audio by a gain chosen per media item (ReplayGain). The audio sink flushes its
 * processors whenever the audio that follows belongs to another item, gapless transitions included,
 * and says which item that is ([AudioProcessor.StreamMetadata]); the gain switches there, on the
 * first sample of the new item.
 */
@UnstableApi
class GainProcessor(private val gainFor: (MediaItem) -> Float) : BaseAudioProcessor() {
    /** The factor applied now, 1 for none. Read from any thread. */
    @Volatile
    var gain = 1f
        private set

    /** Each switch, for the checks: the item's media ID and its factor. */
    val switches: MutableList<Pair<String, Float>> = java.util.Collections.synchronizedList(mutableListOf())

    private val window = Timeline.Window()
    private val period = Timeline.Period()

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val item = itemOf(streamMetadata)
        gain = item?.let(gainFor) ?: 1f
        switches += (item?.mediaId ?: "") to gain
    }

    private fun itemOf(metadata: AudioProcessor.StreamMetadata): MediaItem? {
        val timeline = metadata.timeline
        val uid = metadata.periodUid ?: return null
        if (timeline.isEmpty) return null
        val index = timeline.getIndexOfPeriod(uid)
        if (index == C.INDEX_UNSET) return null
        timeline.getPeriod(index, period)
        return timeline.getWindow(period.windowIndex, window).mediaItem
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)
        val factor = gain
        if (factor == 1f) {
            output.put(inputBuffer)
        } else if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            val input = inputBuffer.order(ByteOrder.nativeOrder())
            while (input.remaining() >= 2) {
                output.putShort((input.short * factor).roundToInt().coerceIn(-32768, 32767).toShort())
            }
        } else {
            val input = inputBuffer.order(ByteOrder.nativeOrder())
            while (input.remaining() >= 4) {
                output.putFloat((input.float * factor).coerceIn(-1f, 1f))
            }
        }
        output.flip()
    }
}
