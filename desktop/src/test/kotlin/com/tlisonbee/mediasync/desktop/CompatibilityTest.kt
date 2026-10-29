package com.tlisonbee.mediasync.desktop

import com.tlisonbee.mediasync.server.device
import com.tlisonbee.mediasync.sync.ApiException
import com.tlisonbee.mediasync.sync.ClientInfo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CompatibilityTest {
    private val saved = Triple(ClientInfo.kind, ClientInfo.version, ClientInfo.minServer)

    @AfterTest
    fun restore() {
        ClientInfo.kind = saved.first
        ClientInfo.version = saved.second
        ClientInfo.minServer = saved.third
    }

    private fun desktop(version: String, needs: String) {
        ClientInfo.kind = "desktop"
        ClientInfo.version = version
        ClientInfo.minServer = needs
    }

    @Test
    fun aSyncStopsWhenTheServerIsTooOld() {
        TestWorld(serverVersion = "1.2.0").use { world ->
            desktop("1.1.0", needs = "1.3.0")
            val client = world.client("PC")
            val e = assertFailsWith<ApiException> { client.sync() }
            assertEquals("This desktop client needs server 1.3.0 or later; the server is 1.2.0. Update the server first.", e.message)
        }
    }

    @Test
    fun aNewEnoughServerSyncsAndHearsTheVersion() {
        TestWorld(serverVersion = "1.10.0").use { world ->
            desktop("1.1.0", needs = "1.3.0")
            val client = world.client("PC")
            client.sync()
            assertEquals("1.1.0", world.repo.device(client.config.deviceId)?.clientVersion)
        }
    }

    @Test
    fun aServerThatDoesntSayIsntRefused() {
        TestWorld(serverVersion = null).use { world ->
            desktop("1.1.0", needs = "1.3.0")
            // A development build: nothing to compare, so the sync goes ahead.
            world.client("PC").sync()
        }
    }
}
