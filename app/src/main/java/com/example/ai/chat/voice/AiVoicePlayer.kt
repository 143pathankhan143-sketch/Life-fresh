package com.example.ai.chat.voice

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.example.data.security.AIQuotaManager
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * One entry point for speaking an AI reply, used by the voice-reply toggle
 * and by Bolo mode. Order of preference:
 *
 *  1. Gemini natural voice (free tier, same key as chat) - human-like and
 *     speaks Hindi/Tamil/Urdu/English with the right accent.
 *  2. The phone's built-in TTS engine (offline fallback, no account at all).
 *
 * Real-time feel:
 *  - Long replies are split into sentences. The first sentence is
 *    synthesized and played as soon as it is ready (~1-2s), while the
 *    next sentences are synthesized one-by-one. This is much faster than
 *    waiting for the whole 700-char reply.
 *  - Every network call and every playback is cancellable. If the user
 *    taps mic/send or sends a new message (thinking starts) the current
 *    audio stops instantly and remaining chunks are dropped (barge-in).
 *
 * STREAM-SAFE (the "old answer spoke again" fix):
 *  - A monotonic [speakToken] is bumped whenever a new reply starts speaking
 *    and whenever [stop] is called. Every chunk captures the token it started
 *    with and re-checks it before each network call and each playback, so a
 *    LATE callback from an OLDER reply (a slow cloud chunk, a device TTS
 *    completion that fires one or two questions later) can never inject stale
 *    audio into the current reply. Previously a late resume would happily keep
 *    speaking the old answer (e.g. A1 leaking into Q3).
 *  - In-flight playback/device continuations are woken by [stop] and by the
 *    next `beginSpeak`, so a barge-in returns immediately instead of hanging
 *    until the long playback/timeout budget expires.
 */
object AiVoicePlayer {

    private const val TAG = "AiVoicePlayer"
    private const val CLOUD_CHUNK_BUDGET_MS = 10_000L
    private const val PLAYBACK_BUDGET_MS = 75_000L

    @Volatile
    private var player: MediaPlayer? = null

    /** Monotonic speak token; bumped by every new speech and every stop(). */
    @Volatile
    private var speakToken = 0L

    /** In-flight media-playback continuation; stop()/new-speech wake it. */
    @Volatile
    private var playContinuation: CancellableContinuation<Unit>? = null

    /** In-flight device-TTS continuation; stop()/new-speech wake it. */
    @Volatile
    private var deviceContinuation: CancellableContinuation<Unit>? = null

    /** Natural voice is used when enabled in Settings and a Gemini key exists. */
    fun isNaturalEnabled(context: Context): Boolean =
        AIQuotaManager.isNaturalTtsEnabled(context) && GeminiTtsClient.isConfigured()

    suspend fun speakSuspend(context: Context, text: String) {
        if (text.isBlank()) return
        // Claim the speak channel: bumps the token, which invalidates and
        // wakes every older in-flight speaker so stale audio stops right now.
        val token = beginSpeak()

        // Fast path: try cloud in chunks for real-time first audio.
        if (isNaturalEnabled(context)) {
            val chunks = splitForTts(AiTts.cleanForVoice(text))
            if (chunks.isNotEmpty()) {
                var anyPlayed = false
                for (chunk in chunks) {
                    if (stale(token) || !currentCoroutineContext().isActive) return
                    val file = try {
                        withTimeoutOrNull(CLOUD_CHUNK_BUDGET_MS) { GeminiTtsClient.synthesize(context, chunk) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "chunk cloud failed, will try device for this chunk", e)
                        null
                    }
                    if (file != null) {
                        if (stale(token) || !currentCoroutineContext().isActive) return
                        try {
                            withTimeoutOrNull(PLAYBACK_BUDGET_MS) { playFile(file) }
                        } catch (e: CancellationException) {
                            throw e
                        }
                        anyPlayed = true
                    } else {
                        // This chunk's cloud synth failed - speak just this
                        // chunk with the device engine, then try the next
                        // chunk on cloud as before.
                        if (stale(token) || !currentCoroutineContext().isActive) return
                        speakOnDevice(context, chunk)
                        anyPlayed = true
                    }
                }
                if (anyPlayed) return
                // Every chunk failed - fall through to the whole-text device
                // fallback below.
            } else {
                // Single chunk (short text) fast path with the same tight budget.
                val file = try {
                    withTimeoutOrNull(CLOUD_CHUNK_BUDGET_MS) { GeminiTtsClient.synthesize(context, text) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (file != null && !stale(token)) {
                    try {
                        withTimeoutOrNull(PLAYBACK_BUDGET_MS) { playFile(file) }
                    } catch (e: CancellationException) {
                        throw e
                    }
                    return
                }
            }
        }

        // Device engine fallback (instant, offline).
        if (stale(token) || !currentCoroutineContext().isActive) return
        speakOnDevice(context, text)
    }

    /** Speaks a sample line with the currently chosen cloud voice (Settings test). */
    suspend fun testSpeak(context: Context, sample: String) {
        if (sample.isBlank()) return
        val token = beginSpeak()
        if (isNaturalEnabled(context)) {
            val file = try {
                withTimeoutOrNull(CLOUD_CHUNK_BUDGET_MS) { GeminiTtsClient.synthesize(context, sample) }
            } catch (e: Exception) {
                null
            }
            if (file != null && !stale(token)) {
                try {
                    withTimeoutOrNull(PLAYBACK_BUDGET_MS) { playFile(file) }
                } catch (e: CancellationException) {
                    throw e
                }
                return
            }
        }
        if (!stale(token) && currentCoroutineContext().isActive) {
            speakOnDevice(context, sample)
        }
    }

    /**
     * Split for streaming TTS: keep sentences intact so the voice sounds
     * natural, but ensure the first chunk is small for fast first-audio.
     */
    internal fun splitForTts(text: String): List<String> {
        if (text.length <= 220) return listOf(text)
        // Split on sentence boundaries common in hi/en/ur/ta
        val delimiters = Regex("(?<=[।.!?\\n])\\s+")
        val parts = text.split(delimiters).map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size <= 1) {
            // No sentence boundary - hard split by words
            val words = text.split(Regex("\\s+"))
            val out = mutableListOf<String>()
            var cur = StringBuilder()
            for (w in words) {
                if (cur.length + w.length + 1 > 180 && cur.isNotEmpty()) {
                    out.add(cur.toString())
                    cur = StringBuilder(w)
                } else {
                    if (cur.isNotEmpty()) cur.append(' ')
                    cur.append(w)
                }
            }
            if (cur.isNotEmpty()) out.add(cur.toString())
            return out
        }
        // Pack sentences into ~180-char chunks, but first chunk is at most one sentence
        // so it starts playing fast.
        val out = mutableListOf<String>()
        var cur = StringBuilder()
        for (p in parts) {
            if (p.length > 220) {
                if (cur.isNotEmpty()) { out.add(cur.toString()); cur = StringBuilder() }
                // Very long sentence - word-split it
                val words = p.split(Regex("\\s+"))
                var c2 = StringBuilder()
                for (w in words) {
                    if (c2.length + w.length + 1 > 180 && c2.isNotEmpty()) {
                        out.add(c2.toString()); c2 = StringBuilder(w)
                    } else {
                        if (c2.isNotEmpty()) c2.append(' ')
                        c2.append(w)
                    }
                }
                if (c2.isNotEmpty()) out.add(c2.toString())
                continue
            }
            if (cur.isEmpty()) {
                cur.append(p)
                // First chunk: flush immediately for low latency
                if (out.isEmpty()) {
                    out.add(cur.toString()); cur = StringBuilder()
                }
            } else if (cur.length + p.length + 1 > 180) {
                out.add(cur.toString()); cur = StringBuilder(p)
            } else {
                cur.append(' '); cur.append(p)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out.filter { it.isNotBlank() }
    }

    // --- token / barge-in plumbing ----------------------------------------

    private fun beginSpeak(): Long = synchronized(this) {
        ++speakToken
    }.also {
        // A new speaker invalidates the old one; wake it so it exits NOW
        // instead of waiting for a late engine callback / long timeout, and
        // silence any audio that is still playing from the old speaker.
        releasePlayer()
        AiTts.stop()
        wakePlayback()
        wakeDevice()
    }

    private fun stale(token: Long): Boolean = token != speakToken

    private fun wakePlayback() {
        val c = playContinuation
        playContinuation = null
        if (c != null && c.isActive) {
            try { c.resume(Unit) } catch (_: Exception) {}
        }
    }

    private fun wakeDevice() {
        val c = deviceContinuation
        deviceContinuation = null
        if (c != null && c.isActive) {
            try { c.resume(Unit) } catch (_: Exception) {}
        }
    }

    /** Device-engine speech that resumes only for THIS call (never a stale call). */
    private suspend fun speakOnDevice(context: Context, text: String): Unit =
        suspendCancellableCoroutine { cont ->
            deviceContinuation = cont
            AiTts.speak(context, text) {
                if (deviceContinuation === cont) {
                    deviceContinuation = null
                    if (cont.isActive) {
                        try { cont.resume(Unit) } catch (_: Exception) {}
                    }
                }
            }
            cont.invokeOnCancellation {
                if (deviceContinuation === cont) deviceContinuation = null
                AiTts.stop()
            }
        }

    private suspend fun playFile(file: File): Unit = suspendCancellableCoroutine { cont ->
        playContinuation = cont
        try {
            releasePlayer()
            val mp = MediaPlayer()
            player = mp
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { finishPlayback() }
            mp.setOnErrorListener { _, _, _ ->
                finishPlayback()
                true
            }
            mp.prepareAsync()
            mp.setOnPreparedListener { it.start() }
        } catch (e: Exception) {
            Log.w(TAG, "playback failed", e)
            finishPlayback()
        }
        cont.invokeOnCancellation {
            if (playContinuation === cont) playContinuation = null
            releasePlayer()
        }
    }

    private fun finishPlayback() {
        val c = playContinuation
        playContinuation = null
        if (c != null && c.isActive) {
            try { c.resume(Unit) } catch (_: Exception) {}
        }
        releasePlayer()
    }

    /** Cuts whatever is playing right now (barge-in / screen leave / toggle off). */
    fun stop() {
        synchronized(this) {
            speakToken++
        }
        releasePlayer()
        AiTts.stop()
        wakePlayback()
        wakeDevice()
    }

    private fun releasePlayer() {
        player?.let { mp ->
            try {
                if (mp.isPlaying) mp.stop()
            } catch (_: Exception) {
            }
            try {
                mp.release()
            } catch (_: Exception) {
            }
        }
        player = null
    }
}
