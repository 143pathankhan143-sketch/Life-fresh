package com.example.voice.android

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One-shot bridge between the voice loop and the AI chat.
 *
 * When a spoken command is not an app action ("kal kisi ko call karun?"), the
 * loop hands the whole sentence to the chat. MainActivity navigates to the AI
 * tab and posts the prompt here; AIScreen (already composed, or freshly opened)
 * collects it, sends it, and clears the slot.
 */
object VoiceHandoff {

    private val _prompts = MutableStateFlow<String?>(null)

    /** The prompt waiting to be sent to the AI chat, or null. */
    val prompts: StateFlow<String?> = _prompts

    fun post(prompt: String) {
        _prompts.value = prompt.trim().ifBlank { null }
    }

    fun consume() {
        _prompts.value = null
    }
}
