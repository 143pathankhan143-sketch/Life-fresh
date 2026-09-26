package com.example.ai.chat

import com.example.ai.chat.provider.AIErrorKind
import com.example.ai.chat.provider.AIFailurePolicy
import com.example.ai.chat.provider.AIRecovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "AI service is busy" rules. The headline case: HTTP 429 must ROTATE to
 * the next model instead of ending the request — limits are per model, so the
 * old fail-fast behaviour turned a recoverable limit into a dead-end error.
 */
class AIFailurePolicyTest {

    // ------------------------------------------------------------- classifying

    @Test
    fun statusCodesAreClassified() {
        assertEquals(AIErrorKind.AUTH, AIFailurePolicy.kindFor(401))
        assertEquals(AIErrorKind.AUTH, AIFailurePolicy.kindFor(403))
        assertEquals(AIErrorKind.AUTH, AIFailurePolicy.kindFor(402))
        assertEquals(AIErrorKind.MODEL_MISSING, AIFailurePolicy.kindFor(404))
        assertEquals(AIErrorKind.RATE_LIMIT, AIFailurePolicy.kindFor(429))
        assertEquals(AIErrorKind.SERVICE_BUSY, AIFailurePolicy.kindFor(500))
        assertEquals(AIErrorKind.SERVICE_BUSY, AIFailurePolicy.kindFor(503))
        assertEquals(AIErrorKind.SERVICE_BUSY, AIFailurePolicy.kindFor(408))
        assertEquals(AIErrorKind.OTHER, AIFailurePolicy.kindFor(418))
    }

    @Test
    fun busyKindsAreRateLimitAndServerBusy() {
        assertTrue(AIFailurePolicy.isBusy(AIErrorKind.RATE_LIMIT))
        assertTrue(AIFailurePolicy.isBusy(AIErrorKind.SERVICE_BUSY))
        assertFalse(AIFailurePolicy.isBusy(AIErrorKind.AUTH))
        assertFalse(AIFailurePolicy.isBusy(AIErrorKind.MODEL_MISSING))
        assertFalse(AIFailurePolicy.isBusy(AIErrorKind.OTHER))
    }

    // ---------------------------------------------------------------- deciding

    @Test
    fun invalidKeyFailsFast() {
        assertEquals(
            AIRecovery.FailFast,
            AIFailurePolicy.decide(401, retryAfterHeader = null, sameModelRetryUsed = false)
        )
    }

    @Test
    fun rateLimitWithNoHeaderStillGetsOneQuickSameModelRetry() {
        // A plain 429 is usually a per-minute burst.
        val recovery = AIFailurePolicy.decide(429, retryAfterHeader = null, sameModelRetryUsed = false)
        assertTrue(recovery is AIRecovery.RetrySameModel)
        assertEquals(AIFailurePolicy.DEFAULT_BUSY_WAIT_MS, (recovery as AIRecovery.RetrySameModel).waitMs)
    }

    @Test
    fun rateLimitRotatesToTheNextModelWhenTheWaitWasAlreadyUsed() {
        assertEquals(
            AIRecovery.TryNextModel,
            AIFailurePolicy.decide(429, retryAfterHeader = null, sameModelRetryUsed = true)
        )
    }

    @Test
    fun aLongRetryAfterRotatesInsteadOfWaiting() {
        // "60" = a minute: waiting inline would hang the chat, so rotate.
        assertEquals(
            AIRecovery.TryNextModel,
            AIFailurePolicy.decide(429, retryAfterHeader = "60", sameModelRetryUsed = false)
        )
    }

    @Test
    fun aShortRetryAfterIsHonouredExactly() {
        val recovery = AIFailurePolicy.decide(429, retryAfterHeader = "1", sameModelRetryUsed = false)
        assertEquals(AIRecovery.RetrySameModel(1_000L), recovery)
    }

    @Test
    fun aShortRetryAfterInMillisecondsIsHonoured() {
        assertEquals(
            AIRecovery.RetrySameModel(800L),
            AIFailurePolicy.decide(429, retryAfterHeader = "800ms", sameModelRetryUsed = false)
        )
    }

    @Test
    fun aServerBusyStatusRetriesThenRotates() {
        val first = AIFailurePolicy.decide(503, retryAfterHeader = null, sameModelRetryUsed = false)
        assertTrue(first is AIRecovery.RetrySameModel)
        assertEquals(
            AIRecovery.TryNextModel,
            AIFailurePolicy.decide(503, retryAfterHeader = null, sameModelRetryUsed = true)
        )
    }

    @Test
    fun aMissingModelRotatesImmediately() {
        assertEquals(
            AIRecovery.TryNextModel,
            AIFailurePolicy.decide(404, retryAfterHeader = null, sameModelRetryUsed = false)
        )
    }

    // ------------------------------------------------------- Retry-After parse

    @Test
    fun retryAfterParsesSeconds() {
        assertEquals(1_000L, AIFailurePolicy.retryAfterMillis("1"))
        assertEquals(1_500L, AIFailurePolicy.retryAfterMillis(" 1.5 "))
    }

    @Test
    fun retryAfterParsesMilliseconds() {
        assertEquals(250L, AIFailurePolicy.retryAfterMillis("250ms"))
    }

    @Test
    fun retryAfterRejectsJunkAndDatesAndIsCapped() {
        assertNull(AIFailurePolicy.retryAfterMillis(null))
        assertNull(AIFailurePolicy.retryAfterMillis(""))
        assertNull(AIFailurePolicy.retryAfterMillis("soon"))
        // HTTP-date form: rotating models beats parsing a date.
        assertNull(AIFailurePolicy.retryAfterMillis("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(AIFailurePolicy.retryAfterMillis("0"))
        // A silly value can never stall a turn for long.
        assertEquals(
            AIFailurePolicy.MAX_RETRY_AFTER_MS,
            AIFailurePolicy.retryAfterMillis("999999")
        )
    }

    @Test
    fun labelsAreAvailableForLogsAndMessages() {
        assertEquals("rate limited", AIFailurePolicy.busyLabel(AIErrorKind.RATE_LIMIT))
        assertEquals("service busy", AIFailurePolicy.busyLabel(AIErrorKind.SERVICE_BUSY))
    }
}
