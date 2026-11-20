package com.example.ai.chat.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.example.data.AppLanguage
import com.example.data.AppLanguageManager
import com.example.voice.VoiceTextLimits
import java.util.ArrayDeque
import java.util.Locale

/**
 * Text-to-speech for AI replies so users who cannot read can use the app by
 * ear. Wraps the phone's built-in TTS engine (no API key, no network).
 *
 * THE RULE THIS CLASS LIVES BY: **the caller's callback is always fired.**
 * A voice that goes quiet is annoying; a voice that also never tells the loop
 * it finished leaves the whole hands-free mode stuck - no more listening, and
 * every later answer arrives as text only. That was the real "only the first
 * two answers are spoken" bug (2026-09-27), so every failure path below ends in
 * exactly one of: the utterance played, or [fireDone] was called.
 *
 * Reliability ladder:
 *  1. [TextToSpeech.speak]'s return value is checked. `ERROR` means the
 *     utterance was dropped with NO callback at all - rebuild and retry once.
 *  2. `onStart` is tracked; an utterance that never starts within
 *     [START_WATCHDOG_MS] is treated the same way.
 *  3. Fail open: after [MAX_START_FAILURES] failed attempts the engine is
 *     dropped (a wedged engine is useless anyway), the callback fires, and the
 *     NEXT reply starts from a brand-new engine after a short cooldown. There is
 *     no infinite rebuild loop - that loop was the actual bug.
 *  4. A pending reply can never wait forever: [READY_TIMEOUT_MS] caps how long
 *     we wait for an engine that never becomes ready.
 *  5. Long replies are split into engine-sized chunks spoken in order, with a
 *     small pause between them (rapid back-to-back speak() calls wedge several
 *     engines), and a lost-callback watchdog after a successful start.
 */
object AiTts {
    private const val TAG = "AiTts"

    private const val START_WATCHDOG_MS = 2_500L
    private const val READY_TIMEOUT_MS = 4_000L
    private const val SETTLE_BETWEEN_UTTERANCES_MS = 150L
    private const val AFTER_STOP_DELAY_MS = 120L
    private const val RETRY_DELAY_MS = 200L
    private const val FAILURE_COOLDOWN_MS = 400L
    private const val MAX_START_FAILURES = 2
    private const val TROUBLE_AFTER_FAILURES = 2
    private const val FALLBACK_CHUNK_CHARS = 3_500
    private const val MS_PER_CHAR = 95L
    private const val MIN_ESTIMATE_MS = 1_500L
    private const val MAX_ESTIMATE_MS = 60_000L

    private var engine: TextToSpeech? = null
    private var ready = false

    /** True while an utterance is really being spoken (or queued to speak). */
    @Volatile
    private var speaking = false

    /** True once the current utterance reported onStart. */
    @Volatile
    private var startedCurrent = false

    /** Failed starts for the CURRENT utterance (reset per chunk). */
    private var startFailures = 0

    /**
     * Failed starts in a row across utterances. Two in a row means the engine
     * itself is broken: from then on a fresh engine is built for every reply
     * (after a short cooldown) instead of retrying inside the dead one.
     */
    @Volatile
    private var consecutiveStartFailures = 0

    /** True when the device engine has been failing - the UI can warn once. */
    fun engineIsTroubled(): Boolean = consecutiveStartFailures >= TROUBLE_AFTER_FAILURES

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
    private var onStartedCb: (() -> Unit)? = null
    private var chunks: ArrayDeque<String> = ArrayDeque()
    private var currentText: String? = null
    private var utteranceSeq = 0
    private var currentUtteranceId: String? = null
    private var lastStopAtMs = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var doneFallback: Runnable? = null
    private var startWatchdog: Runnable? = null
    private var readyTimeout: Runnable? = null

    /** Rough duration of [text] in ms - used for playback budgets everywhere. */
    fun estimateSpeechMs(text: String): Long =
        (text.length * MS_PER_CHAR).coerceIn(MIN_ESTIMATE_MS, MAX_ESTIMATE_MS)

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

    /** Rebuilds the engine from scratch after a crash or a wedged engine. */
    private fun createEngine(context: Context) {
        val resolved = context.applicationContext
        var created: TextToSpeech? = null
        try {
            created = TextToSpeech(resolved) { status ->
                // Posted: the constructor must finish assigning `created` first
                // (an engine can call back synchronously on some devices).
                mainHandler.post { onEngineReady(created, resolved, status) }
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
            engine = null
            try {
                tts.shutdown()
            } catch (_: Throwable) {
            }
            consecutiveStartFailures++
            // One more rebuild while a reply is still waiting; otherwise fail
            // open so the caller is never stuck waiting for a dead engine.
            if (pendingText != null && consecutiveStartFailures < TROUBLE_AFTER_FAILURES) {
                armReadyTimeout(context)
                mainHandler.postDelayed({ createEngine(context) }, RETRY_DELAY_MS)
            } else {
                pendingText = null
                fireDone()
            }
            return
        }
        ready = true
        appliedLanguageTag = null
        applyVoicePrefs(context)
        try {
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceId != currentUtteranceId) return
                    // A real start resets the "engine is broken" counter.
                    consecutiveStartFailures = 0
                    startedCurrent = true
                    speaking = true
                    clearStartWatchdog()
                    val cb = onStartedCb
                    cb?.invoke()
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId != currentUtteranceId) return
                    speaking = false
                    clearFallback()
                    clearStartWatchdog()
                    scheduleNextChunk()
                }

                @Deprecated("Required override for older engines")
                override fun onError(utteranceId: String?) = onChunkFailed(utteranceId)

                override fun onError(utteranceId: String?, errorCode: Int) =
                    onChunkFailed(utteranceId)
            })
        } catch (e: Exception) {
            Log.w(TAG, "listener wiring failed", e)
        }
        // NOTE: the framework's on-service-disconnected listener is a hidden
        // @SystemApi - it is NOT in the public SDK and broke the release build.
        // A disconnected engine is caught by the ERROR return and the watchdog.

        val text = pendingText
        pendingText = null
        clearReadyTimeout()
        if (text != null) {
            chunks = ArrayDeque(splitForEngine(text))
            startFailures = 0
            speakNextChunk()
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
            // of whatever the engine happens to default to.
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

    /**
     * Speaks [raw]. [onFinished] is called exactly once, whatever happens -
     * played, engine dead, voice data missing. [onStarted] fires when the
     * engine really began the utterance (used to tell "spoke" from "silent").
     */
    fun speak(
        context: Context,
        raw: String,
        onFinished: (() -> Unit)? = null,
        onStarted: (() -> Unit)? = null
    ) {
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
            onStartedCb = onStarted
            startFailures = 0
            currentText = null
            chunks = ArrayDeque()
            if (!ready) {
                // The engine is still starting (or being rebuilt): flush on ready,
                // with a hard cap so the caller can never wait forever.
                pendingText = text
                armReadyTimeout(context)
                return
            }
        }
        chunks = ArrayDeque(splitForEngine(text))
        // After two dead utterances in a row the old engine is not trusted:
        // build a fresh one and give it a moment before speaking.
        if (consecutiveStartFailures >= TROUBLE_AFTER_FAILURES) {
            resetEngine()
            createEngine(context)
            mainHandler.postDelayed({ speakNextChunk() }, FAILURE_COOLDOWN_MS)
        } else {
            speakNextChunk()
        }
    }

    /** Caps how long a reply may wait for an engine that never becomes ready. */
    private fun armReadyTimeout(context: Context) {
        clearReadyTimeout()
        val r = Runnable {
            if (!ready && pendingText != null) {
                Log.w(TAG, "engine never became ready - failing open instead of hanging")
                pendingText = null
                resetEngine()
                fireDone()
            }
        }
        readyTimeout = r
        mainHandler.postDelayed(r, READY_TIMEOUT_MS)
    }

    /** Speaks the next chunk, or finishes when the reply has been read out. */
    private fun speakNextChunk() {
        if (chunks.isEmpty()) {
            fireDone()
            return
        }
        currentText = chunks.removeFirst()
        startFailures = 0
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
        if (!ready || tts == null) {
            if (context != null) {
                // Keep the text for the moment the engine is ready, but never
                // without the ready timeout above.
                pendingText = joinRemaining(text)
                if (tts == null && consecutiveStartFailures < TROUBLE_AFTER_FAILURES) {
                    synchronized(this) {
                        if (engine == null) createEngine(context)
                    }
                }
                armReadyTimeout(context)
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
        clearReadyTimeout()
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
            // No callback follows an ERROR return.
            onStartFailure(context, text)
            return
        }

        speaking = true

        // Watchdog: accepted but never started (engine wedged).
        val watchdog = Runnable {
            if (currentUtteranceId == id && !startedCurrent) {
                Log.w(TAG, "utterance accepted but never started")
                onStartFailure(context, text)
            }
        }
        startWatchdog = watchdog
        mainHandler.postDelayed(watchdog, START_WATCHDOG_MS)

        // Watchdog: playback callback lost after a successful start.
        val fallback = Runnable {
            if (currentUtteranceId == id) {
                speaking = false
                scheduleNextChunk()
            }
        }
        doneFallback = fallback
        mainHandler.postDelayed(fallback, estimateSpeechMs(text) + START_WATCHDOG_MS)
    }

    /**
     * The engine refuses to speak. Retry once with a rebuilt engine; after that
     * DROP IT AND FAIL OPEN - an infinite rebuild loop with no callback is what
     * used to leave the whole voice mode stuck and every later answer silent.
     */
    private fun onStartFailure(context: Context?, text: String) {
        speaking = false
        startedCurrent = false
        startFailures++
        consecutiveStartFailures++
        clearFallback()
        clearStartWatchdog()
        Log.w(
            TAG,
            "device voice did not start (attempt $startFailures, " +
                "consecutive $consecutiveStartFailures)"
        )
        if (startFailures < MAX_START_FAILURES && context != null) {
            resetEngine()
            createEngine(context)
            pendingText = joinRemaining(text)
            armReadyTimeout(context)
        } else {
            // Give up on this utterance; a fresh engine is built on the next
            // reply (see speak()), and the caller is released right now.
            resetEngine()
            pendingText = null
            fireDone()
        }
    }

    /** A chunk errored after being accepted: retry once, then move on. */
    private fun onChunkFailed(utteranceId: String?) {
        // A late callback from an older utterance must never touch this one.
        if (utteranceId != null && utteranceId != currentUtteranceId) return
        speaking = false
        clearFallback()
        clearStartWatchdog()
        val context = appContext
        if (startFailures < MAX_START_FAILURES && context != null && ready) {
            startFailures++
            mainHandler.postDelayed({ speakCurrentChunk() }, RETRY_DELAY_MS)
        } else {
            // Do not let one bad sentence silence the whole reply.
            scheduleNextChunk()
        }
    }

    private fun scheduleNextChunk() {
        mainHandler.postDelayed({ speakNextChunk() }, SETTLE_BETWEEN_UTTERANCES_MS)
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
            onStartedCb = null
            mainHandler.post { cb?.invoke() }
        }
        speaking = false
        clearFallback()
        clearStartWatchdog()
        clearReadyTimeout()
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
        clearReadyTimeout()
        speaking = false
        currentText = null
        chunks = ArrayDeque()
        onStartedCb = null
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

    private fun clearReadyTimeout() {
        readyTimeout?.let { mainHandler.removeCallbacks(it) }
        readyTimeout = null
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

    /**
     * Markdown/emoji scrub before speaking.
     *
     * The reply is NOT trimmed to a small size any more: the old 700-character
     * cut is exactly why a normal CRM answer stopped in the middle and the rest
     * was never spoken. Long replies are spoken in chunks instead
     * ([splitForEngine] here, `AiVoicePlayer.splitForTts` for cloud audio), and
     * only a pathologically long text hits [VoiceTextLimits].
     */
    fun cleanForVoice(src: String): String {
        val t = src
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("[*_`#>|]"), " ")
            .replace(Regex("[\\uD800-\\uDFFF]"), "")
            .replace(Regex("[\\u2190-\\u2BFF\\uFE0F\\u200D]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return VoiceTextLimits.truncateForSpeech(t)
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
