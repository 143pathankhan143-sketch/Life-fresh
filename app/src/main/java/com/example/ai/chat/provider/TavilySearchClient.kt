package com.example.ai.chat.provider

import com.example.ai.chat.config.AIConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal client for the Tavily web-search API (https://tavily.com) - the
 * search backend used when the user asks LifeFresh AI to look something up
 * on the internet.
 *
 * Contract: POST https://api.tavily.com/search
 *   Authorization: Bearer tvly-...
 *   body: {"query": "...", "search_depth": "basic", "max_results": N}
 *   200 -> {"query":..., "results": [{"title":..., "url":..., "content":...}]}
 *
 * Every failure path degrades to an EMPTY result list: a search problem must
 * never crash or break the chat.
 */
object TavilySearchClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    data class SearchResult(
        val title: String,
        val url: String,
        val content: String
    )

    fun hasKey(): Boolean = AIConfig.tavilyApiKey.isNotBlank()

    suspend fun search(query: String, maxResults: Int = 5): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val key = AIConfig.tavilyApiKey
            if (key.isBlank() || query.isBlank()) {
                return@withContext emptyList()
            }
            try {
                val payload = JSONObject().apply {
                    put("query", query)
                    put("search_depth", "basic")
                    put("max_results", maxResults)
                }.toString()
                val request = Request.Builder()
                    .url("https://api.tavily.com/search")
                    .header("Authorization", "Bearer $key")
                    .header("Content-Type", "application/json")
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext emptyList()
                    }
                    val body = response.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val results = json.optJSONArray("results") ?: return@withContext emptyList()
                    val out = mutableListOf<SearchResult>()
                    val count = minOf(results.length(), maxResults)
                    for (i in 0 until count) {
                        val r = results.getJSONObject(i)
                        val url = r.optString("url", "").trim()
                        if (url.isBlank()) continue
                        out.add(
                            SearchResult(
                                title = r.optString("title", "").trim(),
                                url = url,
                                content = r.optString("content", "").trim()
                            )
                        )
                    }
                    out
                }
            } catch (t: Throwable) {
                emptyList()
            }
        }
}

/**
 * First-pass gate deciding whether the user's message is plausibly asking for
 * a WEB search. Deliberately narrow: only explicit search words count, because
 * time words alone ("aaj", "today") would also match normal CRM questions like
 * "aaj ke top calls batao".
 *
 * The gate is only a cheap pre-filter - the AI itself decides afterwards
 * (via the query-extraction step) whether a search is really needed, and can
 * answer with NONE to skip it.
 */
object SearchIntentGate {

    private val TRIGGERS = listOf(
        "search",
        "google",
        "web pe",
        "web se",
        "web par",
        "internet pe",
        "internet se",
        "internet par",
        "internet on",
        "online",
        "look up",
        "lookup",
        "khojo",
        "dhundo",
        "dhoondo",
        "khabar",
        "news",
        "live score",
        "live match",
        "live hai",
        "current rate",
        "current price",
        "aaj ka rate",
        "aaj ki price",
        "kaun jeeta",
        "result kya hai"
    )

    fun looksLikeSearchRequest(message: String): Boolean {
        val text = message.lowercase()
        return TRIGGERS.any { text.contains(it) }
    }
}
