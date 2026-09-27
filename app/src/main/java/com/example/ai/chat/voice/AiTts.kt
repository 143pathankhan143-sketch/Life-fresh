package com.example.ai.chat.voice

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.example.data.AppLanguage
import com.example.data.AppLanguageManager
import java.util.ArrayDeque
import java.util.Locale

/**
 * Text-to-speech for AI replies so users who cannot read can use the app by
 * ear. Wraps the phone's built-in TTS engine (no API key, no network).
 *
 * RELIABILITY (fix for "the Android voice speaks one or two answers and then
 * stays silent while the chat keeps working", 2026-09-27):
 *
 *  1. [TextToSpeech.speak]'s return value is checked. An `ERROR` return means
 *     the utterance was dropped without ANY callback (no onStart, no onDone,
 *     no onError) — the old code waited for a callback that never came, faked
 *     completion and left the user in silence without a trace. An `ERROR` now
 *     rebuilds the engine and speaks the text again.
 *  2. `onStart` is tracked and a watchdog re-initialises the engine + retries
 *     once when an utterance never starts ([START_WATCHDOG_MS]).
 *  3. The engine is rebuilt from scratch ([resetEngine] + a fresh [TextToSpeech])
 *     whenever it is wedged, disconnected or reports an error, instead of dying
 *     for the rest of the app session.
 *  4. Long replies are split into engine-sized chunks spoken in order, so one
 *     oversized sentence can never silence the rest of the answer.
 *  5. [stop] records when it happened; a speak() issued in the same instant is
 *     delayed slightly, because several engines drop an utterance that arrives
 *     while a stop() is still being processed (the barge-in race).
 */
object AiTts {
    private const val TAG = "AiTts"
    private const val MAX_VOICE_LEN = 700
    private const val START_WATCHDOG_MS = 2_500L
    private const val RETRY_DELAY_MS = 180L
    private const val AFTER_STOP_DELAY_MS = 120L
    private const val MAX_ATTEMPTS = 2
    private const val FALLBACK_CHUNK_CHARS = 3_500

    private var engine: TextToSpeech? = null
    private var ready = false

    /** True while an utterance is really being spoken (or queued to speak). */
    @Volatile
    private var speaking = false

    /** True once the current utterance reported onStart. */
    @Volatile
    private var startedCurrent = false

    /** True when the phone has no voice data for the app language. */
    @Volatile
    private var languageMissing = false

    /** Fired on the main thread when voice data is missing, so the screen
     *  can point the user at the voice-pack install (or the free cloud
     *  voice in Settings) instead of hearing English-accent gibberish. */
    @Volatile
    var onVoiceUnavailable: (() -> Unit)? = null

    private var appContext: Context? = null
    private var appliedLanguageTag: String? = null
    private var pendingText: String? = null
    private var onDone: (() -> Unit)? = null
    private var chunks: ArrayDeque<String> = ArrayDeque()
    private var currentText: String? = null
    private var attempts = 0
    private var utteranceSeq = 0
    private var currentUtteranceId: String? = null
    private var lastStopAtMs = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var doneFallback: Runnable? = null
    private var startWatchdog: Runnable? = null

    fun ensure(context: Context) {
        appContext = context.applicationContext
        if (engine != null) return
        synchronized(this) {
            if (engine != null) return
            createEngine(context.applicationContext)
        }
    }

    /** True while the device engine is speaking/queued for this app. */
    fun isSpeaking(): Boolean =
        speaking || currentText != null || pendingText != null

    /** Rebuilds the engine from scratch after a crash/disconnect. */
    private fun createEngine(context: Context) {
        val appContextResolved = context.applicationContext
        var created: TextToSpeech? = null
        try {
            created = TextToSpeech(appContextResolved) { status ->
                // Posted: the constructor must finish assigning `created` first
                // (an engine can call back synchronously on some devices).
                mainHandler.post { onEngineReady(created, appContextResolved, status) }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "TextToSpeech construction failed", e)
            created = null
        }
        engine = created
    }

    private fun onEngineReady(tts: TextToSpeech?, context: Context, status: Int) {
        if (tts == null || engine !== tts) return // replaced meanwhile
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TextToSpeech init failed (status=$status)")
            ready = false
            return
        }
        ready = true
        appliedLanguageTag = null
        applyVoicePrefs(context)
        try {
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceId != currentUtteranceId) return
                    startedCurrent = true
                    speaking = true
                    clearStartWatchdog()
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId != currentUtteranceId) return
                    speaking = false
                    clearFallback()
                    clearStartWatchdog()
                    speakNextChunk()
                }

                @Deprecated("Required override for older engines")
                override fun onError(utteranceId: String?) = onChunkFailed()

                override fun onError(utteranceId: String?, errorCode: Int) = onChunkFailed()
            })
        } catch (e: Exception) {
            Log.w(TAG, "listener wiring failed", e)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                tts.setOnServiceDisconnectedListener {
                    Log.w(TAG, "TTS service disconnected - rebuilding engine")
                    onEngineDied(context)
                }
            } catch (_: Throwable) {
            }
        }
        // A reply asked for before the engine was ready.
        val text = pendingText
        pendingText = null
        if (text != null) {
            chunks = ArrayDeque(splitForEngine(text))
            attempts = 0
            speakNextChunk()
        }
    }

    /** Engine died (service disconnected) - rebuild it so the next line works. */
    private fun onEngineDied(context: Context) {
        val wasSpeaking = isSpeaking()
        val remaining = joinRemaining(currentText)
        resetEngine()
        ready = false
        createEngine(context)
        if (wasSpeaking && remaining != null) pendingText = remaining
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
        val tag = locale.toLanguageTag()
        if (appliedLanguageTag == tag) return // already set for this language
        try {
            var res = tts.setLanguage(locale)
            if ((res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) &&
                lang == AppLanguage.ENGLISH
            ) {
                // English text reads fine on any English voice pack.
                res = tts.setLanguage(Locale.US)
            }
            tts.setSpeechRate(0.94f)
            tts.setPitch(1.0f)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Hindi/Tamil/Urdu read by an English voice is gibberish -
                // stay silent and tell the user how to fix it instead.
                languageMissing = true
                appliedLanguageTag = tag
                pendingText = null
                fireDone()
                mainHandler.post { onVoiceUnavailable?.invoke() }
                return
            }
            languageMissing = false
            appliedLanguageTag = tag
            // Use the best voice the device installed for this language instead
            // of whatever the engine happens to default to (often a low-quality
            // local voice even when a natural one is installed).
            pickBestVoice(tts, locale)?.let { best ->
                try {
                    tts.voice = best
                    Log.d(TAG, "voice: ${best.name} (quality=${best.quality})")
                } catch (e: Exception) {
                    Log.w(TAG, "setVoice failed", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TTS locale setup failed", e)
        }
    }

    /**
     * Highest-quality installed voice for [locale], preferring one that also
     * matches the country, then a network (usually the natural-sounding) voice.
     */
    private fun pickBestVoice(tts: TextToSpeech, locale: Locale): Voice? = try {
        tts.voices
            ?.filter { it.locale.language.equals(locale.language, ignoreCase = true) }
            ?.takeIf { it.isNotEmpty() }
            ?.sortedWith(
                compareByDescending<Voice> { it.quality }
                    .thenByDescending { it.locale.country.equals(locale.country, ignoreCase = true) }
                    .thenByDescending { it.isNetworkConnectionRequired }
            )
            ?.firstOrNull()
    } catch (e: Exception) {
        null
    }

    fun speak(context: Context, raw: String, onFinished: (() -> Unit)? = null) {
        ensure(context)
        val text = cleanForVoice(raw)
        if (text.isBlank()) {
            onFinished?.invoke()
            return
        }
        // Re-evaluate the voice for the current app language on every reply
        // (activity recreation/language switch + voice-pack install).
        if (ready) applyVoicePrefs(context)
        if (languageMissing) {
            onFinished?.invoke()
            return
        }
        synchronized(this) {
            onDone = onFinished
            attempts = 0
            currentText = null
            chunks = ArrayDeque()
            if (!ready) {
                // The engine is still starting (or being rebuilt): flush on ready.
                pendingText = text
                return
            }
        }
        chunks = ArrayDeque(splitForEngine(text))
        speakNextChunk()
    }

    /** Speaks the next chunk, or finishes when the reply has been read out. */
    private fun speakNextChunk() {
        if (chunks.isEmpty()) {
            fireDone()
            return
        }
        currentText = chunks.removeFirst()
        attempts = 0
        speakCurrentChunk()
    }

    private fun speakCurrentChunk() {
        val text = currentText
        if (text == null) {
            speakNextChunk()
            return
        }
        val tts = engine
        val context = appContext
        if (tts == null || !ready) {
            // Engine gone/not ready: rebuild and keep the text so the ready
            // callback can speak it instead of losing the reply.
            if (context != null && !ready) {
                pendingText = joinRemaining(text)
                if (tts == null) {
                    synchronized(this) {
                        if (engine == null) createEngine(context)
                    }
                }
            } else {
                fireDone()
            }
            return
        }
        // An utterance issued in the same instant as stop() is dropped by
        // several engines - wait out that window first.
        val sinceStop = System.currentTimeMillis() - lastStopAtMs
        if (sinceStop in 0 until AFTER_STOP_DELAY_MS) {
            mainHandler.postDelayed(
                { speakCurrentChunk() },
                AFTER_STOP_DELAY_MS - sinceStop + 20L
            )
            return
        }

        clearFallback()
        clearStartWatchdog()
        startedCurrent = false
        val id = "lifefresh-reply-${++utteranceSeq}"
        currentUtteranceId = id
        val result = try {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        } catch (e: Exception) {
            Log.w(TAG, "TTS speak() threw", e)
            TextToSpeech.ERROR
        }

        if (result == TextToSpeech.ERROR) {
            // No callback follows an ERROR return - this is the exact way the
            // voice used to die silently. Rebuild and speak it again.
            attempts++
            speaking = false
            Log.w(TAG, "TTS speak() returned ERROR (attempt $attempts) - rebuilding engine")
            if (attempts < MAX_ATTEMPTS && context != null) {
                resetEngine()
                ready = false
                createEngine(context)
                pendingText = joinRemaining(text)
            } else {
                speakNextChunk()
            }
            return
        }

        speaking = true

        // Watchdog: accepted but never started (engine wedged).
        val watchdog = Runnable {
            if (currentUtteranceId == id && !startedCurrent) {
                Log.w(TAG, "TTS never started - rebuilding engine")
                speaking = false
                attempts++
                if (attempts < MAX_ATTEMPTS && context != null) {
                    resetEngine()
                    ready = false
                    createEngine(context)
                    pendingText = joinRemaining(text)
                } else {
                    speakNextChunk()
                }
            }
        }
        startWatchdog = watchdog
        mainHandler.postDelayed(watchdog, START_WATCHDOG_MS)

        // Watchdog: playback callback lost after a successful start.
        val fallback = Runnable {
            if (currentUtteranceId == id) {
                speaking = false
                speakNextChunk()
            }
        }
        doneFallback = fallback
        mainHandler.postDelayed(fallback, estimatedMs(text) + START_WATCHDOG_MS)
    }

    /** A chunk failed (engine error callback): retry once, then move on. */
    private fun onChunkFailed() {
        speaking = false
        clearFallback()
        clearStartWatchdog()
        val context = appContext
        if (attempts < MAX_ATTEMPTS && context != null) {
            attempts++
            mainHandler.postDelayed({ speakCurrentChunk() }, RETRY_DELAY_MS)
        } else {
            // Do not let one bad sentence silence the whole reply.
            speakNextChunk()
        }
    }

    fun stop() {
        // Barge-in must also drop anything queued but not yet speaking: a
        // slow engine init otherwise speaks a stale (older reply) chunk long
        // after the user moved on to the next question (the "A1 during Q3"
        // bug). Clearing pendingText + onDone here means a late engine-ready
        // or safety-timer callback has nothing stale to fire.
        lastStopAtMs = System.currentTimeMillis()
        synchronized(this) {
            pendingText = null
            chunks = ArrayDeque()
            currentText = null
            currentUtteranceId = null
            val cb = onDone
            onDone = null
            mainHandler.post { cb?.invoke() }
        }
        speaking = false
        clearFallback()
        clearStartWatchdog()
        try {
            engine?.stop()
        } catch (_: Exception) {
        }
    }

    private fun resetEngine() {
        val old = engine
        engine = null
        ready = false
        speaking = false
        startedCurrent = false
        appliedLanguageTag = null
        currentUtteranceId = null
        clearFallback()
        clearStartWatchdog()
        try {
            old?.stop()
        } catch (_: Exception) {
        }
        try {
            old?.shutdown()
        } catch (_: Exception) {
        }
    }

    private fun fireDone() {
        clearFallback()
        clearStartWatchdog()
        speaking = false
        currentText = null
        chunks = ArrayDeque()
        val cb = onDone
        onDone = null
        cb?.invoke()
    }

    private fun clearFallback() {
        doneFallback?.let { mainHandler.removeCallbacks(it) }
        doneFallback = null
    }

    private fun clearStartWatchdog() {
        startWatchdog?.let { mainHandler.removeCallbacks(it) }
        startWatchdog = null
    }

    /** Everything still waiting to be spoken (used when the engine restarts). */
    private fun joinRemaining(text: String?): String? {
        val rest = chunks.joinToString(" ").trim()
        val head = text?.trim().orEmpty()
        chunks = ArrayDeque()
        return when {
            head.isBlank() && rest.isBlank() -> null
            rest.isBlank() -> head
            head.isBlank() -> rest
            else -> "$head $rest"
        }
    }

    private fun estimatedMs(text: String): Long =
        (text.length * 95L).coerceIn(1_500L, 45_000L)

    /**
     * Splits a long reply into pieces the engine accepts. Sentence boundaries
     * first, word boundaries as the fallback.
     */
    private fun splitForEngine(text: String): List<String> {
        val limit = try {
            (TextToSpeech.getMaxSpeechInputLength() - 200).coerceIn(500, FALLBACK_CHUNK_CHARS)
        } catch (e: Throwable) {
            FALLBACK_CHUNK_CHARS
        }
        if (text.length <= limit) return listOf(text)
        val out = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotBlank()) {
                out.add(current.toString().trim())
                current.setLength(0)
            }
        }
        val delimiters = Regex("(?<=[।.!?\\n])\\s+")
        for (sentence in text.split(delimiters)) {
            if (sentence.length > limit) {
                flush()
                var wordCurrent = StringBuilder()
                for (word in sentence.split(Regex("\\s+"))) {
                    if (wordCurrent.length + word.length + 1 > limit && wordCurrent.isNotEmpty()) {
                        out.add(wordCurrent.toString())
                        wordCurrent = StringBuilder()
                    } else {
                        if (wordCurrent.isNotEmpty()) wordCurrent.append(' ')
                        wordCurrent.append(word)
                    }
                }
                if (wordCurrent.isNotBlank()) out.add(wordCurrent.toString())
                continue
            }
            if (current.length + sentence.length + 1 > limit) flush()
            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence)
        }
        flush()
        return out.ifEmpty { listOf(text.take(limit)) }
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

    // Combining marks stay with their base letter, so Hindi "नहीं"/"हाँ" and
    // Tamil/Urdu words are single tokens instead of being chopped at the matra.
    private fun tokens(text: String): List<String> =
        text.lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{M}]+"))
            .filter { it.isNotBlank() }
}
