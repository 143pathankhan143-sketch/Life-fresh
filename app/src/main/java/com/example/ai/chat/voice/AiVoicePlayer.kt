package com.example.ai.chat.voice

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.example.data.security.AIQuotaManager
import kotlinx.coroutines.CancellationException
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
 * speakSuspend() returns only when the audio has finished (or failed), so
 * the Bolo loop can chain  speak -> listen  without timers. stop() cuts the
 * current reply instantly (barge-in).
 */
object AiVoicePlayer {

    private const val TAG = "AiVoicePlayer"
    private const val CLOUD_BUDGET_MS = 25_000L
    private const val PLAYBACK_BUDGET_MS = 75_000L

    @Volatile
    private var player: MediaPlayer? = null

    /** Natural voice is used when enabled in Settings and a Gemini key exists. */
    fun isNaturalEnabled(context: Context): Boolean =
        AIQuotaManager.isNaturalTtsEnabled(context) && GeminiTtsClient.isConfigured()

    suspend fun speakSuspend(context: Context, text: String) {
        if (text.isBlank()) return
        if (isNaturalEnabled(context)) {
            val file = try {
                withTimeoutOrNull(CLOUD_BUDGET_MS) { GeminiTtsClient.synthesize(context, text) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "cloud tts failed, using device voice", e)
                null
            }
            if (file != null) {
                withTimeoutOrNull(PLAYBACK_BUDGET_MS) { playFile(file) }
                return
            }
        }
        // Device engine fallback.
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
                withTimeoutOrNull(CLOUD_BUDGET_MS) { GeminiTtsClient.synthesize(context, sample) }
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
