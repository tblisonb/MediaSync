package com.tlisonbee.mediasync.desktop

import com.tlisonbee.mediasync.sync.SyncStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JobProgressTest {
    @Test
    fun percentageAndTimeLeftComeFromBytes() {
        val p = JobProgress(SyncStage.UPLOADING, done = 10, total = 100, bytes = 2_000, totalBytes = 10_000, startedAt = 0, startBytes = 0)
        assertEquals(0.2, p.fraction)
        // 2,000 bytes in 10 s: the other 8,000 take 40 s.
        assertEquals(40_000, p.remainingMs(now = 10_000))
        assertNull(p.remainingMs(now = 1_000), "too early for a rate")
        assertEquals(0.5, p.copy(bytes = 0, totalBytes = 0, done = 50).fraction, "by files when bytes aren't known")
    }
}
