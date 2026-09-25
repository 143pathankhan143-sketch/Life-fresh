package com.example.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM smoke tests for the chat history date formatting helper used by
 * the AI screen history dialog. No Android/Robolectric dependencies.
 */
class AIChatHistoryDateTest {

    @Test
    fun formatSessionDateContainsYear() {
        // Any 2026 timestamp — month/day text is locale- and timezone-dependent,
        // but the 4-digit year is stable across locales.
        val ts = 1789468200000L // 15 Sep 2026, 10:30 UTC
        val formatted = formatSessionDate(ts)
        assertFalse(formatted.isBlank())
        assertTrue(formatted.contains("2026"))
    }

    @Test
    fun formatSessionDateIsStableForSameInput() {
        val ts = 1789468200000L
        val first = formatSessionDate(ts)
        val second = formatSessionDate(ts)
        assertTrue(first == second)
    }

    @Test
    fun formatSessionDateNeverThrowsForEdgeTimestamps() {
        // Must not crash on epoch 0 or the maximum representable timestamp.
        formatSessionDate(0L)
        formatSessionDate(Long.MAX_VALUE)
    }
}
