package com.tlisonbee.mediasync.desktop

import com.tlisonbee.mediasync.sync.Decisions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrayTest {
    private val done = mutableListOf<String>()
    private val actions = object : TrayActions {
        override fun openReview() {
            done += "review"
        }

        override fun acceptAll(decisions: Decisions) {
            done += "accept ${decisions.acceptOutgoing.sorted()}"
        }

        override fun checkNow() {
            done += "check"
        }

        override fun openWebUi() {
            done += "web"
        }
    }

    private fun List<TrayEntry>.entry(label: String) = single { it.label == label }

    @Test
    fun theMenuSaysWhatWaitsAndAcceptsThatList() {
        val state = TrayState(waiting = 12, summary = "12 changes made on this PC", acceptAll = Decisions(acceptOutgoing = setOf("a", "b")))
        val menu = trayMenu(state, actions)
        assertEquals("12 changes made on this PC", menu.first().label)
        assertFalse(menu.first().enabled, "the headline is a label, not a button")
        assertTrue(state.needsAttention)

        menu.entry("Accept all").run!!()
        menu.entry("Review changes…").run!!()
        menu.entry("Check now").run!!()
        menu.entry("Open web UI").run!!()
        assertEquals(listOf("accept [a, b]", "review", "check", "web"), done)
    }

    @Test
    fun nothingToAcceptWhileIdleOrBusy() {
        val idle = trayMenu(TrayState(), actions)
        assertEquals("Nothing waiting", idle.first().label)
        assertFalse(idle.entry("Accept all").enabled)
        assertFalse(TrayState().needsAttention)

        val busy = TrayState(waiting = 3, summary = "3 changes from the server", busy = "Applying your choices")
        val menu = trayMenu(busy, actions)
        assertEquals("Applying your choices…", menu.first().label)
        assertFalse(menu.entry("Accept all").enabled)
        assertFalse(menu.entry("Check now").enabled)
    }

    @Test
    fun withoutReviewItOffersASyncAndShowsFailures() {
        val state = TrayState(reviewing = false, error = "server unreachable", lastCheck = 0)
        val menu = trayMenu(state, actions)
        assertEquals("Sync failing: server unreachable", menu.first().label)
        assertTrue(menu.any { it.label == "Sync now" })
        assertTrue(state.needsAttention)
        assertTrue(state.tooltip().startsWith("Sync failing: server unreachable\nLast checked "), state.tooltip())
    }
}
