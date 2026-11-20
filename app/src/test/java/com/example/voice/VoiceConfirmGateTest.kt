package com.example.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceConfirmGateTest {

    @Test
    fun singleConfirmsOnYes() {
        val gate = VoiceConfirmGate()
        val first = gate.begin(ConfirmLevel.SINGLE, VoiceConfirmPrompts.single("Ramesh complete kar du?"))
        assertTrue(gate.isActive)
        assertEquals("Ramesh complete kar du?", first)
        assertEquals(ConfirmStep.Confirmed, gate.feed("haan"))
        assertFalse(gate.isActive)
    }

    @Test
    fun doubleRequiresTwoYes() {
        val gate = VoiceConfirmGate()
        gate.begin(ConfirmLevel.DOUBLE, VoiceConfirmPrompts.double("Local data delete kar du?"))
        assertEquals(ConfirmStep.Ask("Pakka? fir se haan bolo."), gate.feed("haan"))
        assertTrue(gate.isActive)
        assertEquals(ConfirmStep.Confirmed, gate.feed("haan"))
        assertFalse(gate.isActive)
    }

    @Test
    fun noCancelsAtAnyStep() {
        val gate = VoiceConfirmGate()
        gate.begin(ConfirmLevel.DOUBLE, VoiceConfirmPrompts.double("Local data delete kar du?"))
        assertEquals(ConfirmStep.Cancelled, gate.feed("nahi"))
        assertFalse(gate.isActive)

        gate.begin(ConfirmLevel.DOUBLE, VoiceConfirmPrompts.double("Local data delete kar du?"))
        gate.feed("haan")
        assertEquals(ConfirmStep.Cancelled, gate.feed("nahi"))
        assertFalse(gate.isActive)
    }

    @Test
    fun tripleIgnoresBareYesOnFinalStep() {
        val gate = VoiceConfirmGate()
        gate.begin(ConfirmLevel.TRIPLE, VoiceConfirmPrompts.cloudDelete())
        gate.feed("haan") // step 1
        gate.feed("haan") // step 2
        // final step: a bare "haan" must NOT confirm — exact phrase required
        val s = gate.feed("haan")
        assertTrue(s is ConfirmStep.RetryExactPhrase)
        assertTrue(gate.isActive)
    }

    @Test
    fun tripleConfirmsOnlyOnExactPhrase() {
        val gate = VoiceConfirmGate()
        gate.begin(ConfirmLevel.TRIPLE, VoiceConfirmPrompts.cloudDelete())
        gate.feed("haan")
        gate.feed("haan")
        assertEquals(ConfirmStep.Confirmed, gate.feed("haan delete karo"))
        assertFalse(gate.isActive)
    }

    @Test
    fun tripleAcceptsYesDelete() {
        val gate = VoiceConfirmGate()
        gate.begin(ConfirmLevel.TRIPLE, VoiceConfirmPrompts.cloudDelete())
        gate.feed("yes")
        gate.feed("yes")
        assertEquals(ConfirmStep.Confirmed, gate.feed("yes delete"))
    }

    @Test
    fun tripleRejectsNearMissPhrase() {
        val gate = VoiceConfirmGate()
        gate.begin(ConfirmLevel.TRIPLE, VoiceConfirmPrompts.cloudDelete())
        gate.feed("haan")
        gate.feed("haan")
        assertTrue(gate.feed("haan delete kar do") is ConfirmStep.RetryExactPhrase)
        assertTrue(gate.feed("delete karo") is ConfirmStep.RetryExactPhrase)
        assertTrue(gate.feed("yes delete please") is ConfirmStep.RetryExactPhrase)
        assertTrue(gate.isActive)
    }

    @Test
    fun tripleNoCancels() {
        val gate = VoiceConfirmGate()
        gate.begin(ConfirmLevel.TRIPLE, VoiceConfirmPrompts.cloudDelete())
        gate.feed("haan")
        gate.feed("haan")
        assertEquals(ConfirmStep.Cancelled, gate.feed("nahi"))
        assertFalse(gate.isActive)
    }

    @Test
    fun tripleAcceptsThePhraseInIndicScripts() {
        // Speech recognition returns Devanagari/Tamil/Nastaliq text on those
        // phones even when the user copied a roman prompt.
        val phrases = listOf("हाँ डिलीट करो", "हां डिलीट करो", "ہاں ڈیلیٹ کرو", "ஆம் நீக்கு")
        for (phrase in phrases) {
            val gate = VoiceConfirmGate()
            gate.begin(ConfirmLevel.TRIPLE, VoiceConfirmPrompts.cloudDelete())
            gate.feed("haan")
            gate.feed("haan")
            assertEquals("phrase '$phrase' must confirm", ConfirmStep.Confirmed, gate.feed(phrase))
        }
    }

    @Test
    fun tripleStillRejectsIncompleteIndicPhrases() {
        val gate = VoiceConfirmGate()
        gate.begin(ConfirmLevel.TRIPLE, VoiceConfirmPrompts.cloudDelete())
        gate.feed("haan")
        gate.feed("haan")
        assertTrue(gate.feed("हाँ डिलीट") is ConfirmStep.RetryExactPhrase)
        assertTrue(gate.feed("डिलीट करो") is ConfirmStep.RetryExactPhrase)
        assertTrue(gate.isActive)
    }

    @Test
    fun negationWinsAffirmation() {
        val gate = VoiceConfirmGate()
        gate.begin(ConfirmLevel.SINGLE, VoiceConfirmPrompts.single("complete kar du?"))
        assertEquals(ConfirmStep.Cancelled, gate.feed("nahi kar do"))
    }
}
