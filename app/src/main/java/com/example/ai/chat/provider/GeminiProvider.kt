package com.example.ai.chat.provider

import android.util.Log
import com.example.ai.chat.config.AIConfig
import com.example.ai.chat.model.ChatMessage
import com.example.ai.chat.model.ChatRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class GeminiProvider(
    private val apiKeyProvider: () -> String = { AIConfig.geminiApiKey },
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build(),
    private val modelName: String = AIConfig.GEMINI_TEXT_MODELS.first()
) : AIProvider {

    override val name: String = "Gemini"

    override val isConfigured: Boolean
        get() = apiKeyProvider().isNotBlank()

    override suspend fun generateResponse(
        messages: List<ChatMessage>,
        systemInstruction: String
    ): AIProviderResult = generateStreamingResponse(messages, systemInstruction) {
        // Non-streaming callers do not need the tokens; the final result
        // text is exactly what they consume.
    }

    override suspend fun generateStreamingResponse(
        messages: List<ChatMessage>,
        systemInstruction: String,
        onToken: (String) -> Unit
    ): AIProviderResult = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) {
            return@withContext AIProviderResult.Failure(
                providerName = name,
                errorMessage = "Gemini API key is not configured.",
                isRetryable = false,
                isRateLimitOrTimeout = false
            )
        }

        try {
            val requestJson = buildRequestBody(messages, systemInstruction) ?: return@withContext AIProviderResult.Failure(
                providerName = name,
                errorMessage = "No message content to send.",
                isRetryable = false
            )

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = requestJson.toString().toRequestBody(mediaType)

            val models = AIConfig.GEMINI_TEXT_MODELS
                .let { if (it.contains(modelName)) it else listOf(modelName) + it }
                .distinct()

            var lastFailure: AIProviderResult.Failure? = null

            for (model in models) {
                // streamGenerateContent + alt=sse = server-sent events.
                // readStream() degrades to the classic one-shot JSON response
                // automatically if the body is not SSE.
                // Same endpoint + auth as the classic generateContent call,
                // just with the streaming action and alt=sse.
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse&key=$apiKey"

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build()

                val attempt: ModelAttempt = client.newCall(request).execute().use { response ->
                    val code = response.code

                    if (response.isSuccessful) {
                        val body = response.body
                        if (body == null) {
                            lastFailure = AIProviderResult.Failure(
                                providerName = name,
                                errorMessage = "Gemini model $model returned an empty response.",
                                isRetryable = true
                            )
                            ModelAttempt.TryNext
                        } else {
                            val text = readStream(body, onToken).trim()
                            if (text.isNotBlank()) {
                                ModelAttempt.Ok(text)
                            } else {
                                lastFailure = AIProviderResult.Failure(
                                    providerName = name,
                                    errorMessage = "Gemini model $model returned no text (HTTP $code).",
                                    isRetryable = true
                                )
                                ModelAttempt.TryNext
                            }
                        }
                    } else {
                        val responseBodyString = try {
                            response.body?.string().orEmpty()
                        } catch (_: Throwable) {
                            ""
                        }

                        val parsedError = try {
                            JSONObject(responseBodyString).optJSONObject("error")?.optString("message")
                        } catch (_: Throwable) {
                            null
                        }

                        val isRateLimit = code == 429
                        val isAuthError = code == 400 || code == 401 || code == 403

                        val errorMsg = if (isAuthError) {
                            "API Key Invalid: ${parsedError ?: "HTTP $code"}"
                        } else if (isRateLimit) {
                            "Gemini rate limit reached: ${parsedError ?: "Please try again later."}"
                        } else {
                            parsedError ?: "Gemini returned status $code"
                        }

                        // If auth error, return immediately without trying other models
                        if (isAuthError) {
                            return@withContext AIProviderResult.Failure(
                                providerName = name,
                                errorMessage = errorMsg,
                                isRetryable = false,
                                isRateLimitOrTimeout = false
                            )
                        }

                        lastFailure = AIProviderResult.Failure(
                            providerName = name,
                            errorMessage = errorMsg,
                            isRetryable = true,
                            isRateLimitOrTimeout = isRateLimit
                        )
                        ModelAttempt.TryNext
                    }
                }

                if (attempt is ModelAttempt.Ok) {
                    return@withContext AIProviderResult.Success(attempt.text, name)
                }

                // Rate limits are per-project (not per-model) and network
                // timeouts affect every model endpoint equally - trying the
                // remaining fallback models would only add seconds of delay.
                if (lastFailure?.isRateLimitOrTimeout == true) {
                    Log.w(TAG, "Gemini $model: ${lastFailure.errorMessage} - skipping remaining fallback models")
                    return@withContext lastFailure
                }

                Log.w(TAG, "Gemini model $model failed. Trying next fallback model...")
            }

            return@withContext lastFailure ?: AIProviderResult.Failure(
                providerName = name,
                errorMessage = "Unable to reach Gemini API after trying all fallback models.",
                isRetryable = true
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "Gemini request timed out", e)
            AIProviderResult.Failure(
                providerName = name,
                errorMessage = "Gemini request timed out.",
                isRetryable = true,
                isRateLimitOrTimeout = true,
                cause = e
            )
        } catch (e: IOException) {
            Log.w(TAG, "Gemini network error", e)
            AIProviderResult.Failure(
                providerName = name,
                errorMessage = "Network connection error reaching Gemini.",
                isRetryable = true,
                cause = e
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in GeminiProvider", e)
            AIProviderResult.Failure(
                providerName = name,
                errorMessage = "Failed to communicate with Gemini.",
                isRetryable = true,
                cause = e
            )
        }
    }

    /**
     * Builds the classic generateContent JSON body. Returns null when there
     * is no usable message content at all.
     */
    private fun buildRequestBody(
        messages: List<ChatMessage>,
        systemInstruction: String
    ): JSONObject? {
        val jsonContents = JSONArray()

        // Filter recent valid messages (limit to 20 messages for context)
        val recentMessages = if (messages.size > 20) messages.takeLast(20) else messages
        for (msg in recentMessages) {
            if (msg.isError || msg.content.isBlank()) continue
            val roleStr = when (msg.role) {
                ChatRole.USER -> "user"
                ChatRole.ASSISTANT -> "model"
                ChatRole.SYSTEM -> "user"
            }

            val contentObj = JSONObject().apply {
                put("role", roleStr)
                val partsArray = JSONArray().apply {
                    put(JSONObject().apply { put("text", msg.content) })
                }
                put("parts", partsArray)
            }
            jsonContents.put(contentObj)
        }

        if (jsonContents.length() == 0) return null

        return JSONObject().apply {
            if (systemInstruction.isNotBlank()) {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", systemInstruction) })
                    })
                })
            }

            put("contents", jsonContents)

            put("generationConfig", JSONObject().apply {
                // NOTE: Gemini 3.x models default to thinking_level "high", which
                // adds 10-16s of internal reasoning per request. For an
                // interactive chat, "low" is Google's recommended level
                // (ai.google.dev/gemini-api/docs/generate-content/thinking).
                // NOTE: temperature is intentionally NOT set - Google's Gemini 3
                // migration notes say explicit temperature values can cause
                // looping/performance degradation on 3.x models.
                // NOTE: thinking tokens count towards maxOutputTokens on 3.x
                // models. 3072 caused replies to be silently cut off
                // (finishReason MAX_TOKENS) when thinking used most of the
                // budget - 8192 leaves real headroom for the visible answer.
                put("maxOutputTokens", 8192)
                put("thinkingConfig", JSONObject().apply {
                    put("thinkingLevel", "low")
                })
            })
        }
    }

    /**
     * Reads the response body of a SUCCESSFUL request.
     *
     * Normal case (alt=sse): server-sent events - lines like
     * `data: {"candidates":[{"content":{"parts":[{"text":"..."}]}}]}`.
     * Each text part is emitted through [onToken] immediately.
     *
     * Defensive fallback: if the first non-empty line is plain JSON instead
     * of a `data:` line, the whole body is parsed as a classic
     * generateContent response and delivered as a single token.
     *
     * Returns the full reply text (may be empty if nothing usable arrived).
     */
    private fun readStream(body: ResponseBody, onToken: (String) -> Unit): String {
        val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8))
        val text = StringBuilder()
        val plainBuffer = StringBuilder()
        var mode = 0 // 0 = unknown, 1 = SSE ("data:" lines), 2 = plain JSON
        while (true) {
            val line = reader.readLine() ?: break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (mode == 0) mode = if (trimmed.startsWith("data:")) 1 else 2
            if (mode == 1) {
                if (!trimmed.startsWith("data:")) continue
                val payload = trimmed.substring(5).trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                try {
                    val obj = JSONObject(payload)
                    val candidates = obj.optJSONArray("candidates") ?: continue
                    if (candidates.length() == 0) continue
                    val content = candidates.getJSONObject(0).optJSONObject("content") ?: continue
                    val parts = content.optJSONArray("parts") ?: continue
                    val partText = StringBuilder()
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        // Thinking/reasoning parts are internal to the model -
                        // never show them in the chat.
                        if (part.optBoolean("thought", false)) continue
                        partText.append(part.optString("text", "").orEmpty())
                    }
                    if (partText.isNotEmpty()) {
                        text.append(partText)
                        onToken(partText.toString())
                    }
                } catch (e: Throwable) {
                    // Malformed chunk - skip it, keep the stream alive.
                }
            } else {
                plainBuffer.append(line).append('\n')
            }
        }

        if (mode != 1) {
            // Plain (non-SSE) body: parse exactly like the classic endpoint.
            try {
                val candidates = JSONObject(plainBuffer.toString().trim()).optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val parts = candidates.getJSONObject(0)
                        .optJSONObject("content")
                        ?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val partText = StringBuilder()
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            // Thinking/reasoning parts are internal - skip.
                            if (part.optBoolean("thought", false)) continue
                            partText.append(part.optString("text", "").orEmpty())
                        }
                        val fullText = partText.toString().trim()
                        if (fullText.isNotEmpty()) {
                            text.append(fullText)
                            onToken(fullText)
                        }
                    }
                }
            } catch (e: Throwable) {
                // Unparseable body - return whatever was accumulated so far.
            }
        }
        return text.toString()
    }

    private sealed class ModelAttempt {
        data class Ok(val text: String) : ModelAttempt()
        object TryNext : ModelAttempt()
    }

    companion object {
        private const val TAG = "GeminiProvider"
    }
}
