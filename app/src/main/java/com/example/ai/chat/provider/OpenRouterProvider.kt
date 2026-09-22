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
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * OpenRouter provider (https://openrouter.ai) - one API key gives access to
 * many models. The API is OpenAI-compatible: same request body and the same
 * server-sent-events stream format as Groq, only the base URL differs.
 *
 * Fallback role in the router: Gemini (primary) -> OpenRouter -> Groq (fast
 * last resort). It only activates when the user has saved an OpenRouter key.
 */
class OpenRouterProvider(
    private val apiKeyProvider: () -> String = { AIConfig.openrouterApiKey },
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
) : AIProvider {
    override val name: String = "OpenRouter"
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
                errorMessage = "OpenRouter API key is not configured.",
                isRetryable = false,
                isRateLimitOrTimeout = false
            )
        }

        try {
            val jsonMessages = buildJsonMessages(messages, systemInstruction)
            val mediaType = "application/json; charset=utf-8".toMediaType()

            // Model chain: alias slugs from AIConfig.OPENROUTER_TEXT_MODELS
            // (they always redirect to the newest version of the family).
            val models = AIConfig.OPENROUTER_TEXT_MODELS.distinct()

            var lastFailure: AIProviderResult.Failure? = null

            for (model in models) {
                val requestJson = JSONObject().apply {
                    put("model", model)
                    put("messages", jsonMessages)
                    put("temperature", 0.7)
                    // Headroom for reasoning models that spend part of the
                    // budget on internal thinking (mirrors Groq provider).
                    put("max_tokens", 8192)
                    put("stream", true)
                }
                val request = Request.Builder()
                    .url("https://openrouter.ai/api/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("X-Title", "LifeFresh QuickNote Pro")
                    .post(requestJson.toString().toRequestBody(mediaType))
                    .build()

                val attempt: ModelAttempt = client.newCall(request).execute().use { response ->
                    val code = response.code

                    if (response.isSuccessful) {
                        val body = response.body
                        if (body == null) {
                            lastFailure = AIProviderResult.Failure(
                                providerName = name,
                                errorMessage = "OpenRouter model $model returned an empty response.",
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
                                    errorMessage = "OpenRouter model $model returned no text (HTTP $code).",
                                    isRetryable = true
                                )
                                ModelAttempt.TryNext
                            }
                        }
                    } else {
                        val responseBodyString = bodyOrNull(response)
                        val parsedError = try {
                            JSONObject(responseBodyString).optJSONObject("error")?.optString("message")
                        } catch (_: Throwable) {
                            null
                        }
                        val isRateLimit = code == 429
                        val isAuthError = code == 400 || code == 401 || code == 402 || code == 403
                        Log.w(TAG, "OpenRouter model $model returned HTTP $code (${parsedError ?: ""})")

                        // Invalid key / out-of-credit: retrying is pointless.
                        if (isAuthError) {
                            return@withContext AIProviderResult.Failure(
                                providerName = name,
                                errorMessage = "OpenRouter API Key Invalid: ${parsedError ?: "HTTP $code"}",
                                isRetryable = false,
                                isRateLimitOrTimeout = false
                            )
                        }

                        if (isRateLimit) {
                            return@withContext AIProviderResult.Failure(
                                providerName = name,
                                errorMessage = "OpenRouter rate limit reached: ${parsedError ?: "Please try again later."}",
                                isRetryable = true,
                                isRateLimitOrTimeout = true
                            )
                        }

                        lastFailure = AIProviderResult.Failure(
                            providerName = name,
                            errorMessage = "OpenRouter model $model is unavailable (HTTP $code).",
                            isRetryable = true,
                            isRateLimitOrTimeout = false
                        )
                        ModelAttempt.TryNext
                    }
                }

                if (attempt is ModelAttempt.Ok) {
                    return@withContext AIProviderResult.Success(attempt.text, name)
                }
                Log.w(TAG, "OpenRouter model $model failed. Trying next fallback model...")
            }

            return@withContext lastFailure ?: AIProviderResult.Failure(
                providerName = name,
                errorMessage = "Unable to reach OpenRouter API after trying all fallback models.",
                isRetryable = true
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "OpenRouter request timed out", e)
            AIProviderResult.Failure(
                providerName = name,
                errorMessage = "OpenRouter request timed out.",
                isRetryable = true,
                isRateLimitOrTimeout = true,
                cause = e
            )
        } catch (e: IOException) {
            Log.w(TAG, "OpenRouter network I/O error", e)
            AIProviderResult.Failure(
                providerName = name,
                errorMessage = "Network connection error reaching OpenRouter.",
                isRetryable = true,
                cause = e
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in OpenRouterProvider", e)
            AIProviderResult.Failure(
                providerName = name,
                errorMessage = "Failed to communicate with OpenRouter.",
                isRetryable = true,
                cause = e
            )
        }
    }

    /** Reads the error body of a failed response without crashing. */
    private fun bodyOrNull(response: okhttp3.Response): String = try {
        response.body?.string().orEmpty()
    } catch (e: Throwable) {
        ""
    }

    /**
     * Builds the OpenAI-style "messages" array (system instruction first).
     */
    private fun buildJsonMessages(
        messages: List<ChatMessage>,
        systemInstruction: String
    ): JSONArray {
        val jsonMessages = JSONArray()
        if (systemInstruction.isNotBlank()) {
            jsonMessages.put(JSONObject().apply {
                put("role", "system")
                put("content", systemInstruction)
            })
        }

        val recentMessages = if (messages.size > 20) messages.takeLast(20) else messages
        for (msg in recentMessages) {
            if (msg.isError || msg.content.isBlank()) continue
            val role = when (msg.role) {
                ChatRole.USER -> "user"
                ChatRole.ASSISTANT -> "assistant"
                ChatRole.SYSTEM -> "system"
            }
            jsonMessages.put(JSONObject().apply {
                put("role", role)
                put("content", msg.content)
            })
        }
        return jsonMessages
    }

    /**
     * Reads the response body of a SUCCESSFUL request.
     *
     * OpenRouter streams exactly like Groq/OpenAI: `data: {"choices":[{"delta":
     * {"content":"..."}}]}` lines until `data: [DONE]`. If the body turns out
     * to be plain JSON (non-stream), it is parsed as a classic response.
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
                if (payload == "[DONE]") break
                try {
                    val obj = JSONObject(payload)
                    val choices = obj.optJSONArray("choices") ?: continue
                    if (choices.length() == 0) continue
                    val delta = choices.getJSONObject(0)
                        .optJSONObject("delta")
                        ?.optString("content", "")
                        .orEmpty()
                    if (delta.isNotEmpty()) {
                        text.append(delta)
                        onToken(delta)
                    }
                } catch (e: Throwable) {
                    // Malformed chunk - skip it, keep the stream alive.
                }
            } else {
                plainBuffer.append(line).append('\n')
            }
        }

        if (mode != 1) {
            try {
                val choices = JSONObject(plainBuffer.toString().trim()).optJSONArray("choices")
                val content = choices?.takeIf { it.length() > 0 }
                    ?.getJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    ?.trim()
                if (!content.isNullOrEmpty()) {
                    text.append(content)
                    onToken(content)
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
        private const val TAG = "OpenRouterProvider"
    }
}
