package com.example.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.Normalizer

/**
 * Guards the spoken lines against the two mistakes that a device would hide:
 * a key that only exists in one locale, and a confirmation prompt that teaches
 * a phrase the gate would actually reject.
 */
class VoiceLocaleStringsTest {

    private val locales = listOf("values", "values-hi", "values-ta", "values-ur")

    private fun resDir(): File {
        System.getProperty("lifefresh.res.dir")?.let { return File(it) }
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, "app/src/main/res")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        return File("src/main/res")
    }

    /** Locates a repo file by walking up from the working directory. */
    private fun sourceFile(relative: String): File {
        System.getProperty("lifefresh.repo.dir")?.let { repo ->
            val direct = File(repo, relative)
            if (direct.isFile) return direct
        }
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return File(relative)
    }

    private fun entries(locale: String): Map<String, String> {
        val file = File(resDir(), "$locale/strings.xml")
        assertTrue("missing ${file.path}", file.isFile)
        val regex = Regex("<string name=\"([^\"]+)\"\\s*>([\\s\\S]*?)</string>")
        return regex.findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun placeholders(value: String): List<String> =
        Regex("%[0-9]+\\$[a-z]").findAll(value).map { it.value }.sorted().toList()

    /** aapt-style unescaping of the bits we care about. */
    private fun unescape(value: String): String = Normalizer.normalize(
        value.replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\"),
        Normalizer.Form.NFC
    )

    @Test
    fun everyLocaleHasEveryVoiceString() {
        for (locale in locales) {
            val entries = entries(locale)
            val missing = VoiceTextKeys.ALL.filter { it !in entries }
            assertTrue("$locale is missing: $missing", missing.isEmpty())
        }
    }

    @Test
    fun theHinglishFallbackCoversEveryKeyToo() {
        val map = VoiceTextDefaults.hinglishMap()
        val missing = VoiceTextKeys.ALL.filter { it !in map }
        assertTrue("fallback is missing: $missing", missing.isEmpty())
    }

    @Test
    fun placeholdersMatchTheDefaultLocale() {
        val reference = entries("values")
        for (locale in locales.drop(1)) {
            val actual = entries(locale)
            for (key in VoiceTextKeys.ALL) {
                assertEquals(
                    "$locale/$key placeholders",
                    placeholders(reference.getValue(key)),
                    placeholders(actual.getValue(key))
                )
            }
        }
    }

    @Test
    fun everyLocaleTeachesAPhraseTheGateActuallyAccepts() {
        for (locale in locales) {
            val raw = entries(locale).getValue(VoiceTextKeys.CLOUD_DELETE_PROMPT_3)
            val quoted = Regex("\\\\'(.*?)\\\\'").find(raw)
            assertTrue("$locale: vc_cloud_delete_3 has no quoted exact phrase", quoted != null)
            val phrase = VoiceConfirmGate.normalizePhrase(unescape(quoted!!.groupValues[1]))
            assertTrue(
                "$locale teaches '$phrase', which TRIPLE_ACCEPT_PHRASES rejects",
                phrase in TRIPLE_ACCEPT_PHRASES
            )
        }
    }

    @Test
    fun everyVoiceKeyIsListedInTheAppStringsMap() {
        // Release builds run with isShrinkResources = true. Keys resolved only
        // by name (getIdentifier) would be stripped; listing them in
        // AppStrings.STRING_RESOURCE_MAP keeps them alive (and localised).
        val appStrings = sourceFile("app/src/main/java/com/example/data/AppStrings.kt").readText()
        // Exact "key to R.string.key" mapping — a typo on either side would
        // compile only if the R field existed, so pin both halves here.
        val missing = VoiceTextKeys.ALL.filter {
            !appStrings.contains("\"$it\" to com.example.R.string.$it,")
        }
        assertTrue("missing/wrong in STRING_RESOURCE_MAP (R8 would shrink them): $missing", missing.isEmpty())
    }

    @Test
    fun theSpecifiedPhraseIsStillAccepted() {
        assertTrue(TRIPLE_ACCEPT_PHRASES.contains("haan delete karo"))
        assertTrue(TRIPLE_ACCEPT_PHRASES.contains("yes delete"))
    }

    @Test
    fun noVoiceLineLeaksAnEscapedPlaceholder() {
        // A stray "\\$" would make Android's String.format throw at runtime.
        for (locale in locales) {
            for ((key, value) in entries(locale)) {
                if (!key.startsWith("vc_")) continue
                assertTrue("$locale/$key contains an escaped placeholder", !value.contains("\\$"))
            }
        }
    }
}
