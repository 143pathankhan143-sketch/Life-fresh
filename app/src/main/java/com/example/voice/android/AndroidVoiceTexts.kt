package com.example.voice.android

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import com.example.data.AppLanguage
import com.example.data.AppStrings
import com.example.voice.VocalDestination
import com.example.voice.VoiceTextDefaults
import com.example.voice.VoiceTexts
import java.util.Locale

/**
 * Android implementation of [VoiceTexts].
 *
 * Resolution order for every key:
 *  1. [AppStrings] with the app language forced — the same resolver the UI
 *     uses. The vc_* keys are listed in `AppStrings.STRING_RESOURCE_MAP`,
 *     which also keeps them alive through R8 resource shrinking (release
 *     builds have `isShrinkResources = true`), and picked up from downloaded
 *     language packs when one is installed.
 *  2. A direct resource lookup in the app language.
 *  3. The Hinglish defaults, and finally the key itself.
 *
 * A voice turn must never crash or go silent because of a missing line.
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

    /** Resolves a key to the app-language template, without placeholders applied. */
    private fun template(key: String): String {
        // 1. Shared app resolver (keeps resources through shrinking).
        val fromAppStrings = try {
            AppStrings.getString(context, key, languageCode().ifBlank { "en" })
        } catch (e: Throwable) {
            key
        }
        if (fromAppStrings.isNotBlank() && fromAppStrings != key) return fromAppStrings

        // 2. Direct resource lookup.
        try {
            val res = localizedResources()
            val id = res.getIdentifier(key, "string", context.packageName)
            if (id != 0) {
                val value = res.getString(id)
                if (value.isNotBlank()) return value
            }
        } catch (e: Throwable) {
            // fall through
        }

        // 3. Baked-in Hinglish line, else the key itself.
        return VoiceTextDefaults.hinglishMap()[key] ?: key
    }

    override fun get(key: String, vararg args: Any): String {
        val template = template(key)
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
        val fromAppStrings = try {
            AppStrings.getString(context, key, languageCode().ifBlank { "en" })
        } catch (e: Throwable) {
            key
        }
        if (fromAppStrings.isNotBlank() && fromAppStrings != key) return fromAppStrings
        return VoiceTextDefaults.hinglish().destinationName(destination)
    }
}
