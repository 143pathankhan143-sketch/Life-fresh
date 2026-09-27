package com.example.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Groq's Orpheus TTS accepts at most 200 characters per request, so a longer
 * English reply is spoken as several requests. The splitter must never lose or
 * duplicate text - a truncated sentence would be worse than no natural voice.
 */
class OrpheusChunkerTest {

    @Test
    fun shortTextStaysOnePiece() {
        assertEquals(listOf("Hello there."), OrpheusChunker.split("Hello there."))
    }

    @Test
    fun blankTextProducesNothing() {
        assertTrue(OrpheusChunker.split("   ").isEmpty())
        assertTrue(OrpheusChunker.split("").isEmpty())
    }

    @Test
    fun everyPieceFitsTheGroqLimit() {
        val text = (1..40).joinToString(" ") { "Sentence number $it is short." }
        val pieces = OrpheusChunker.split(text)
        assertTrue("expected several pieces", pieces.size > 1)
        for (p in pieces) {
            assertTrue("piece too long (${p.length}): $p", p.length <= OrpheusChunker.MAX_INPUT_CHARS)
        }
    }

    @Test
    fun nothingIsLostWhenSplitting() {
        val text = (1..25).joinToString(" ") { "Line $it." }
        val pieces = OrpheusChunker.split(text)
        val rejoined = pieces.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        assertEquals(text.replace(Regex("\\s+"), " ").trim(), rejoined)
    }

    @Test
    fun aWordLongerThanTheLimitIsHardCutAndTerminates() {
        val pieces = OrpheusChunker.split("a".repeat(500))
        assertTrue(pieces.size >= 2)
        assertEquals(500, pieces.sumOf { it.length })
        for (p in pieces) assertTrue(p.length <= OrpheusChunker.MAX_INPUT_CHARS)
    }
}
