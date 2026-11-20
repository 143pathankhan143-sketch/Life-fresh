package com.example.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "answer stops in the middle" bug (2026-09-27): a normal CRM reply is
 * 800+ characters, but the voice trimmed every reply to 700 - so the tail of the
 * answer ("Weekly summary, today's plan ... Agar koi specific kaam chahiye, bas
 * bata dijiye!") was never spoken and the app looked like it had gone quiet.
 */
class VoiceTextLimitsTest {

    /** The real reply from the user's device, 805 characters long. */
    private val realReply = """Main aapki CRM (LifeFresh QuickNote Pro) mein yeh kar sakta hoon:

Lead add/complete: Naya lead add kar sakte hain, ya maujooda lead ko complete/pending set kar sakte hain.

Lead update: Phone number, relation, disease, reminder, call log, ya note update kar sakte hain.

Draft handling: Draft leads ko dekh sakte hain aur unko complete kar sakte hain.

Reminders: Specific dates/times aur repeat options (daily, weekly, monthly) ke saath reminder set ya delete kar sakte hain.

WhatsApp: Lead ko WhatsApp message bhejne ka link khol sakte hain.

Delete / Archive: Soft delete (archive) ya permanent delete (agar already archived) kar sakte hain.

Reports & summaries: Weekly summary, today's plan, cold leads, etc. ke baare mein quick info de sakta hoon.

Agar koi specific kaam chahiye, bas bata dijiye!"""

    @Test
    fun theRealReplyIsLongerThanTheOldTruncationPoint() {
        // Guards the premise of this test suite: if this reply were shorter than
        // 700 characters the old code would have spoken it completely.
        assertTrue("fixture is only ${realReply.length} chars", realReply.length > 700)
    }

    @Test
    fun anEightHundredCharacterReplyIsSpokenCompletely() {
        val spoken = VoiceTextLimits.truncateForSpeech(realReply)
        assertEquals("no character of a normal reply may be dropped", realReply, spoken)
        assertTrue("the tail must survive", spoken.endsWith("bas bata dijiye!"))
    }

    @Test
    fun theLimitIsFarAboveAnyRealAnswer() {
        assertTrue(
            "a limit this low would cut normal answers again",
            VoiceTextLimits.MAX_SPEAKABLE_CHARS >= 4_000
        )
    }

    @Test
    fun onlyAbsurdInputIsTrimmedAndItSoundsUnfinished() {
        val huge = (1..4_000).joinToString(" ") { "word$it" }
        val spoken = VoiceTextLimits.truncateForSpeech(huge)
        assertTrue(spoken.length <= VoiceTextLimits.MAX_SPEAKABLE_CHARS + 1)
        assertTrue("a trim must be audible", spoken.endsWith("…"))
        assertTrue("no half word at the end", !spoken.removeSuffix("…").endsWith("wor"))
    }

    @Test
    fun textWithoutSpacesIsStillBounded() {
        val spoken = VoiceTextLimits.truncateForSpeech("x".repeat(20_000))
        assertEquals(VoiceTextLimits.MAX_SPEAKABLE_CHARS + 1, spoken.length)
    }

    @Test
    fun hinglishLatinTextIsRecognised() {
        // Used to let the English cloud voice read a Hinglish reply when the
        // phone's own engine is broken and there is no Gemini key.
        assertTrue(VoiceTextLimits.looksLatin("Main aapki CRM mein yeh kar sakta hoon, bataiye!"))
        assertTrue(VoiceTextLimits.looksLatin("Reports & summaries: weekly summary, today's plan."))
    }

    @Test
    fun devanagariTamilAndUrduTextIsNotLatin() {
        assertTrue(!VoiceTextLimits.looksLatin("मैं आपकी सीआरएम में यह कर सकता हूँ, बताइए"))
        assertTrue(!VoiceTextLimits.looksLatin("நான் உங்கள் சிஆர்எம் இல் இதை செய்ய முடியும்"))
        assertTrue(!VoiceTextLimits.looksLatin("میں آپ کی سی آر ایم میں یہ کر سکتا ہوں"))
    }

    @Test
    fun veryShortTextIsNotJudgedByScript() {
        // "ok" is legitimate but too short to classify - fall back to the
        // language rule rather than guessing from two letters.
        assertTrue(!VoiceTextLimits.looksLatin("ok"))
        assertTrue(!VoiceTextLimits.looksLatin(""))
    }

    @Test
    fun digitsAndPunctuationDoNotSkewTheRatio() {
        assertTrue(VoiceTextLimits.looksLatin("Lead add karo 12345 !!! --- email@example.com"))
    }

    @Test
    fun aZeroOrNegativeLimitIsIgnoredInsteadOfSilencingEverything() {
        val spoken = VoiceTextLimits.truncateForSpeech("hello there", maxLen = 0)
        assertEquals("hello there", spoken)
    }
}
