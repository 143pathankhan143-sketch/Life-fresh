package com.example.ai.chat.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import com.example.data.AppLanguage
import com.example.data.AppLanguageManager
import java.util.Locale

/**
 * Text-to-speech for AI replies so users who cannot read can use the app by
 * ear. Wraps the phone's built-in TTS engine (no API key, no network).
 * Engine init is asynchronous, so one pending utterance is flushed once the
 * engine becomes ready. A per-call onFinished callback powers the Bolo-Mode
 * loop (speak -> listen -> act); a safety timer fires it even if the engine
 * never reports completion.
 */
object AiTts {
    private const val TAG = "AiTts"
    private const val MAX_VOICE_LEN = 700

    private var engine: TextToSpeech? = null
    private var ready = false
    private var pendingText: String? = null
    private var onDone: (() -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var doneFallback: Runnable? = null

    fun ensure(context: Context) {
        if (engine != null) return
        synchronized(this) {
            if (engine != null) return
            engine = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    applyVoicePrefs(context)
                    engine?.setOnUtteranceProgressListener(
                        object : android.speech.tts.UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) = Unit
                            override fun onDone(utteranceId: String?) = fireDone()
                            @Deprecated("Required override for older engines")
                            override fun onError(utteranceId: String?) = fireDone()
                            override fun onError(utteranceId: String?, errorCode: Int) = fireDone()
                        }
                    )
                    pendingText?.let { speakNow(it) }
                    pendingText = null
                } else {
                    Log.w(TAG, "TextToSpeech init failed (status=$status)")
                }
            }
        }
    }

    private fun applyVoicePrefs(context: Context) {
        val tts = engine ?: return
        val lang = try {
            AppLanguageManager.getLanguage(context)
        } catch (e: Exception) {
            AppLanguage.ENGLISH
        }
        val locale = when (lang) {
            AppLanguage.HINDI -> Locale("hi", "IN")
            AppLanguage.TAMIL -> Locale("ta", "IN")
            AppLanguage.URDU -> Locale("ur", "PK")
            else -> Locale("en", "IN")
        }
        try {
            val res = tts.setLanguage(locale)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.US)
            }
            tts.setSpeechRate(0.94f)
            tts.setPitch(1.0f)
        } catch (e: Exception) {
            Log.w(TAG, "TTS locale setup failed", e)
        }
    }

    fun speak(context: Context, raw: String, onFinished: (() -> Unit)? = null) {
        ensure(context)
        val text = cleanForVoice(raw)
        if (text.isBlank()) {
            onFinished?.invoke()
            return
        }
        synchronized(this) {
            onDone = onFinished
            if (!ready) {
                pendingText = text
                return
            }
        }
        speakNow(text)
    }

    private fun speakNow(text: String) {
        val tts = engine ?: run {
            fireDone()
            return
        }
        try {
            clearFallback()
            val r = Runnable { fireDone() }
            doneFallback = r
            mainHandler.postDelayed(r, (text.length * 95L).coerceAtMost(45_000L))
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lifefresh-reply")
        } catch (e: Exception) {
            Log.w(TAG, "TTS speak failed", e)
            fireDone()
        }
    }

    fun stop() {
        try {
            engine?.stop()
        } catch (_: Exception) {
        }
    }

    private fun fireDone() {
        clearFallback()
        val cb = onDone
        onDone = null
        cb?.invoke()
    }

    private fun clearFallback() {
        doneFallback?.let { mainHandler.removeCallbacks(it) }
        doneFallback = null
    }

    /** Markdown/emoji scrub before speaking; long replies are trimmed by words. */
    fun cleanForVoice(src: String): String {
        var t = src
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("[*_`#>|]"), " ")
            .replace(Regex("[\\uD800-\\uDFFF]"), "")
            .replace(Regex("[\\u2190-\\u2BFF\\uFE0F\\u200D]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (t.length > MAX_VOICE_LEN) {
            t = t.substring(0, MAX_VOICE_LEN)
            val cut = t.lastIndexOf(' ').coerceAtLeast(0)
            t = t.substring(0, cut) + "…"
        }
        return t
    }
}

/**
 * Tiny yes/no detector for the Bolo-Mode confirmation ("haan bolo ya nahi").
 * Negation wins over affirmation so "nahi kar do" is read as NO. Unknown
 * phrases are not answers - the caller forwards them as a normal message.
 */
object VoiceWordMatcher {

    private val NEG_WORDS = setOf(
        "nahi", "nahin", "nhi", "nah", "no", "not", "rehne", "rehna", "rehne",
        "chhod", "chhodo", "chhoddo", "cancel", "mat", "mt", "venda", "venam",
        "illa", "yillai", "नहीं", "नही", "मत", "छोड़", "வேண்டாம்", "இல்லை",
        "نہیں", "مت", "رہنے"
    )

    private val POS_WORDS = setOf(
        "haan", "haanji", "haa", "han", "ha", "yes", "yess", "ya", "ok", "okay",
        "okey", "thik", "theek", "ठीक", "sahi", "sure", "bilkul", "ji", "kar", "karo",
        "kardo", "lagao", "am", "ama", "sari", "हाँ", "हां", "जी", "कर",
        "करो", "ஆம்", "சரி", "أوكي", "ٹھیک", "جی", "ہاں"
    )

    fun isNegation(text: String): Boolean = tokens(text).any { it in NEG_WORDS }

    fun isAffirmation(text: String): Boolean {
        if (isNegation(text)) return false
        return tokens(text).any { it in POS_WORDS }
    }

    private fun tokens(text: String): List<String> =
        text.lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}]+"))
            .filter { it.isNotBlank() }
}
