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

class GroqProvider(
    private val apiKeyProvider: () -> String = { AIConfig.groqApiKey },
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val modelName: String = AIConfig.GROQ_TEXT_MODELS.first()
) : AIProvider {
    override val name: String = "Groq"
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
                errorMessage = "Groq API key is not configured.",
                isRetryable = false,
                isRateLimitOrTimeout = false
            )
        }

        try {
            val jsonMessages = buildJsonMessages(messages, systemInstruction)
            val mediaType = "application/json; charset=utf-8".toMediaType()

            // Fallback chain: try the preferred model first, then the rest of
            // the current production list. Single source of truth:
            // AIConfig.GROQ_TEXT_MODELS (never add deprecated/preview models).
            val models = AIConfig.GROQ_TEXT_MODELS
                .let { if (it.contains(modelName)) it else listOf(modelName) + it }
                .distinct()

            var lastFailure: AIProviderResult.Failure? = null

            for (model in models) {
                val requestJson = JSONObject().apply {
                    put("model", model)
                    put("messages", jsonMessages)
                    put("temperature", 0.7)
                    // Reasoning models (gpt-oss) spend part of max_tokens on
                    // internal thinking - 3072 caused silently truncated
                    // replies. 8192 leaves headroom for the visible answer.
                    put("max_tokens", 8192)
                    // Stream tokens so the chat UI can show the reply as it is
                    // generated (ChatGPT-style). readStream() degrades to the
                    // classic one-shot JSON response automatically.
                    put("stream", true)
                }
                val request = Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestJson.toString().toRequestBody(mediaType))
                    .build()

                val attempt: ModelAttempt = client.newCall(request).execute().use { response ->
                    val code = response.code

                    if (response.isSuccessful) {
                        val body = response.body
                        if (body == null) {
                            lastFailure = AIProviderResult.Failure(
                                providerName = name,
                                errorMessage = "Groq model $model returned an empty response.",
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
                                    errorMessage = "Groq model $model returned no text (HTTP $code).",
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
                        val isAuthError = code == 400 || code == 401 || code == 403
                        Log.w(TAG, "Groq model $model returned HTTP $code (${parsedError ?: ""})")

                        // Invalid key / account problem: retrying other models
                        // or the next request is pointless - fail fast.
                        if (isAuthError) {
                            return@withContext AIProviderResult.Failure(
                                providerName = name,
                                errorMessage = "Groq API Key Invalid: ${parsedError ?: "HTTP $code"}",
                                isRetryable = false,
                                isRateLimitOrTimeout = false
                            )
                        }

                        // Rate limits are per-account, and timeouts affect all
                        // models equally - stop trying the rest.
                        if (isRateLimit) {
                            return@withContext AIProviderResult.Failure(
                                providerName = name,
                                errorMessage = "Groq rate limit reached: ${parsedError ?: "Please try again later."}",
                                isRetryable = true,
                                isRateLimitOrTimeout = true
                            )
                        }

                        lastFailure = AIProviderResult.Failure(
                            providerName = name,
                            errorMessage = "Groq model $model is unavailable (HTTP $code).",
                            isRetryable = true,
                            isRateLimitOrTimeout = false
                        )
                        ModelAttempt.TryNext
                    }
                }

                if (attempt is ModelAttempt.Ok) {
                    return@withContext AIProviderResult.Success(attempt.text, name)
                }
                Log.w(TAG, "Groq model $model failed. Trying next fallback model...")
            }

            return@withContext lastFailure ?: AIProviderResult.Failure(
                providerName = name,
                errorMessage = "Unable to reach Groq API after trying all fallback models.",
                isRetryable = true
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "Groq request timed out", e)
            AIProviderResult.Failure(
                providerName = name,
                errorMessage = "Groq request timed out.",
                isRetryable = true,
                isRateLimitOrTimeout = true,
                cause = e
            )
        } catch (e: IOException) {
            Log.w(TAG, "Groq network I/O error", e)
            AIProviderResult.Failure(
                providerName = name,
                errorMessage = "Network connection error reaching Groq.",
                isRetryable = true,
                cause = e
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in GroqProvider", e)
            AIProviderResult.Failure(
                providerName = name,
                errorMessage = "Failed to communicate with Groq.",
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
     * Normal case (stream=true): server-sent events - lines like
     * `data: {"choices":[{"delta":{"content":"..."}}]}` until `data: [DONE]`.
     * Each delta is emitted through [onToken] immediately.
     *
     * Defensive fallback: if the first non-empty line is plain JSON instead
     * of a `data:` line, the whole body is parsed as a classic
     * chat-completions response and delivered as a single token.
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
            // Plain (non-SSE) body: parse exactly like the classic endpoint.
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
        private const val TAG = "GroqProvider"
    }
}
