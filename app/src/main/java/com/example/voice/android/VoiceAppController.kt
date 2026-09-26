package com.example.voice.android

import android.content.Context
import android.util.Log
import com.example.ai.chat.voice.AiVoicePlayer
import com.example.ai.chat.voice.VoiceInputHelper
import com.example.voice.ListenReason
import com.example.voice.VocalDestination
import com.example.voice.VoiceCapabilities
import com.example.voice.VoiceCapability
import com.example.voice.VoiceCommand
import com.example.voice.VoiceEffect
import com.example.voice.VoiceLoop
import com.example.voice.VoiceSessionMode
import com.example.voice.VoiceSpeech
import com.example.voice.VoiceTextKeys
import com.example.voice.VoiceTexts
import com.example.voice.VoiceConfirmPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the app must do for the loop. Implemented by MainActivity (navigation,
 * chat hand-off, confirm card, live lead counts) so [VoiceLoop] stays pure and
 * this class stays a thin script player.
 */
interface VoiceEffectHandler {

    fun navigate(destination: VocalDestination)

    fun back()

    fun askAI(prompt: String)

    /** Show ([prompt] != null) or hide the on-screen confirmation card. */
    fun showConfirmCard(prompt: String?)

    /**
     * "[screen] khul gaya" plus live counts for screens that have them —
     * the controller falls back to a plain line when this returns null.
     */
    fun navAnnouncement(destination: VocalDestination): String?

    fun onSessionChanged(active: Boolean)

    fun onListeningChanged(listening: Boolean)

    fun onSpeakingChanged(speaking: Boolean)

    /** M3/M4/M5 executors. The controller calls back after it is done. */
    fun execute(command: VoiceCommand) {}
}

/**
 * The Android side of voice full-app control (M2): opens the phone's speech
 * recognizer, plays the [VoiceEffect] script that [VoiceLoop] returns, and
 * reports what was heard back into the loop.
 *
 * Barge-in: every new turn cancels the previous script and stops audio + mic
 * first, and the AI chat's own [AiVoicePlayer] audio is stopped the same way —
 * so the phone is always quiet before it listens.
 */
class VoiceAppController(
    private val context: Context,
    private val texts: VoiceTexts,
    private val handler: VoiceEffectHandler,
    private val scope: CoroutineScope,
    enabled: Set<VoiceCapability> = VoiceCapabilities.M2,
    confirmNavigation: Boolean = VoiceConfirmPolicy.DEFAULT_CONFIRM_NAVIGATION,
    knownVoices: () -> Set<String> = { emptySet() }
) {

    private val loop = VoiceLoop(
        texts = texts,
        enabled = enabled,
        confirmNavigation = confirmNavigation,
        knownVoices = knownVoices
    )

    private val voiceInput = VoiceInputHelper(context)
    private var job: Job? = null

    /**
     * Bumped for every listening attempt. Callbacks from an earlier attempt
     * (a cancelled recognition that reports ERROR_CLIENT a moment later) carry
     * an old token and are ignored, so a late cancel can never tear down the
     * turn that is starting now.
     */
    private var listenAttempt: Long = 0L

    val isSessionActive: Boolean get() = loop.isSessionActive
    val isListening: Boolean get() = voiceInput.isListening

    /** Mic button: start a one-shot session, or stop the running one. */
    fun toggleMic() {
        if (loop.isSessionActive) cancelSession() else startSession()
    }

    fun startSession(mode: VoiceSessionMode = VoiceSessionMode.ONE_SHOT) {
        handler.onSessionChanged(true)
        runScript(loop.startSession(mode))
    }

    /**
     * Silent stop (mic tap again, tab change, screen going away). When no
     * session is open this must NOT touch AiVoicePlayer — the AI chat may be
     * reading an answer aloud on the very screen we are switching to.
     */
    fun cancelSession() {
        if (!loop.isSessionActive) {
            handler.showConfirmCard(null)
            handler.onSessionChanged(false)
            return
        }
        val effects = loop.cancelSession()
        stopEverythingNow()
        runScript(effects)
    }

    /** "ruko/chup/stop" from outside the loop, or the AI chat's stop button. */
    fun stopSpeaking() {
        AiVoicePlayer.stop()
    }

    /**
     * Hard stop with no speech: app went to the background, the screen left the
     * main tabs, or the activity is going away. A microphone must never stay
     * open behind another app (privacy + battery), and nothing is executed.
     */
    fun abandonSilently() {
        job?.cancel()
        loop.abandon()
        endSessionNow()
    }

    /** M3/M4/M5: the host finished an [VoiceEffect.Execute] — settle the turn. */
    fun onExecutionFinished() {
        runScript(loop.onExecutionFinished())
    }

    fun shutdown() {
        job?.cancel()
        stopEverythingNow()
        voiceInput.shutdown()
        handler.onSessionChanged(false)
    }

    // ------------------------------------------------------------- turn engine

    private fun runScript(effects: List<VoiceEffect>) {
        if (effects.isEmpty()) return
        job?.cancel()
        job = scope.launch { play(effects) }
    }

    private suspend fun play(effects: List<VoiceEffect>) {
        try {
            playOrThrow(effects)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Throwable) {
            // An effect blew up (bad route, missing voice data, ...). Leaving the
            // session "active" here would strand the mic button and the confirm
            // card, so fail closed instead of failing stuck.
            Log.w(TAG, "voice turn failed, closing the session", e)
            endSessionNow()
        }
    }

    private suspend fun playOrThrow(effects: List<VoiceEffect>) {
        for (effect in effects) {
            when (effect) {
                is VoiceEffect.Speak -> speakNow(speechText(effect.speech))

                VoiceEffect.StopSpeaking -> AiVoicePlayer.stop()

                is VoiceEffect.StartListening -> startListening(effect.reason)

                VoiceEffect.StopListening -> {
                    listenAttempt++
                    voiceInput.cancel()
                    handler.onListeningChanged(false)
                }

                is VoiceEffect.Navigate -> handler.navigate(effect.destination)

                VoiceEffect.Back -> handler.back()

                is VoiceEffect.AskAI -> handler.askAI(effect.prompt)

                is VoiceEffect.ConfirmCard -> handler.showConfirmCard(effect.prompt)

                is VoiceEffect.Execute -> handler.execute(effect.command)

                VoiceEffect.SessionEnded -> {
                    listenAttempt++
                    voiceInput.cancel()
                    handler.showConfirmCard(null)
                    handler.onListeningChanged(false)
                    handler.onSessionChanged(false)
                }
            }
        }
    }

    private suspend fun startListening(reason: ListenReason) {
        if (!voiceInput.isAvailable()) {
            // No recognizer (rare) — say so instead of failing silently.
            speakNow(texts.get(VoiceTextKeys.MIC_UNAVAILABLE))
            loop.abandon()
            handler.showConfirmCard(null)
            handler.onSessionChanged(false)
            return
        }

        // Everything from a previous attempt is now dead (the old recognition
        // is being cancelled just below, and its late ERROR_CLIENT must not
        // tear down the turn that starts here).
        listenAttempt++
        val attempt = listenAttempt

        // Never let the phone listen to itself: silence audio first, and drop
        // any recognition that is somehow still running — VoiceInputHelper.start
        // silently does nothing while one is listening.
        AiVoicePlayer.stop()
        voiceInput.cancel()

        // The TTS/MediaPlayer "done" callback fires slightly before the speaker
        // is really quiet; a mic opened too early transcribes the tail of our
        // own sentence ("...say yes or no" heard as a command).
        delay(MIC_SETTLE_MS)

        Log.d(TAG, "listening ($reason)")
        voiceInput.start(
            onResult = { text ->
                if (attempt != listenAttempt) return@start
                handler.onListeningChanged(false)
                runScript(loop.onHeard(text))
            },
            onError = { message ->
                if (attempt != listenAttempt) return@start
                handler.onListeningChanged(false)
                if (message.isBlank()) {
                    // Silent reset (user cancelled / system took the mic).
                    loop.abandon()
                    handler.showConfirmCard(null)
                    handler.onSessionChanged(false)
                } else {
                    runScript(loop.onListenFailed())
                }
            }
        )
        handler.onListeningChanged(voiceInput.isListening)
    }

    private suspend fun speakNow(text: String) {
        if (text.isBlank()) return
        handler.onSpeakingChanged(true)
        try {
            AiVoicePlayer.speakSuspend(context, text)
        } catch (e: Throwable) {
            Log.w(TAG, "speak failed", e)
        } finally {
            handler.onSpeakingChanged(false)
        }
    }

    private fun speechText(speech: VoiceSpeech): String = when (speech) {
        is VoiceSpeech.Literal -> speech.text
        is VoiceSpeech.NavDone ->
            handler.navAnnouncement(speech.destination)
                ?: texts.get(
                    VoiceTextKeys.NAV_DONE,
                    texts.destinationName(speech.destination)
                )
    }

    private fun stopEverythingNow() {
        AiVoicePlayer.stop()
        voiceInput.cancel()
        handler.onListeningChanged(false)
    }

    /** Quiet shutdown of the turn state (used by [abandonSilently] and errors). */
    private fun endSessionNow() {
        listenAttempt++
        AiVoicePlayer.stop()
        voiceInput.cancel()
        loop.abandon()
        handler.showConfirmCard(null)
        handler.onListeningChanged(false)
        handler.onSessionChanged(false)
    }

    private companion object {
        const val TAG = "VoiceAppController"

        /**
         * Pause before opening the mic after speaking. Long enough that the
         * speaker is actually silent, short enough that a turn still feels
         * instant.
         */
        const val MIC_SETTLE_MS = 250L
    }
}
