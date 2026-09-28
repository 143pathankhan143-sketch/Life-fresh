package com.example.ai.chat.voice

import android.content.Context
import android.util.Log
import com.example.ai.chat.config.AIConfig
import com.example.data.AppLanguage
import com.example.data.AppLanguageManager
import com.example.data.security.AIQuotaManager
import com.example.voice.OrpheusChunker
import com.example.voice.VoiceIds
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Natural speech without a Gemini account (user decision, 2026-09-27): Groq
 * hosts Canopy Labs' Orpheus TTS on the same API key the chat already uses.
 *
 * Scope, verified 2026-09-27 against console.groq.com/docs/text-to-speech:
 *  - Models: `canopylabs/orpheus-v1-english` and `canopylabs/orpheus-arabic-saudi`.
 *    There is NO Hindi/Tamil/Urdu model, so this client is used only while the
 *    app language is English. For Hindi/Tamil/Urdu the Gemini voice (or the
 *    phone's own engine) speaks instead.
 *  - `input` accepts at most 200 characters, so a longer reply is split by
 *    [OrpheusChunker] into several requests played in order.
 *
 * Every failure path returns an empty list (never throws), because the caller
 * silently falls back to the device TTS engine. A reply is never lost.
 *
 * Barge-in: the HTTP call is cancelled with the coroutine, like GeminiTtsClient.
 */
object GroqTtsClient {

    private const val TAG = "GroqTtsClient"
    private const val MODEL = "canopylabs/orpheus-v1-english"
    private const val VOICE_FEMALE = "hannah"
    private const val VOICE_MALE = "troy"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean = AIConfig.groqApiKey.isNotBlank()

    /** Orpheus on Groq speaks English (and Saudi Arabic) only. */
    fun speaksAppLanguage(context: Context): Boolean = try {
        AppLanguageManager.getLanguage(context) == AppLanguage.ENGLISH
    } catch (e: Exception) {
        true
    }

    /** The Orpheus voice matching the app's own female/male choice. */
    fun voice(context: Context): String {
        val chosen = try {
            GeminiTtsClient.normaliseVoice(AIQuotaManager.getTtsVoiceName(context))
        } catch (e: Exception) {
            VoiceIds.FEMALE
        }
        return if (chosen == VoiceIds.MALE) VOICE_MALE else VOICE_FEMALE
    }

    /**
     * One audio file per request-sized piece of [text], in order. An empty list
     * means "use the device engine instead".
     */
    suspend fun synthesizeAll(context: Context, text: String): List<File> {
        val key = AIConfig.groqApiKey
        if (key.isBlank()) return emptyList()
        val clean = AiTts.cleanForVoice(text)
        if (clean.isBlank()) return emptyList()
        val voice = voice(context)
        val out = mutableListOf<File>()
        for (piece in OrpheusChunker.split(clean)) {
            if (!currentCoroutineContext().isActive) break
            val file = runAttempt(context, key, voice, piece)
            if (file == null) {
                // Never play HALF an answer: if ANY piece fails, play nothing and
                // let the caller speak this whole chunk on the device engine.
                // (Nothing was played yet - the caller plays after this returns.)
                return emptyList()
            }
            out.add(file)
        }
        return out
    }

    private suspend fun runAttempt(
        context: Context,
        key: String,
        voice: String,
        text: String
    ): File? = try {
        val body = JSONObject().apply {
            put("model", MODEL)
            put("input", text)
            put("voice", voice)
            put("response_format", "wav")
        }
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/speech")
            .header("Authorization", "Bearer $key")
            .header("Accept", "audio/wav")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val response = client.newCall(request).awaitCancellable()
        response.use { res ->
            if (!res.isSuccessful) {
                Log.w(TAG, "speech HTTP ${res.code} (voice=$voice)")
                null
            } else {
                val bytes = res.body?.bytes()
                if (bytes == null || bytes.isEmpty()) null
                else writeCache(context, bytes)
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "speech failed: ${e.message}")
        null
    }

    private fun writeCache(context: Context, bytes: ByteArray): File {
        purgeOld(context)
        val f = File(
            context.cacheDir,
            "ai_groq_tts_${System.currentTimeMillis()}_${(0..9999).random()}.wav"
        )
        f.writeBytes(bytes)
        return f
    }

    private fun purgeOld(context: Context) {
        val cutoff = System.currentTimeMillis() - 10 * 60 * 1000L
        context.cacheDir
            .listFiles { f -> f.name.startsWith("ai_groq_tts_") && f.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }

    /** Await that respects coroutine cancellation (call.cancel on cancel). */
    private suspend fun Call.awaitCancellable(): Response =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { try { cancel() } catch (_: Exception) {} }
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isCancelled) return
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isCancelled) {
                        try { response.close() } catch (_: Exception) {}
                        return
                    }
                    cont.resume(response)
                }
            })
        }
}
