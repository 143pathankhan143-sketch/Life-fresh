package com.example.ai.chat.voice

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.ai.chat.config.AIConfig
import com.example.data.AppLanguage
import com.example.data.AppLanguageManager
import com.example.data.security.AIQuotaManager
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Cloud text-to-speech through Gemini's native TTS models - the voice that
 * powers spoken AI replies when the user has a Gemini key configured (the
 * same key chat uses, so this costs nothing on the free tier).
 *
 * One multilingual voice speaks Hindi, Tamil, Urdu and English with the
 * correct accent, which is exactly what the phone's built-in engine cannot
 * do for non-English users. Every failure path here returns null so the
 * caller can silently fall back to the device engine - nothing throws.
 *
 * FAST PATH: single model + single voice (auto -> Kore) so the first audio
 * chunk is ready in ~1-2s on good network. No 6-way retry storm.
 */
object GeminiTtsClient {

    private const val TAG = "GeminiTtsClient"

    /** Prebuilt Gemini TTS voices. "auto" is kept for UI but maps to Kore (stable). */
    val VOICES: List<String> = listOf(
        "auto",
        "Kore", "Charon", "Puck", "Zephyr", "Fenrir", "Leda", "Aoede",
        "Callirrhoe", "Autonoe", "Enceladus", "Iapetus", "Umbriel",
        "Algieba", "Despina", "Erinome", "Algenib", "Rasalgethi",
        "Laomedeia", "Achernar", "Alnilam", "Schedar", "Gacrux",
        "Pulcherrima", "Achird", "Zubenelgenubi", "Vindemiatrix",
        "Sadachbia", "Sadaltager", "Sulafat"
    )

    private const val STABLE_DEFAULT_VOICE = "Kore"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean = AIConfig.geminiApiKey.isNotBlank()

    fun stableVoice(context: Context): String {
        val v = AIQuotaManager.getTtsVoiceName(context)
        return if (v.isBlank() || v.equals("auto", true)) STABLE_DEFAULT_VOICE else v
    }

    /**
     * Synthesizes [rawText] into a playable audio file in cacheDir.
     * Returns null when cloud TTS is unavailable (no key, offline, bad
     * response) - caller must fall back to the device engine.
     * Cancellable: if the coroutine is cancelled (user interrupted / new
     * request started) the HTTP call is aborted immediately.
     */
    suspend fun synthesize(context: Context, rawText: String): File? {
        val key = AIConfig.geminiApiKey
        if (key.isBlank()) return null
        val text = AiTts.cleanForVoice(rawText)
        if (text.isBlank()) return null
        val voice = stableVoice(context)
        // Fast path: try the first (newest) model with the chosen voice.
        // If that fails, try the next model once. No 6-way storm.
        val models = AIConfig.GEMINI_TTS_MODELS.take(2)
        for (model in models) {
            val file = runAttemptCancellable(context, model, key, text, voice)
            if (file != null) return file
            // If we were cancelled, don't try next model.
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) return null
        }
        return null
    }

    private suspend fun runAttemptCancellable(
        context: Context,
        model: String,
        key: String,
        text: String,
        voice: String
    ): File? {
        return try {
            val body = buildBody(text, voice, context)
            val request = Request.Builder()
                .url(
                    "https://generativelanguage.googleapis.com/v1beta/models/" +
                        "$model:generateContent?key=$key"
                )
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val call = client.newCall(request)
            val response = call.awaitCancellable()
            response.use { res ->
                if (!res.isSuccessful) {
                    Log.w(TAG, "$model ($voice) HTTP ${res.code}")
                    return null
                }
                parseAudio(context, res.body?.string().orEmpty())
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$model ($voice) failed: ${e.message}")
            null
        }
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

    private fun buildBody(text: String, voice: String, context: Context): JSONObject {
        val speech = JSONObject()
        // Always pin a concrete voice - "auto" was random gender each time.
        val v = if (voice.isBlank() || voice.equals("auto", true)) STABLE_DEFAULT_VOICE else voice
        speech.put(
            "voiceConfig",
            JSONObject().put(
                "prebuiltVoiceConfig",
                JSONObject().put("voiceName", v)
            )
        )
        speech.put("languageCode", speechLocale(context))
        return JSONObject().apply {
            put("contents", org.json.JSONArray().put(
                JSONObject().put("parts", org.json.JSONArray().put(
                    JSONObject().put("text", text)
                ))
            ))
            put("generationConfig", JSONObject().apply {
                put("responseModalities", org.json.JSONArray().put("AUDIO"))
                put("speechConfig", speech)
            })
        }
    }

    private fun speechLocale(context: Context): String = try {
        when (AppLanguageManager.getLanguage(context)) {
            AppLanguage.HINDI -> "hi-IN"
            AppLanguage.TAMIL -> "ta-IN"
            AppLanguage.URDU -> "ur-PK"
            else -> "en-IN"
        }
    } catch (e: Exception) {
        "en-IN"
    }

    /** Pulls the inline audio out of a generateContent response and stores it. */
    private fun parseAudio(context: Context, json: String): File? {
        try {
            if (json.isBlank()) return null
            val root = JSONObject(json)
            val candidates = root.optJSONArray("candidates") ?: return null
            for (c in 0 until candidates.length()) {
                val parts = candidates.optJSONObject(c)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts") ?: continue
                for (i in 0 until parts.length()) {
                    val inline = parts.optJSONObject(i)?.optJSONObject("inlineData")
                        ?: parts.optJSONObject(i)?.optJSONObject("inline_data")
                        ?: continue
                    val mime = inline.optString("mimeType", inline.optString("mime_type", ""))
                    val data = inline.optString("data")
                    if (data.isBlank()) continue
                    val bytes = Base64.decode(data, Base64.DEFAULT)
                    if (bytes.isEmpty()) continue
                    return when {
                        mime.contains("pcm", true) || mime.contains("L24", true) ||
                            mime.contains("L16", true) ->
                            writeWav(context, pcmToWav(bytes, sampleRateOf(mime)))
                        mime.contains("wav", true) -> writeCache(context, bytes, "wav")
                        mime.contains("mpeg", true) || mime.contains("mp3", true) ->
                            writeCache(context, bytes, "mp3")
                        mime.contains("ogg", true) -> writeCache(context, bytes, "ogg")
                        else -> writeWav(context, pcmToWav(bytes, 24000))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "audio parse failed", e)
        }
        return null
    }

    private fun sampleRateOf(mime: String): Int = when {
        mime.contains("24000", true) || mime.contains("L24", true) -> 24000
        mime.contains("16000", true) || mime.contains("L16", true) -> 16000
        else -> 24000
    }

    private fun writeCache(context: Context, bytes: ByteArray, ext: String): File {
        purgeOld(context)
        val f = File(context.cacheDir, "ai_tts_${System.currentTimeMillis()}_${(0..9999).random()}.$ext")
        f.writeBytes(bytes)
        return f
    }

    private fun writeWav(context: Context, bytes: ByteArray): File =
        writeCache(context, bytes, "wav")

    /** 44-byte RIFF header over raw mono 16-bit PCM. */
    private fun pcmToWav(pcm: ByteArray, rate: Int): ByteArray {
        val total = pcm.size + 44
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(total - 8)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)          // fmt chunk size
        header.putShort(1)         // PCM
        header.putShort(1)         // mono
        header.putInt(rate)
        header.putInt(rate * 2)    // byte rate
        header.putShort(2)         // block align
        header.putShort(16)        // bits per sample
        header.put("data".toByteArray())
        header.putInt(pcm.size)
        return header.array() + pcm
    }

    private fun purgeOld(context: Context) {
        val dir = context.cacheDir
        val cutoff = System.currentTimeMillis() - 10 * 60 * 1000L
        dir.listFiles { f -> f.name.startsWith("ai_tts_") && f.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }
}
