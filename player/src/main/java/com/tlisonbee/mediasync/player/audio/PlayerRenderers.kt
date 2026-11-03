package com.tlisonbee.mediasync.player.audio

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/** ExoPlayer's renderers, with the player's own audio processors (the gain) in the audio sink. */
@UnstableApi
class PlayerRenderers(context: Context, private val processors: List<AudioProcessor>) : DefaultRenderersFactory(context) {
    init {
        // FFmpeg's decoder before Android's: Android's MP3 decoder clips each track's start and
        // pads its end, so gapless albums click at every track change (see DESIGN.md).
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
    }

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(processors.toTypedArray())
            .build()
}
