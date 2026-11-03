package com.tlisonbee.mediasync.player.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplayGainTest {
    @Test
    fun parsesTheWaysTagsWriteGains() {
        assertEquals(-6.02f, ReplayGain.parseDb("-6.02 dB")!!, 1e-6f)
        assertEquals(1.5f, ReplayGain.parseDb("+1.5 dB")!!, 1e-6f)
        assertEquals(-3.1f, ReplayGain.parseDb(" -3,1 db ")!!, 1e-6f)
        assertEquals(0.5f, ReplayGain.parseDb(".5")!!, 1e-6f)
        assertNull(ReplayGain.parseDb(""))
        assertNull(ReplayGain.parseDb("loud"))
        assertNull(ReplayGain.parseDb("NaN dB"))
    }

    @Test
    fun peaksMustBePositive() {
        assertEquals(0.988f, ReplayGain.parsePeak("0.988")!!, 1e-6f)
        assertNull(ReplayGain.parsePeak("0"))
        assertNull(ReplayGain.parsePeak("-1"))
    }

    @Test
    fun factorFollowsDecibelsAndAvoidsClipping() {
        assertEquals(0.5f, ReplayGain.factor(-6.0206f), 1e-4f)
        assertEquals(2f, ReplayGain.factor(3.0103f, preampDb = 3.0103f), 1e-3f)
        assertEquals(1f, ReplayGain.factor(0f), 0f)
        // +6 dB on a track peaking at 0.8 would clip: held to 1 / 0.8.
        assertEquals(1.25f, ReplayGain.factor(6.0206f, peak = 0.8f), 1e-6f)
        assertEquals(0.5f, ReplayGain.factor(-6.0206f, peak = 0.8f), 1e-4f)
    }
}
