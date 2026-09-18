package com.example.ai.chat

import com.example.ai.chat.model.ChatMessage
import com.example.ai.chat.model.ChatRole
import com.example.ai.chat.provider.AIProvider
import com.example.ai.chat.provider.AIProviderResult
import com.example.ai.chat.provider.AIProviderRouter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies how the router reports failures when every provider is down,
 * so the user sees the real cause (rate limit / network detail) instead of
 * only a generic "check your connection" message.
 */
class AIProviderRouterErrorTest {

    private class FakeFailingProvider(
        override val name: String,
        override val isConfigured: Boolean = true,
        private val failure: AIProviderResult.Failure
    ) : AIProvider {
        var callCount = 0
        override suspend fun generateResponse(
            messages: List<ChatMessage>,
            systemInstruction: String
        ): AIProviderResult {
            callCount++
            return failure
        }
    }

    private val messages = listOf(ChatMessage(role = ChatRole.USER, content = "hi"))

    @Test
    fun allProvidersRetryableFailure_reportsLastFailureDetail() = runTest {
        val a = FakeFailingProvider(
            "A",
            AIProviderResult.Failure("A", "Network connection error reaching A.", isRetryable = true)
        )
        val b = FakeFailingProvider(
            "B",
            AIProviderResult.Failure("B", "B returned status 503", isRetryable = true)
        )

        val result = AIProviderRouter(listOf(a, b)).routeChat(messages, "sys")
        val failure = result as AIProviderResult.Failure

        assertTrue(failure.isRetryable)
        // Both providers were tried (both retryable)
        assertEquals(1, a.callCount)
        assertEquals(1, b.callCount)
        // The summary surfaces the real cause instead of only "check your connection"
        assertTrue(
            "Expected last failure detail in: ${failure.errorMessage}",
            failure.errorMessage.contains("B returned status 503")
        )
    }

    @Test
    fun rateLimitOrTimeoutFailure_reportsBusyMessage() = runTest {
        val provider = FakeFailingProvider(
            "A",
            AIProviderResult.Failure(
                "A",
                "Gemini request timed out.",
                isRetryable = true,
                isRateLimitOrTimeout = true
            )
        )

        val result = AIProviderRouter(listOf(provider)).routeChat(messages, "sys")
        val failure = result as AIProviderResult.Failure

        assertTrue(failure.isRetryable)
        assertTrue(failure.isRateLimitOrTimeout)
        assertTrue(
            "Expected busy/rate-limit wording in: ${failure.errorMessage}",
            failure.errorMessage.contains("busy")
        )
    }

    @Test
    fun nonRetryableAuthFailure_stopsAndReturnsExactError() = runTest {
        val failing = FakeFailingProvider(
            "A",
            AIProviderResult.Failure(
                "A",
                "API Key Invalid: HTTP 401",
                isRetryable = false,
                isRateLimitOrTimeout = false
            )
        )
        val neverCalled = FakeFailingProvider(
            "B",
            AIProviderResult.Failure("B", "should never be called", isRetryable = true)
        )

        val result = AIProviderRouter(listOf(failing, neverCalled)).routeChat(messages, "sys")
        val failure = result as AIProviderResult.Failure

        assertFalse(failure.isRetryable)
        assertEquals("API Key Invalid: HTTP 401", failure.errorMessage)
        // The router must stop at the first non-retryable (auth) failure
        assertEquals(0, neverCalled.callCount)
    }

    @Test
    fun successShortCircuitsRemainingProviders() = runTest {
        val ok = object : AIProvider {
            override val name = "A"
            override val isConfigured = true
            override suspend fun generateResponse(
                messages: List<ChatMessage>,
                systemInstruction: String
            ): AIProviderResult = AIProviderResult.Success("hello", "A")
        }
        val second = FakeFailingProvider(
            "B",
            AIProviderResult.Failure("B", "unused", isRetryable = true)
        )

        val result = AIProviderRouter(listOf(ok, second)).routeChat(messages, "sys")
        val success = result as AIProviderResult.Success

        assertEquals("hello", success.text)
        assertEquals(0, second.callCount)
    }
}
