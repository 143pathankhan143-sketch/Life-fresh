package com.example.ai.chat.voice

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.example.data.security.AIQuotaManager
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
 */
object AiVoicePlayer {

    private const val TAG = "AiVoicePlayer"
    private const val CLOUD_CHUNK_BUDGET_MS = 10_000L
    private const val PLAYBACK_BUDGET_MS = 75_000L

    @Volatile
    private var player: MediaPlayer? = null

    /** Natural voice is used when enabled in Settings and a Gemini key exists. */
    fun isNaturalEnabled(context: Context): Boolean =
        AIQuotaManager.isNaturalTtsEnabled(context) && GeminiTtsClient.isConfigured()

    suspend fun speakSuspend(context: Context, text: String) {
        if (text.isBlank()) return
        // Fast path: try cloud in chunks for real-time first audio.
        if (isNaturalEnabled(context)) {
            val chunks = splitForTts(AiTts.cleanForVoice(text))
            if (chunks.isNotEmpty()) {
                var anyPlayed = false
                for (chunk in chunks) {
                    if (!currentCoroutineContext().isActive) throw CancellationException("interrupted")
                    val file = try {
                        withTimeoutOrNull(CLOUD_CHUNK_BUDGET_MS) { GeminiTtsClient.synthesize(context, chunk) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "chunk cloud failed, will try device for remainder", e)
                        null
                    }
                    if (file != null) {
                        anyPlayed = true
                        // Play this chunk; if cancelled (barge-in) we stop entirely.
                        try {
                            withTimeoutOrNull(PLAYBACK_BUDGET_MS) { playFile(file) }
                        } catch (e: CancellationException) {
                            throw e
                        }
                        if (!currentCoroutineContext().isActive) throw CancellationException("interrupted")
                    } else {
                        // This chunk failed - fall back to device for THIS chunk only
                        // if we haven't played anything yet, fall back the remainder as device.
                        // If we already played some chunks, speak the failed chunk via device
                        // then continue to next chunk's cloud attempt.
                        try {
                            suspendCancellableCoroutine<Unit> { cont ->
                                AiTts.speak(context, chunk) {
                                    if (cont.isActive) cont.resume(Unit)
                                }
                                cont.invokeOnCancellation { AiTts.stop() }
                            }
                            anyPlayed = true
                        } catch (e: CancellationException) {
                            throw e
                        }
                    }
                }
                if (anyPlayed) return
                // All chunks failed - fall through to single device fallback below
            } else {
                // Single chunk (short text) fast path - same as before but budget tight
                val file = try {
                    withTimeoutOrNull(CLOUD_CHUNK_BUDGET_MS) { GeminiTtsClient.synthesize(context, text) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (file != null) {
                    withTimeoutOrNull(PLAYBACK_BUDGET_MS) { playFile(file) }
                    return
                }
            }
        }
        // Device engine fallback (instant, offline).
        suspendCancellableCoroutine<Unit> { cont ->
            AiTts.speak(context, text) {
                if (cont.isActive) cont.resume(Unit)
            }
            cont.invokeOnCancellation { AiTts.stop() }
        }
    }

    /** Speaks a sample line with the currently chosen cloud voice (Settings test). */
    suspend fun testSpeak(context: Context, sample: String) {
        if (isNaturalEnabled(context)) {
            val file = try {
                withTimeoutOrNull(CLOUD_CHUNK_BUDGET_MS) { GeminiTtsClient.synthesize(context, sample) }
            } catch (e: Exception) {
                null
            }
            if (file != null) {
                withTimeoutOrNull(PLAYBACK_BUDGET_MS) { playFile(file) }
                return
            }
        }
        withTimeoutOrNull(PLAYBACK_BUDGET_MS) {
            suspendCancellableCoroutine<Unit> { cont ->
                AiTts.speak(context, sample) {
                    if (cont.isActive) cont.resume(Unit)
                }
                cont.invokeOnCancellation { AiTts.stop() }
            }
        }
    }

    /**
     * Split for streaming TTS: keep sentences intact so the voice sounds
     * natural, but ensure the first chunk is small for fast first-audio.
     */
    private fun splitForTts(text: String): List<String> {
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

    private suspend fun playFile(file: File) = suspendCancellableCoroutine<Unit> { cont ->
        try {
            releasePlayer()
            val mp = MediaPlayer()
            player = mp
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                releasePlayer()
                if (cont.isActive) cont.resume(Unit)
            }
            mp.setOnErrorListener { _, _, _ ->
                releasePlayer()
                if (cont.isActive) cont.resume(Unit)
                true
            }
            mp.prepareAsync()
            mp.setOnPreparedListener { it.start() }
            cont.invokeOnCancellation { releasePlayer() }
        } catch (e: Exception) {
            Log.w(TAG, "playback failed", e)
            releasePlayer()
            if (cont.isActive) cont.resume(Unit)
        }
    }

    /** Cuts whatever is playing right now (barge-in / screen leave / toggle off). */
    fun stop() {
        releasePlayer()
        AiTts.stop()
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
