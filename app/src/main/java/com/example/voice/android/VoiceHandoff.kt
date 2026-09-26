package com.example.voice.android

import com.example.voice.VoiceHandoffTiming
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One-shot bridge between the voice loop and the AI chat.
 *
 * When a spoken sentence is not an app action ("kal kisi ko call karun?"), the
 * loop hands it to the chat. MainActivity navigates to the AI tab and posts it
 * here; AIScreen collects the slot, sends it through [consumeIfFresh] and
 * clears it.
 *
 * Prompts expire (see [VoiceHandoffTiming]): if the tab switch never happened
 * (user tapped elsewhere), the stale question is dropped instead of being sent
 * much later.
 */
object VoiceHandoff {

    /** Test hook: lets the freshness rule be exercised without a real clock. */
    var nowProvider: () -> Long = { System.currentTimeMillis() }

    private val _prompts = MutableStateFlow<String?>(null)
    private var postedAtMs: Long = 0L

    /** The prompt waiting to be sent to the AI chat, or null. */
    val prompts: StateFlow<String?> = _prompts

    fun post(prompt: String) {
        val trimmed = prompt.trim()
        _prompts.value = trimmed.ifBlank { null }
        postedAtMs = if (trimmed.isBlank()) 0L else nowProvider()
    }

    /**
     * Takes the pending prompt and clears the slot. Returns null when there was
     * nothing, or when the prompt is older than [maxAgeMs] (stale).
     */
    fun consumeIfFresh(maxAgeMs: Long = VoiceHandoffTiming.MAX_AGE_MS): String? {
        val pending = _prompts.value
        _prompts.value = null
        val postedAt = postedAtMs
        postedAtMs = 0L
        if (pending == null) return null
        return if (VoiceHandoffTiming.isFresh(postedAt, nowProvider(), maxAgeMs)) pending else null
    }

    /** Drops the pending prompt (screen left, session cancelled). */
    fun clear() {
        _prompts.value = null
        postedAtMs = 0L
    }
}
