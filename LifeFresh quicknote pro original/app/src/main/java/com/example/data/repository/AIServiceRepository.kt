package com.example.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.ai.chat.config.AIConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around the Google Generative Language API used for
 * API-key validation from Settings ("Test connection").
 *
 * The chatbot itself talks to Gemini through its own provider layer
 * (com.example.ai.chat.provider), NOT through this class.
 */
class AIServiceRepository {

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Tests a custom Gemini API key with a lightweight prompt ("Say 'Connected'")
     * to immediately verify key validity.
     */
    suspend fun testGeminiKey(apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        val trimmed = apiKey.trim()
        if (trimmed.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("API Key cannot be empty."))
        }

        // Step 1: Probe Google Generative Language API models endpoint with key to test validity
        try {
            val probeUrl = "https://generativelanguage.googleapis.com/v1beta/models?key=$trimmed"
            val probeRequest = Request.Builder().url(probeUrl).get().build()
            okHttpClient.newCall(probeRequest).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val code = response.code

                if (code == 400 || code == 401 || code == 403) {
                    val errMsg = try {
                        JSONObject(body).optJSONObject("error")?.optString("message")
                    } catch (_: Throwable) { null } ?: "API Key is invalid or not recognized by Google."
                    return@withContext Result.failure(Exception(errMsg))
                }

                if (response.isSuccessful) {
                    // Key is verified! Check available models that support generateContent
                    val supportedModels = mutableListOf<String>()
                    try {
                        val modelsArray = JSONObject(body).optJSONArray("models")
                        if (modelsArray != null) {
                            for (i in 0 until modelsArray.length()) {
                                val m = modelsArray.getJSONObject(i)
                                val name = m.optString("name").removePrefix("models/")
                                val methods = m.optJSONArray("supportedGenerationMethods")
                                val canGenerate = methods != null && (0 until methods.length()).any {
                                    methods.optString(it) == "generateContent"
                                }
                                if (canGenerate) {
                                    supportedModels.add(name)
                                }
                            }
                        }
                    } catch (_: Throwable) {}

                    // Priority order for modern Gemini models (Fastest first).
                    // Single source of truth: AIConfig.GEMINI_TEXT_MODELS.
                    val preferredOrder = AIConfig.GEMINI_TEXT_MODELS

                    val selectedModel = preferredOrder.firstOrNull { supportedModels.contains(it) }
                        ?: supportedModels.firstOrNull { it.contains("flash") }
                        ?: supportedModels.firstOrNull()
                        ?: preferredOrder.first()

                    val answer = callSingleGeminiModel(trimmed, selectedModel, "Say 'Connected'")
                    return@withContext Result.success("Connected successfully ($selectedModel): $answer")
                }
            }
        } catch (e: Exception) {
            if (e.message?.contains("API Key", ignoreCase = true) == true) {
                return@withContext Result.failure(e)
            }
            Log.w(TAG, "Probe check had issue, falling back to direct test: ${e.message}")
        }

        // Fallback: direct test using modern active models
        try {
            val response = callDirectGeminiApi(trimmed, "Say 'Connected'")
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun callSingleGeminiModel(apiKey: String, model: String, prompt: String): String {
        val requestJson = JSONObject().apply {
            val partsArray = JSONArray().apply {
                put(JSONObject().apply { put("text", prompt) })
            }
            val contentsArray = JSONArray().apply {
                put(JSONObject().apply { put("parts", partsArray) })
            }
            put("contents", contentsArray)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = requestJson.toString().toRequestBody(mediaType)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val bodyString = response.body?.string().orEmpty()
            val code = response.code

            if (response.isSuccessful) {
                val json = JSONObject(bodyString)
                val candidates = json.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val firstCandidate = candidates.getJSONObject(0)
                    val parts = firstCandidate.optJSONObject("content")?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val sb = StringBuilder()
                        for (i in 0 until parts.length()) {
                            sb.append(parts.getJSONObject(i).optString("text", ""))
                        }
                        val text = sb.toString().trim()
                        if (text.isNotBlank()) return text
                    }
                }
                return "Connected"
            }

            val parsedError = try {
                JSONObject(bodyString).optJSONObject("error")?.optString("message")
            } catch (_: Throwable) { null }

            throw Exception(parsedError ?: "Gemini returned HTTP $code for model $model")
        }
    }

    private fun callDirectGeminiApi(apiKey: String, prompt: String): String {
        val models = AIConfig.GEMINI_TEXT_MODELS

        val requestJson = JSONObject().apply {
            val partsArray = JSONArray().apply {
                put(JSONObject().apply { put("text", prompt) })
            }
            val contentsArray = JSONArray().apply {
                put(JSONObject().apply { put("parts", partsArray) })
            }
            put("contents", contentsArray)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = requestJson.toString().toRequestBody(mediaType)

        var lastException: Exception? = null

        for (model in models) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            try {
                okHttpClient.newCall(request).execute().use { response ->
                    val bodyString = response.body?.string().orEmpty()
                    val code = response.code

                    if (response.isSuccessful) {
                        val json = JSONObject(bodyString)
                        val candidates = json.optJSONArray("candidates")
                        if (candidates != null && candidates.length() > 0) {
                            val firstCandidate = candidates.getJSONObject(0)
                            val parts = firstCandidate.optJSONObject("content")?.optJSONArray("parts")
                            if (parts != null && parts.length() > 0) {
                                val sb = StringBuilder()
                                for (i in 0 until parts.length()) {
                                    sb.append(parts.getJSONObject(i).optString("text", ""))
                                }
                                val text = sb.toString().trim()
                                if (text.isNotBlank()) return text
                            }
                        }
                        throw Exception("No text candidate returned by Gemini ($model).")
                    }

                    val parsedError = try {
                        JSONObject(bodyString).optJSONObject("error")?.optString("message")
                    } catch (_: Throwable) {
                        null
                    }

                    // HTTP 400 / 401 / 403: API Key Invalid or expired - fail fast with detailed error
                    if (code == 400 || code == 401 || code == 403) {
                        val details = parsedError ?: "HTTP $code"
                        throw Exception("API Key Invalid: $details")
                    }

                    // HTTP 429: Rate limit / Quota issue
                    if (code == 429) {
                        val details = parsedError ?: "Rate limit or quota reached."
                        throw Exception("Quota Limit: $details")
                    }

                    // HTTP 404 (Model Not Found) or 503 (Overloaded) -> retry next model in fallback list
                    if (code == 404 || code == 503) {
                        Log.w(TAG, "Model $model returned HTTP $code ($parsedError). Retrying next fallback model...")
                        lastException = Exception("Model $model unavailable (HTTP $code): ${parsedError ?: "Overloaded or not found"}")
                        return@use // continue to next model
                    }

                    // Any other HTTP error
                    val errorMsg = parsedError ?: "Gemini returned HTTP status $code"
                    lastException = Exception("Gemini error ($model): $errorMsg")
                }
            } catch (e: Exception) {
                if (e.message?.startsWith("API Key Invalid") == true || e.message?.startsWith("Quota Limit") == true) {
                    throw e
                }
                Log.w(TAG, "Call to model $model failed: ${e.message}")
                lastException = e
            }
        }

        throw lastException ?: Exception("Unable to reach Google Gemini API after trying all fallback models.")
    }

    companion object {
        private const val TAG = "AIServiceRepository"
    }
}
