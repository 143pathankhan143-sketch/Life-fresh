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
    fun theDeviceEngineUsesOnlyPublicSdkApis() {
        // TextToSpeech.setOnServiceDisconnectedListener() is a hidden @SystemApi.
        // It compiles in unit tests (no android.jar) but failed the real release
        // build with "Unresolved reference", so it must never come back.
        val tts = read("app/src/main/java/com/example/ai/chat/voice/AiTts.kt")
        assertFalse(
            "hidden @SystemApi would break the release build",
            tts.contains("setOnServiceDisconnectedListener")
        )
        // onServiceConnected / onServiceDisconnected callbacks are equally hidden.
        assertFalse(tts.contains("onServiceDisconnected"))
        assertFalse(tts.contains("onServiceConnected"))
        // The public mechanisms must stay in place instead.
        assertTrue(tts.contains("START_WATCHDOG_MS"))
        assertTrue(tts.contains("TextToSpeech.ERROR"))
    }

    @Test
    fun theVoiceLayerNeverTrimsAReplyToAShortLimit() {
        // The 700-character trim made normal CRM answers stop in the middle.
        // Speak everything (chunked) - trim only absurd input, via the pure,
        // tested VoiceTextLimits.
        val tts = read("app/src/main/java/com/example/ai/chat/voice/AiTts.kt")
        // A numeric cap declaration is what must never come back (comments may
        // still explain the old bug).
        assertFalse(
            "a small character cap must never return",
            Regex("=\\s*[0-9_]*700\\b").containsMatchIn(tts) ||
                tts.contains("MAX_VOICE_LEN")
        )
        assertTrue(
            "cleanForVoice must use the tested limit object",
            tts.contains("VoiceTextLimits.truncateForSpeech")
        )
        assertTrue(
            "the limit must be far above a real answer",
            VoiceTextLimits.MAX_SPEAKABLE_CHARS >= 4_000
        )
        // Every cloud/speech entry point must go through cleanForVoice (no
        // private copy of an older, smaller trim).
        for (file in listOf(
            "app/src/main/java/com/example/ai/chat/voice/AiVoicePlayer.kt",
            "app/src/main/java/com/example/ai/chat/voice/GeminiTtsClient.kt",
            "app/src/main/java/com/example/ai/chat/voice/GroqTtsClient.kt"
        )) {
            val src = read(file)
            assertTrue("$file must not define its own trim", !src.contains("MAX_VOICE_LEN"))
        }
    }

    @Test
    fun orpheusNeverPlaysHalfAnAnswer() {
        val groq = read("app/src/main/java/com/example/ai/chat/voice/GroqTtsClient.kt")
        // If any 200-char piece fails, the whole chunk must go to the device
        // engine instead - playing the pieces that succeeded would cut the
        // sentence off in the middle (the same class of bug as the 700-char cap).
        assertTrue(
            "a failed piece must return an empty list (device fallback), not a partial one",
            groq.contains("Never play HALF an answer")
        )
    }

    @Test
    fun aSilentEngineCanNeverHangTheVoiceLoop() {
        // The real "only the first two answers are spoken" bug: a wedged engine
        // was rebuilt forever without ever firing the callback, so the caller
        // waited for good - no more listening, and every later answer text-only.
        val tts = read("app/src/main/java/com/example/ai/chat/voice/AiTts.kt")
        assertTrue("must bound the retries", tts.contains("MAX_START_FAILURES"))
        assertTrue("must drop an engine that failed twice", tts.contains("TROUBLE_AFTER_FAILURES"))
        assertTrue("must fail open (fire the callback)", tts.contains("failing open"))
        assertTrue("must bound the wait for a ready engine", tts.contains("READY_TIMEOUT_MS"))
        assertTrue("empty replies must settle between chunks", tts.contains("SETTLE_BETWEEN_UTTERANCES_MS"))
        val player = read("app/src/main/java/com/example/ai/chat/voice/AiVoicePlayer.kt")
        assertTrue("device speech needs a hard budget", player.contains("DEVICE_EXTRA_BUDGET_MS"))
        assertTrue(
            "speakSuspend must report whether audio played",
            player.contains("suspend fun speakSuspend(context: Context, text: String): Boolean")
        )
        assertTrue(
            "a failed piece must tell the caller",
            player.contains("AiTts.estimateSpeechMs(text) + DEVICE_EXTRA_BUDGET_MS")
        )
        val screen = read("app/src/main/java/com/example/ui/screens/AIScreen.kt")
        assertTrue("the screen must gate speech with a budget", screen.contains("withTimeoutOrNull(90_000L)"))
        assertTrue("the user must be told once when the voice is silent", screen.contains("warnIfVoiceSilent"))
    }

    @Test
    fun everyLocaleCarriesTheSilentVoiceHint() {
        for (locale in listOf("values", "values-hi", "values-ta", "values-ur")) {
            val strings = read("app/src/main/res/$locale/strings.xml")
            assertTrue("$locale is missing ai_tts_silent_hint", strings.contains("ai_tts_silent_hint"))
        }
    }

    @Test
    fun theEnglishCloudVoiceMayReadHinglishText() {
        // Groq Orpheus is English-only, but a Hinglish reply is Latin script and
        // is far better read aloud in English than not read at all.
        val groq = read("app/src/main/java/com/example/ai/chat/voice/GroqTtsClient.kt")
        assertTrue("canSpeak must exist", groq.contains("fun canSpeak(context: Context, text: String)"))
        assertTrue("it must accept Latin text", groq.contains("VoiceTextLimits.looksLatin(text)"))
        val player = read("app/src/main/java/com/example/ai/chat/voice/AiVoicePlayer.kt")
        assertTrue("the player must use canSpeak", player.contains("GroqTtsClient.canSpeak(context, text)"))
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
