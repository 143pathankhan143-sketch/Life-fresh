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
 * "AI service is busy" recovery at the router level: a busy provider must not
 * end the request while another provider can still answer, and an
 * everything-is-busy burst gets one quiet automatic retry before the user sees
 * the error bubble.
 */
class AIProviderRouterRecoveryTest {

    private val messages = listOf(ChatMessage(role = ChatRole.USER, content = "hi"))

    /** Fails [failures] times, then answers - like a rate limit that clears. */
    private class FlakyProvider(
        override val name: String,
        private val failures: Int,
        private val failure: AIProviderResult.Failure,
        private val reply: String = "answered by $name"
    ) : AIProvider {
        override val isConfigured = true
        var callCount = 0
            private set

        override suspend fun generateResponse(
            messages: List<ChatMessage>,
            systemInstruction: String
        ): AIProviderResult = generateStreamingResponse(messages, systemInstruction) {}

        override suspend fun generateStreamingResponse(
            messages: List<ChatMessage>,
            systemInstruction: String,
            onToken: (String) -> Unit
        ): AIProviderResult {
            callCount++
            return if (callCount <= failures) {
                failure
            } else {
                onToken(reply)
                AIProviderResult.Success(reply, name)
            }
        }
    }

    private fun busyFailure(name: String) = AIProviderResult.Failure(
        providerName = name,
        errorMessage = "$name rate limit reached (HTTP 429).",
        isRetryable = true,
        isRateLimitOrTimeout = true
    )

    private fun networkFailure(name: String) = AIProviderResult.Failure(
        providerName = name,
        errorMessage = "$name network error.",
        isRetryable = true,
        isRateLimitOrTimeout = false
    )

    /** Records sleeps so the tests can prove the pause happened without waiting. */
    private class SleepRecorder {
        val waits = mutableListOf<Long>()
        suspend fun sleep(ms: Long) {
            waits.add(ms)
        }
    }

    @Test
    fun aBusyProviderFallsOverToTheNextOne() = runTest {
        val busy = FlakyProvider("Busy", failures = Int.MAX_VALUE, failure = busyFailure("Busy"))
        val healthy = FlakyProvider("Healthy", failures = 0, failure = busyFailure("Healthy"))
        val sleeps = SleepRecorder()

        val result = AIProviderRouter(
            listOf(busy, healthy),
            busyRetryDelayMs = 10,
            sleep = sleeps::sleep
        ).routeChat(messages, "sys")

        val success = result as AIProviderResult.Success
        assertEquals("answered by Healthy", success.text)
        assertEquals(1, busy.callCount)
        assertEquals(1, healthy.callCount)
        // Failover alone answered it - no need to wait and retry the whole pass.
        assertTrue("no retry pause expected", sleeps.waits.isEmpty())
    }

    @Test
    fun everythingBusyGetsOneAutomaticRetryBeforeFailing() = runTest {
        // Both providers are busy on the first pass and healthy on the second.
        val a = FlakyProvider("A", failures = 1, failure = busyFailure("A"))
        val b = FlakyProvider("B", failures = 1, failure = busyFailure("B"))
        val sleeps = SleepRecorder()

        val result = AIProviderRouter(
            listOf(a, b),
            busyRetryDelayMs = 1_200,
            sleep = sleeps::sleep
        ).routeChat(messages, "sys")

        val success = result as AIProviderResult.Success
        assertEquals("answered by A", success.text)
        // Pass 1: A busy, B busy. Pass 2: A is healthy again and answers first,
        // so B is not called a second time (the chain stops at the first hit).
        assertEquals(2, a.callCount)
        assertEquals(1, b.callCount)
        assertEquals(listOf(1_200L), sleeps.waits)
    }

    @Test
    fun stillBusyAfterTheRetryReportsTheBusyMessage() = runTest {
        val a = FlakyProvider("A", failures = Int.MAX_VALUE, failure = busyFailure("A"))
        val b = FlakyProvider("B", failures = Int.MAX_VALUE, failure = busyFailure("B"))
        val sleeps = SleepRecorder()

        val result = AIProviderRouter(
            listOf(a, b),
            busyRetryDelayMs = 5,
            sleep = sleeps::sleep
        ).routeChat(messages, "sys")

        val failure = result as AIProviderResult.Failure
        assertTrue(failure.isRetryable)
        assertTrue(failure.isRateLimitOrTimeout)
        assertTrue("busy wording", failure.errorMessage.contains("busy"))
        assertTrue("says it retried", failure.errorMessage.contains("retried"))
        // One retry pass only: two calls each, one pause.
        assertEquals(2, a.callCount)
        assertEquals(2, b.callCount)
        assertEquals(1, sleeps.waits.size)
    }

    @Test
    fun aNetworkFailureIsNotRetriedAutomatically() = runTest {
        val a = FlakyProvider("A", failures = Int.MAX_VALUE, failure = networkFailure("A"))
        val sleeps = SleepRecorder()

        val result = AIProviderRouter(
            listOf(a),
            busyRetryDelayMs = 5,
            sleep = sleeps::sleep
        ).routeChat(messages, "sys")

        val failure = result as AIProviderResult.Failure
        assertFalse(failure.isRateLimitOrTimeout)
        assertTrue(failure.errorMessage.contains("Last error"))
        assertTrue(failure.errorMessage.contains("A network error."))
        assertEquals(1, a.callCount)
        assertTrue(sleeps.waits.isEmpty())
    }

    @Test
    fun streamingFailuresAreNotRetriedOnceTextWasShown() = runTest {
        val partial = object : AIProvider {
            override val name = "Partial"
            override val isConfigured = true
            var callCount = 0
                private set

            override suspend fun generateResponse(
                messages: List<ChatMessage>,
                systemInstruction: String
            ): AIProviderResult = generateStreamingResponse(messages, systemInstruction) {}

            override suspend fun generateStreamingResponse(
                messages: List<ChatMessage>,
                systemInstruction: String,
                onToken: (String) -> Unit
            ): AIProviderResult {
                callCount++
                onToken("half an answer")
                return busyFailure("Partial")
            }
        }
        val second = FlakyProvider("B", failures = 0, failure = busyFailure("B"))
        val sleeps = SleepRecorder()

        val result = AIProviderRouter(
            listOf(partial, second),
            busyRetryDelayMs = 5,
            sleep = sleeps::sleep
        ).routeChat(messages, "sys")

        // The partial text stays; no other provider may append to it.
        val failure = result as AIProviderResult.Failure
        assertEquals("Partial", failure.providerName)
        assertEquals(1, partial.callCount)
        assertEquals(0, second.callCount)
        assertTrue(sleeps.waits.isEmpty())
    }

    @Test
    fun onePassModeNeverRetries() = runTest {
        val a = FlakyProvider("A", failures = Int.MAX_VALUE, failure = busyFailure("A"))
        val sleeps = SleepRecorder()

        val result = AIProviderRouter(
            listOf(a),
            maxPasses = 1,
            busyRetryDelayMs = 5,
            sleep = sleeps::sleep
        ).routeChat(messages, "sys")

        val failure = result as AIProviderResult.Failure
        assertTrue(failure.errorMessage.contains("busy"))
        // maxPasses = 1: nothing claims an automatic retry happened.
        assertFalse(failure.errorMessage.contains("retried"))
        assertEquals(1, a.callCount)
        assertTrue(sleeps.waits.isEmpty())
    }
}
