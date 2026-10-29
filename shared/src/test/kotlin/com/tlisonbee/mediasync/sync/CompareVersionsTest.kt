package com.tlisonbee.mediasync.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompareVersionsTest {
    @Test
    fun versionsCompareByNumberNotText() {
        assertTrue(compareVersions("1.10.0", "1.9.2") > 0)
        assertTrue(compareVersions("1.2.0", "1.3.0") < 0)
        assertEquals(0, compareVersions("1.3", "1.3.0"))
        assertEquals(0, compareVersions("1.4.0-dev", "1.4.0"))
        assertTrue(compareVersions("2.0.0", "1.99.99") > 0)
    }

    @Test
    fun theUserAgentNamesTheApp() {
        val saved = Triple(ClientInfo.kind, ClientInfo.version, ClientInfo.minServer)
        try {
            ClientInfo.kind = "android"
            ClientInfo.version = "1.2.0"
            assertEquals("MediaSync-android/1.2.0", ClientInfo.userAgent)
            ClientInfo.kind = null
            assertEquals(null, ClientInfo.userAgent)
        } finally {
            ClientInfo.kind = saved.first
            ClientInfo.version = saved.second
            ClientInfo.minServer = saved.third
        }
    }
}
