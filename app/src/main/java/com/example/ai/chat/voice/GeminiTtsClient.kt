package com.example.ai.chat.voice

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.ai.chat.config.AIConfig
import com.example.data.AppLanguage
import com.example.data.AppLanguageManager
import com.example.data.security.AIQuotaManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Cloud text-to-speech through Gemini's native TTS models - the voice that
 * powers spoken AI replies when the user has a Gemini key configured (the
 * same key chat uses, so this costs nothing on the free tier).
 *
 * One multilingual voice speaks Hindi, Tamil, Urdu and English with the
 * correct accent, which is exactly what the phone's built-in engine cannot
 * do for non-English users. Every failure path here returns null so the
 * caller can silently fall back to the device engine - nothing throws.
 */
object GeminiTtsClient {

    private const val TAG = "GeminiTtsClient"

    /** Prebuilt Gemini TTS voices. "auto" omits the voice config entirely. */
    val VOICES: List<String> = listOf(
        "auto",
        "Kore", "Charon", "Puck", "Zephyr", "Fenrir", "Leda", "Aoede",
        "Callirrhoe", "Autonoe", "Enceladus", "Iapetus", "Umbriel",
        "Algieba", "Despina", "Erinome", "Algenib", "Rasalgethi",
        "Laomedeia", "Achernar", "Alnilam", "Schedar", "Gacrux",
        "Pulcherrima", "Achird", "Zubenelgenubi", "Vindemiatrix",
        "Sadachbia", "Sadaltager", "Sulafat"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean = AIConfig.geminiApiKey.isNotBlank()

    /**
     * Synthesizes [rawText] into a playable audio file in cacheDir.
     * Returns null when cloud TTS is unavailable (no key, offline, bad
     * response) - caller must fall back to the device engine.
     */
    suspend fun synthesize(context: Context, rawText: String): File? = withContext(Dispatchers.IO) {
        val key = AIConfig.geminiApiKey
        if (key.isBlank()) return@withContext null
        val text = AiTts.cleanForVoice(rawText)
        if (text.isBlank()) return@withContext null

        val voice = AIQuotaManager.getTtsVoiceName(context)
        for (model in AIConfig.GEMINI_TTS_MODELS) {
            // With the chosen voice first, then once without (some newer
            // models dropped an old voice name; auto always works).
            for (useVoice in listOf(true, false)) {
                if (!useVoice && (voice.isBlank() || voice == "auto")) continue
                val attempt = runAttempt(context, model, key, text, if (useVoice) voice else "auto")
                if (attempt != null) return@withContext attempt
            }
        }
        null
    }

    private fun runAttempt(
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
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "$model ($voice) HTTP ${response.code}")
                    return null
                }
                parseAudio(context, response.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            Log.w(TAG, "$model ($voice) failed: ${e.message}")
            null
        }
    }

    private fun buildBody(text: String, voice: String, context: Context): JSONObject {
        val speech = JSONObject()
        if (voice.isNotBlank() && !voice.equals("auto", ignoreCase = true)) {
            speech.put(
                "voiceConfig",
                JSONObject().put(
                    "prebuiltVoiceConfig",
                    JSONObject().put("voiceName", voice)
                )
            )
        }
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
        val f = File(context.cacheDir, "ai_tts_${System.currentTimeMillis()}.$ext")
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
