package com.example.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceNavigatorTest {

    @Test
    fun everyDestinationMapsToARegisteredRoute() {
        val routes = VocalDestination.values().map { VoiceNavigator.routeFor(it) }.toSet()
        assertTrue(routes.containsAll(setOf("dashboard", "leads", "ai", "reports", "settings")))
        assertEquals(5, routes.size)
    }

    @Test
    fun aiHistoryOpensTheAiTab() {
        assertEquals("ai", VoiceNavigator.routeFor(VocalDestination.AI_HISTORY))
        assertEquals("ai", VoiceNavigator.routeFor(VocalDestination.AI_CHAT))
    }

    @Test
    fun onlyLeadsAndDashboardAnnounceCounts() {
        assertTrue(VoiceNavigator.announcesCounts(VocalDestination.LEADS))
        assertTrue(VoiceNavigator.announcesCounts(VocalDestination.DASHBOARD))
        assertFalse(VoiceNavigator.announcesCounts(VocalDestination.REPORTS))
        assertFalse(VoiceNavigator.announcesCounts(VocalDestination.SETTINGS))
        assertFalse(VoiceNavigator.announcesCounts(VocalDestination.AI_CHAT))
    }
}
