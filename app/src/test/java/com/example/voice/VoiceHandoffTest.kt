package com.example.voice

import com.example.voice.android.VoiceHandoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceHandoffTest {

    private val postedAt = 1_000_000L

    @Test
    fun aJustHeardQuestionIsFresh() {
        assertTrue(VoiceHandoffTiming.isFresh(postedAt, postedAt, VoiceHandoffTiming.MAX_AGE_MS))
        assertTrue(VoiceHandoffTiming.isFresh(postedAt, postedAt + 5_000L))
    }

    @Test
    fun anOldQuestionIsStale() {
        assertFalse(
            VoiceHandoffTiming.isFresh(
                postedAt,
                postedAt + VoiceHandoffTiming.MAX_AGE_MS + 1L
            )
        )
    }

    @Test
    fun noPromptAtAllIsNeverFresh() {
        assertFalse(VoiceHandoffTiming.isFresh(0L, postedAt))
    }

    @Test
    fun aBackwardsClockDoesNotDropAFreshQuestion() {
        assertTrue(VoiceHandoffTiming.isFresh(postedAt, postedAt - 500L))
    }

    @Test
    fun thePendingPromptIsSentOnceAndOnlyOnce() {
        VoiceHandoff.nowProvider = { postedAt + 1_000L }
        VoiceHandoff.post("kal kisko call karun")

        assertEquals("kal kisko call karun", VoiceHandoff.consumeIfFresh())
        assertNull(VoiceHandoff.consumeIfFresh())
        VoiceHandoff.clear()
    }

    @Test
    fun aStalePromptIsDroppedInsteadOfSent() {
        VoiceHandoff.nowProvider = { postedAt }
        VoiceHandoff.post("purana sawaal")

        VoiceHandoff.nowProvider = { postedAt + VoiceHandoffTiming.MAX_AGE_MS + 5_000L }
        assertNull("a question asked long ago must not fire now", VoiceHandoff.consumeIfFresh())
        assertNull(VoiceHandoff.consumeIfFresh())
        VoiceHandoff.clear()
    }

    @Test
    fun aBlankPromptIsNotPosted() {
        VoiceHandoff.nowProvider = { postedAt }
        VoiceHandoff.post("   ")
        assertNull(VoiceHandoff.consumeIfFresh())
        assertEquals(null, VoiceHandoff.prompts.value)
        VoiceHandoff.clear()
    }

    @Test
    fun postingAgainReplacesTheOlderQuestion() {
        VoiceHandoff.nowProvider = { postedAt }
        VoiceHandoff.post("pehla sawaal")
        VoiceHandoff.nowProvider = { postedAt + 60_000L }
        VoiceHandoff.post("dusra sawaal")

        assertEquals("dusra sawaal", VoiceHandoff.consumeIfFresh())
        VoiceHandoff.clear()
    }
}
