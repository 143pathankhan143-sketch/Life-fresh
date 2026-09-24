package com.example.data.security

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AIQuotaManager {

    const val MAX_DAILY_FREE_QUOTA = 20
    private const val PREFS_NAME = "daily_ai_usage_prefs"
    private const val KEY_CUSTOM_GEMINI_KEY = "custom_gemini_api_key"
    private const val KEY_CUSTOM_GROQ_KEY = "custom_groq_api_key"
    private const val KEY_CUSTOM_OPENROUTER_KEY = "custom_openrouter_api_key"
    private const val KEY_CUSTOM_TAVILY_KEY = "custom_tavily_api_key"
    private const val KEY_VOICE_REPLY_ENABLED = "ai_voice_reply_enabled"
    private const val KEY_BOLO_MODE_ENABLED = "ai_bolo_mode_enabled"
    private const val KEY_AGENT_MODE_ENABLED = "agent_mode_enabled"
    private const val KEY_USAGE_COUNT = "daily_usage_count"
    private const val KEY_LAST_USAGE_DATE = "last_usage_date"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @SuppressLint("HardwareIds")
    fun getDeviceId(context: Context): String {
        return try {
            val id = Settings.Secure.getString(
                context.applicationContext.contentResolver,
                Settings.Secure.ANDROID_ID
            )
            if (!id.isNullOrBlank()) id else "unknown_device"
        } catch (e: Exception) {
            "unknown_device"
        }
    }

    private fun getTodayDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    @Volatile
    private var cachedCustomGeminiKey: String? = null
    @Volatile
    private var isGeminiCacheInitialized: Boolean = false

    fun getCustomGeminiKey(context: Context): String? {
        if (isGeminiCacheInitialized) {
            return cachedCustomGeminiKey
        }
        val key = getPrefs(context).getString(KEY_CUSTOM_GEMINI_KEY, null)?.trim()
        val result = if (!key.isNullOrBlank()) key else null
        cachedCustomGeminiKey = result
        isGeminiCacheInitialized = true
        return result
    }

    fun saveCustomGeminiKey(context: Context, key: String?) {
        val cleanKey = key?.trim()
        val result = if (!cleanKey.isNullOrBlank()) cleanKey else null
        cachedCustomGeminiKey = result
        isGeminiCacheInitialized = true
        getPrefs(context).edit().apply {
            if (result == null) {
                remove(KEY_CUSTOM_GEMINI_KEY)
            } else {
                putString(KEY_CUSTOM_GEMINI_KEY, result)
            }
            apply()
        }
    }

    @Volatile
    private var cachedCustomGroqKey: String? = null
    @Volatile
    private var isGroqCacheInitialized: Boolean = false

    fun getCustomGroqKey(context: Context): String? {
        if (isGroqCacheInitialized) {
            return cachedCustomGroqKey
        }
        val key = getPrefs(context).getString(KEY_CUSTOM_GROQ_KEY, null)?.trim()
        val result = if (!key.isNullOrBlank()) key else null
        cachedCustomGroqKey = result
        isGroqCacheInitialized = true
        return result
    }

    fun saveCustomGroqKey(context: Context, key: String?) {
        val cleanKey = key?.trim()
        val result = if (!cleanKey.isNullOrBlank()) cleanKey else null
        cachedCustomGroqKey = result
        isGroqCacheInitialized = true
        getPrefs(context).edit().apply {
            if (result == null) {
                remove(KEY_CUSTOM_GROQ_KEY)
            } else {
                putString(KEY_CUSTOM_GROQ_KEY, result)
            }
            apply()
        }
    }

    @Volatile
    private var cachedCustomOpenRouterKey: String? = null
    @Volatile
    private var isOpenRouterCacheInitialized: Boolean = false

    fun getCustomOpenRouterKey(context: Context): String? {
        if (isOpenRouterCacheInitialized) {
            return cachedCustomOpenRouterKey
        }
        val key = getPrefs(context).getString(KEY_CUSTOM_OPENROUTER_KEY, null)?.trim()
        val result = if (!key.isNullOrBlank()) key else null
        cachedCustomOpenRouterKey = result
        isOpenRouterCacheInitialized = true
        return result
    }

    fun saveCustomOpenRouterKey(context: Context, key: String?) {
        val cleanKey = key?.trim()
        val result = if (!cleanKey.isNullOrBlank()) cleanKey else null
        cachedCustomOpenRouterKey = result
        isOpenRouterCacheInitialized = true
        getPrefs(context).edit().apply {
            if (result == null) {
                remove(KEY_CUSTOM_OPENROUTER_KEY)
            } else {
                putString(KEY_CUSTOM_OPENROUTER_KEY, result)
            }
            apply()
        }
    }

    @Volatile
    private var cachedCustomTavilyKey: String? = null
    @Volatile
    private var isTavilyCacheInitialized: Boolean = false

    fun getCustomTavilyKey(context: Context): String? {
        if (isTavilyCacheInitialized) {
            return cachedCustomTavilyKey
        }
        val key = getPrefs(context).getString(KEY_CUSTOM_TAVILY_KEY, null)?.trim()
        val result = if (!key.isNullOrBlank()) key else null
        cachedCustomTavilyKey = result
        isTavilyCacheInitialized = true
        return result
    }

    fun saveCustomTavilyKey(context: Context, key: String?) {
        val cleanKey = key?.trim()
        val result = if (!cleanKey.isNullOrBlank()) cleanKey else null
        cachedCustomTavilyKey = result
        isTavilyCacheInitialized = true
        getPrefs(context).edit().apply {
            if (result == null) {
                remove(KEY_CUSTOM_TAVILY_KEY)
            } else {
                putString(KEY_CUSTOM_TAVILY_KEY, result)
            }
            apply()
        }
    }

    /** Agent Mode: when ON, the AI's lead actions run without a confirmation card. */
    fun isAgentModeEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_AGENT_MODE_ENABLED, false)

    fun setAgentModeEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AGENT_MODE_ENABLED, enabled).apply()
    }

    /** Voice replies (TTS): the AI speaks its answers aloud for users who
     *  cannot read. Uses the phone's built-in TTS engine. */
    fun isVoiceReplyEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_VOICE_REPLY_ENABLED, false)

    fun setVoiceReplyEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_VOICE_REPLY_ENABLED, enabled).apply()
    }

    /** Bolo mode: hands-free loop - the app listens again automatically after
     *  every answer and applies card actions on a spoken "haan"/"nahi". */
    fun isBoloModeEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_BOLO_MODE_ENABLED, false)

    fun setBoloModeEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_BOLO_MODE_ENABLED, enabled).apply()
    }

    private fun checkAndResetDailyUsageIfNeeded(prefs: SharedPreferences): Int {
        val today = getTodayDateString()
        val lastDate = prefs.getString(KEY_LAST_USAGE_DATE, null)
        return if (lastDate != today) {
            prefs.edit()
                .putString(KEY_LAST_USAGE_DATE, today)
                .putInt(KEY_USAGE_COUNT, 0)
                .apply()
            0
        } else {
            prefs.getInt(KEY_USAGE_COUNT, 0)
        }
    }

    fun canExecuteAI(context: Context): Boolean {
        // 1. If user has any BYOK key (Gemini or Groq) configured, ALWAYS return true (unlimited)
        if (isUnlimited(context)) {
            return true
        }

        // 2. Otherwise, check daily count against MAX_DAILY_FREE_QUOTA (20)
        val prefs = getPrefs(context)
        val currentCount = checkAndResetDailyUsageIfNeeded(prefs)
        return currentCount < MAX_DAILY_FREE_QUOTA
    }

    fun incrementUsage(context: Context) {
        val prefs = getPrefs(context)
        val currentCount = checkAndResetDailyUsageIfNeeded(prefs)
        prefs.edit()
            .putString(KEY_LAST_USAGE_DATE, getTodayDateString())
            .putInt(KEY_USAGE_COUNT, currentCount + 1)
            .apply()
    }

    fun getRemainingQuota(context: Context): Int {
        val prefs = getPrefs(context)
        val currentCount = checkAndResetDailyUsageIfNeeded(prefs)
        return (MAX_DAILY_FREE_QUOTA - currentCount).coerceAtLeast(0)
    }

    fun isUnlimited(context: Context): Boolean {
        // Any BYOK key (Gemini or Groq) unlocks unlimited queries.
        return !getCustomGeminiKey(context).isNullOrBlank() ||
            !getCustomGroqKey(context).isNullOrBlank()
    }

    fun getDailyUsageCount(context: Context): Int {
        val prefs = getPrefs(context)
        return checkAndResetDailyUsageIfNeeded(prefs)
    }
}
