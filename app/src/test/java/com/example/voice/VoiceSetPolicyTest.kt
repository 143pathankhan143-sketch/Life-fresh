package com.example.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the user's voice decision (2026-09-27): the app offers exactly TWO AI
 * voices - one female, one male - plus the phone's own engine. Anything else
 * (28 removed studio voices, "auto", a retired name) must not come back, and an
 * old stored choice must degrade to the female voice instead of going silent.
 */
class VoiceSetPolicyTest {

    @Test
    fun exactlyOneFemaleAndOneMaleVoice() {
        assertEquals(2, VoiceIds.ALL.size)
        assertEquals(listOf("Kore", "Orus"), VoiceIds.ALL)
        assertEquals("Kore", VoiceIds.FEMALE)
        assertEquals("Orus", VoiceIds.MALE)
    }

    @Test
    fun theRemovedStudioVoicesAreGone() {
        val removed = listOf(
            "Charon", "Puck", "Zephyr", "Fenrir", "Leda", "Aoede", "Callirrhoe",
            "Autonoe", "Enceladus", "Iapetus", "Umbriel", "Algieba", "Despina",
            "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar", "Alnilam",
            "Schedar", "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi",
            "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat", "auto"
        )
        for (name in removed) {
            assertFalse("'$name' must not be offered", VoiceIds.ALL.contains(name))
        }
    }

    @Test
    fun anOldStoredChoiceFallsBackToTheFemaleVoice() {
        // Installs that had picked any of the removed voices (or "auto") must
        // still speak - with the stable default - instead of sending an
        // unknown name the TTS API answers with HTTP 400.
        for (name in listOf("auto", "Charon", "sulafat", "", "   ", "nonsense")) {
            assertEquals("'$name' must normalise to the female voice", VoiceIds.FEMALE, VoiceIds.normalise(name))
        }
    }

    @Test
    fun normaliseKeepsTheTwoOfferedVoicesAndIgnoresCase() {
        assertEquals(VoiceIds.FEMALE, VoiceIds.normalise("Kore"))
        assertEquals(VoiceIds.FEMALE, VoiceIds.normalise("kore"))
        assertEquals(VoiceIds.MALE, VoiceIds.normalise("Orus"))
        assertEquals(VoiceIds.MALE, VoiceIds.normalise("orus"))
        assertEquals(VoiceIds.MALE, VoiceIds.normalise("  Orus  "))
    }

    @Test
    fun onlyTwoVoicesAreEverTriedInTheLoop() {
        // The picker, the cloud client and the parser all read VoiceIds.ALL, so
        // a stray name can never reach the speech API.
        val probes = listOf(
            "Kore awaz lagao", "orus awaz lagao", "male awaz lagao",
            "mahila awaz lagao", "आदमी आवाज़ लगाओ", "ஆண் குரல்", "مرد کی آواز",
            "awaz badlo", "charon awaz lagao", "sulafat awaz lagao"
        )
        for (probe in probes) {
            val command = VoiceCommandParser.parse(probe, VoiceIds.ALL.toSet())
            assertTrue("'$probe' must be a voice command, was $command", command is VoiceCommand.SetVoice)
            val name = (command as VoiceCommand.SetVoice).voiceName
            assertTrue(
                "'$probe' produced '$name' which is not an offered voice",
                name.isBlank() || name in VoiceIds.ALL
            )
        }
        val texts = VoiceTextDefaults.hinglish()
        for (key in listOf(VoiceTextKeys.VOICE_HINT, VoiceTextKeys.VOICE_SET_FEMALE, VoiceTextKeys.VOICE_SET_MALE)) {
            assertTrue("$key must have a spoken line", texts.get(key).isNotBlank())
        }
    }
}
