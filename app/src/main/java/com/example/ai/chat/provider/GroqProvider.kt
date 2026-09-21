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
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
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
                    put("max_tokens", 3072)
                }
                val request = Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestJson.toString().toRequestBody(mediaType))
                    .build()

                val failure: AIProviderResult.Failure = client.newCall(request).execute().use { response ->
                    val responseBodyString = response.body?.string().orEmpty()
                    val code = response.code

                    if (response.isSuccessful) {
                        if (responseBodyString.isNotBlank()) {
                            val choices = JSONObject(responseBodyString).optJSONArray("choices")
                            if (choices != null && choices.length() > 0) {
                                val content = choices.getJSONObject(0)
                                    .optJSONObject("message")
                                    ?.optString("content", "")
                                    ?.trim()
                                if (!content.isNullOrBlank()) {
                                    return@withContext AIProviderResult.Success(content, name)
                                }
                            }
                        }
                    } else {
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
                    }

                    AIProviderResult.Failure(
                        providerName = name,
                        errorMessage = "Groq model $model is unavailable (HTTP $code).",
                        isRetryable = true,
                        isRateLimitOrTimeout = false
                    )
                }

                lastFailure = failure
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

    companion object {
        private const val TAG = "GroqProvider"
    }
}
