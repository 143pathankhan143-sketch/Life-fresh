package com.example.ai.chat

import com.example.ai.chat.config.AIConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the model lists against the two ways they rot:
 *  a) a decommissioned id gets added back (it costs a wasted request on every
 *     chat turn, and used to surface as "API Key Invalid"),
 *  b) Groq's reasoning flag leaks onto a model that rejects it with HTTP 400.
 *
 * Sources (verified 2026-09-27):
 *  - https://console.groq.com/docs/models
 *  - https://console.groq.com/docs/deprecations
 *  - https://console.groq.com/docs/reasoning
 */
class AIConfigModelListTest {

    @Test
    fun groqListHasNoRetiredModels() {
        val retired = AIConfig.GROQ_RETIRED_MODEL_IDS
        val offenders = AIConfig.GROQ_TEXT_MODELS.filter { model ->
            retired.any { it.equals(model, ignoreCase = true) }
        }
        assertTrue("decommissioned Groq ids must never be listed: $offenders", offenders.isEmpty())
    }

    @Test
    fun groqListOnlyContainsKnownLiveModels() {
        // Pin the current lineup so any change is a deliberate, verified edit.
        assertEquals(
            listOf("openai/gpt-oss-20b", "qwen/qwen3.8-27b", "openai/gpt-oss-120b"),
            AIConfig.GROQ_TEXT_MODELS
        )
    }

    @Test
    fun groqOrderPutsTheLeastContendedModelFirst() {
        // A voice agent needs an answer, not the smartest model: the smallest /
        // least popular model must be tried first, the flagship last.
        assertEquals("openai/gpt-oss-20b", AIConfig.GROQ_TEXT_MODELS.first())
        assertEquals("openai/gpt-oss-120b", AIConfig.GROQ_TEXT_MODELS.last())
    }

    @Test
    fun reasoningEffortIsSentToTheFamiliesThatAcceptIt() {
        for (model in listOf("openai/gpt-oss-20b", "openai/gpt-oss-120b", "gpt-oss-20b")) {
            assertEquals("low", AIConfig.groqReasoningEffort(model))
        }
        // qwen3.8-27b accepts none/default/low/medium/high.
        assertEquals("low", AIConfig.groqReasoningEffort("qwen/qwen3.8-27b"))
        assertEquals("low", AIConfig.groqReasoningEffort("Qwen/Qwen3.8-27B"))
    }

    @Test
    fun reasoningEffortNeverLeaksToUnsupportedModels() {
        // It is a HTTP 400 on models that can not reason.
        for (model in listOf("llama-3.1-8b-instant", "llama-3.3-70b-versatile", "groq/compound-mini", "")) {
            assertNull("must not send reasoning_effort to '$model'", AIConfig.groqReasoningEffort(model))
        }
    }

    @Test
    fun geminiListHasALowContentionFirstEntryAndNoDeadFamilies() {
        // 2.0 family shut down 2026-06-01; 2.5 is closed to new projects
        // (2026-09-18). Trying any of them would waste a request per turn.
        assertEquals("gemini-3.5-flash-lite", AIConfig.GEMINI_TEXT_MODELS.first())
        AIConfig.GEMINI_TEXT_MODELS.forEach { model ->
            assertFalse("dead Gemini family in list: $model", model.startsWith("gemini-2."))
            assertFalse("retired Gemini model in list: $model", model.contains("gemini-1.5") || model.contains("gemini-pro"))
        }
        // Two entries => two separate free-tier quotas before a turn fails.
        assertEquals(2, AIConfig.GEMINI_TEXT_MODELS.size)
        assertEquals(2, AIConfig.GEMINI_TEXT_MODELS.distinct().size)
    }

    @Test
    fun openRouterAndGeminiListsAreNotEmptyAndCarryNoRetiredIds() {
        assertTrue(AIConfig.OPENROUTER_TEXT_MODELS.isNotEmpty())
        assertTrue(AIConfig.GEMINI_TEXT_MODELS.isNotEmpty())
        val openRouterRetired = AIConfig.GROQ_RETIRED_MODEL_IDS
        AIConfig.OPENROUTER_TEXT_MODELS.forEach { model ->
            assertFalse("stale id in OpenRouter list: $model", openRouterRetired.contains(model))
        }
    }

    @Test
    fun theGroqTokenCapIsSaneForAFreeTierVoiceAgent() {
        assertTrue(AIConfig.GROQ_MAX_COMPLETION_TOKENS in 1_024..16_384)
        assertNotNull(AIConfig.GROQ_TEXT_MODELS.firstOrNull())
    }
}
