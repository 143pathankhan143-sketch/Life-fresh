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
