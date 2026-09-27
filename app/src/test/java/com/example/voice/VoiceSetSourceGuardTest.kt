package com.example.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guards for things a unit test cannot reach from the sandbox
 * (the Android TTS clients). Cheap insurance against a silent regression:
 *  - the cloud client must offer only VoiceIds.ALL,
 *  - the "auto" voice and the removed studio names must not come back,
 *  - the retired Gemini 2.5 TTS model must not be listed again,
 *  - the voice picker must iterate VOICES (not a hardcoded 30-name list),
 *  - MainActivity must wire knownVoices, or a spoken voice name does nothing.
 */
class VoiceSetSourceGuardTest {

    private fun sourceFile(relative: String): File {
        System.getProperty("lifefresh.repo.dir")?.let { repo ->
            val direct = File(repo, relative)
            if (direct.isFile) return direct
        }
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return File(relative)
    }

    private fun read(relative: String): String {
        val file = sourceFile(relative)
        assertTrue("missing ${file.path}", file.isFile)
        return file.readText()
    }

    @Test
    fun cloudVoiceListIsExactlyTheTwoOfferedVoices() {
        val client = read("app/src/main/java/com/example/ai/chat/voice/GeminiTtsClient.kt")
        assertTrue(
            "GeminiTtsClient.VOICES must come from VoiceIds.ALL",
            client.contains("val VOICES: List<String> = VoiceIds.ALL")
        )
        assertFalse("the removed \"auto\" voice must not return", client.contains("\"auto\""))
        for (removed in listOf("Charon", "Puck", "Zephyr", "Sulafat", "Zubenelgenubi")) {
            assertFalse("removed voice $removed is back in the client", client.contains("\"$removed\""))
        }
        // Migration guard: an old stored name must be normalised, not sent.
        assertTrue(client.contains("normaliseVoice"))
        // And the mirror must be gone (models come from AIConfig, all of them).
        assertFalse("the take(2) hack must be gone", client.contains(".take(2)"))
    }

    @Test
    fun retiredTtsModelOnesAreNotListedAgain() {
        val config = read("app/src/main/java/com/example/ai/chat/config/AIConfig.kt")
        // Comments contain brackets, so pull the quoted ids in order instead of
        // slicing text between brackets.
        val after = config.substringAfter("GEMINI_TTS_MODELS")
        val ids = Regex("\"(gemini-[a-z0-9.\\-]+)\"").findAll(after.take(1_500))
            .map { it.groupValues[1] }.toList()
        assertEquals(
            listOf("gemini-3.8-flash-lite-tts", "gemini-3.8-flash-tts", "gemini-3.1-flash-tts-preview"),
            ids
        )
        assertFalse("the closed 2.5 preview model is back", ids.any { it.startsWith("gemini-2.") })
    }

    @Test
    fun theVoicePickerShowsBothVoicesAndTestsThem() {
        val settings = read("app/src/main/java/com/example/ui/screens/settings/SettingsApiKeysScreen.kt")
        assertTrue("picker must iterate VOICES", settings.contains("GeminiTtsClient.VOICES.forEach"))
        assertTrue("picker must label voices", settings.contains("GeminiTtsClient.voiceLabelRes"))
        assertFalse("the removed auto label must not return", settings.contains("ai_voice_auto"))
        assertTrue(
            "each voice needs its own Test button",
            settings.contains("btn_ai_voice_test_\$v")
        )
    }

    @Test
    fun aSpokenVoiceNameIsActuallyWired() {
        val activity = read("app/src/main/java/com/example/MainActivity.kt")
        assertTrue(
            "MainActivity must pass knownVoices, or \"kore awaz lagao\" only opens the picker",
            activity.contains("knownVoices = { GeminiTtsClient.VOICES.toSet() }")
        )
        assertTrue(
            "voice selection must be an enabled capability",
            activity.contains("VoiceCapabilities.CURRENT")
        )
        val controller = read("app/src/main/java/com/example/voice/android/VoiceAppController.kt")
        assertTrue(
            "the controller must execute SetVoice itself",
            controller.contains("private suspend fun executeCommand(command: VoiceCommand)")
        )
        assertTrue(controller.contains("AIQuotaManager.setTtsVoiceName(context, voice)"))
    }

    @Test
    fun theDeviceEngineDefendsAgainstASilentEngine() {
        val tts = read("app/src/main/java/com/example/ai/chat/voice/AiTts.kt")
        // The exact bug: TextToSpeech.speak() returning ERROR without any
        // callback used to end the voice for the whole session.
        assertTrue("speak() must be checked for ERROR", tts.contains("result == TextToSpeech.ERROR"))
        assertTrue("engine must be rebuildable", tts.contains("private fun resetEngine()"))
        assertTrue("watchdog for a never-starting utterance", tts.contains("START_WATCHDOG_MS"))
        assertTrue("long replies must be chunked", tts.contains("splitForEngine"))
        assertTrue("onStart must be tracked", tts.contains("startedCurrent"))
        val player = read("app/src/main/java/com/example/ai/chat/voice/AiVoicePlayer.kt")
        assertTrue(
            "a quiet device engine must not be stopped before speaking",
            player.contains("if (AiTts.isSpeaking()) AiTts.stop()")
        )
    }
}
