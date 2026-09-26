package com.example.voice.android

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import com.example.data.AppLanguage
import com.example.voice.VocalDestination
import com.example.voice.VoiceTextDefaults
import com.example.voice.VoiceTexts
import java.util.Locale

/**
 * Android implementation of [VoiceTexts].
 *
 * Looks every key up by name in the app's own string resources with the app
 * language forced (values / values-hi / values-ta / values-ur), so the voice
 * layer speaks the same language the UI shows. Any miss falls back to the
 * Hinglish defaults and finally to the key itself — a voice turn must never
 * crash or go silent because of a missing translation.
 *
 * [languageCode] is a provider (not a value) so a language switch mid-session
 * is picked up immediately.
 */
class AndroidVoiceTexts(
    private val context: Context,
    private val languageCode: () -> String = { AppLanguage.ENGLISH.code }
) : VoiceTexts {

    private fun localizedResources(): Resources = try {
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(languageCode().ifBlank { "en" }))
        context.createConfigurationContext(config).resources
    } catch (e: Throwable) {
        context.resources
    }

    private fun resource(key: String): String? = try {
        val res = localizedResources()
        val id = res.getIdentifier(key, "string", context.packageName)
        if (id == 0) null else res.getString(id)
    } catch (e: Throwable) {
        null
    }

    override fun get(key: String, vararg args: Any): String {
        val template = resource(key)
            ?: VoiceTextDefaults.hinglishMap()[key]
            ?: key
        if (args.isEmpty()) return template
        return try {
            String.format(Locale.ROOT, template, *args)
        } catch (e: Throwable) {
            template
        }
    }

    override fun destinationName(destination: VocalDestination): String {
        val key = when (destination) {
            VocalDestination.DASHBOARD -> "nav_dashboard"
            VocalDestination.LEADS -> "nav_leads"
            VocalDestination.AI_CHAT, VocalDestination.AI_HISTORY -> "nav_ai"
            VocalDestination.REPORTS -> "nav_reports"
            VocalDestination.SETTINGS -> "nav_settings"
        }
        return resource(key) ?: VoiceTextDefaults.hinglish().destinationName(destination)
    }
}
