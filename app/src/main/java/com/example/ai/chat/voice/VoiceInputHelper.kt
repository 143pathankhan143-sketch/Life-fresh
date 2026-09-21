package com.example.ai.chat.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Thin wrapper around the Android built-in [SpeechRecognizer] for the AI chat
 * composer mic button. No API key and no extra library - it uses the phone's
 * own voice service (Google/OS speech).
 *
 * Everything is wrapped in try/catch so a missing engine, a denied
 * microphone or a failed recognition can NEVER crash the app - it only
 * surfaces a short friendly message through the error callback.
 *
 * Flow (AI chat composer):
 *  - [start]  -> listening on; the final transcript arrives via the result
 *    callback (either when the user stops, or when recognition ends on its
 *    own after silence).
 *  - [stop]   -> finish listening; the transcript (if any) is still delivered.
 *  - [cancel] / [shutdown] -> drop the audio without a callback.
 *
 * RecognitionListener callbacks are delivered on the main thread, so callers
 * can safely update UI state from the result/error lambdas.
 */
class VoiceInputHelper(context: Context) {

    private val appContext: Context = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var onResult: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    var isListening: Boolean = false
        private set

    /** True when the phone has a usable speech recognition service. */
    fun isAvailable(): Boolean = try {
        SpeechRecognizer.isRecognitionAvailable(appContext)
    } catch (e: Throwable) {
        false
    }

    /**
     * Starts listening. [onResult] receives the final transcript; [onError]
     * receives a user-friendly message when nothing could be heard (also for
     * an empty transcript).
     */
    fun start(onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (isListening) return
        if (!isAvailable()) {
            onError("Voice feature is not available on this phone.")
            return
        }
        val sr = try {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        } catch (e: Throwable) {
            null
        } ?: run {
            onError("Voice feature is not available on this phone.")
            return
        }

        this.onResult = onResult
        this.onError = onError
        recognizer = sr
        sr.setOnRecognitionListener(createListener())

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            // Use the device locale - on Indian phones that means the
            // recognizer understands Hindi, English and Hinglish.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            sr.startListening(intent)
            isListening = true
        } catch (e: Throwable) {
            release()
            onError("Mic start nahi ho saka, dobara try karo.")
        }
    }

    /** Finishes listening; the final transcript (if any) is still delivered. */
    fun stop() {
        if (recognizer == null) return
        try {
            recognizer?.stopListening()
        } catch (e: Throwable) {
            release()
        }
    }

    /** Drops the current audio without delivering any transcript. */
    fun cancel() {
        try {
            recognizer?.cancel()
        } catch (e: Throwable) {
            // ignore - release below
        }
        release()
    }

    /** Permanently releases the recognizer (call when the screen goes away). */
    fun shutdown() {
        cancel()
        onResult = null
        onError = null
    }

    private fun release() {
        try {
            recognizer?.shutdown()
        } catch (e: Throwable) {
            // ignore
        }
        recognizer = null
        isListening = false
    }

    private fun createListener(): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onResults(results: Bundle?) {
            val text = try {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                matches?.firstOrNull()?.trim().orEmpty()
            } catch (e: Throwable) {
                ""
            }
            release()
            if (text.isNotEmpty()) {
                onResult?.invoke(text)
            } else {
                onError?.invoke("Kuch sunai nahi diya, dobara try karo.")
            }
        }

        override fun onError(error: Int) {
            val wasListening = isListening
            release()
            // ERROR_CLIENT = cancelled by the user or system (back press,
            // lifecycle) - reset the UI quietly, no scary message (empty
            // string means "silent reset").
            if (error == RecognitionListener.ERROR_CLIENT || !wasListening) {
                onError?.invoke("")
                return
            }
            val message = when (error) {
                RecognitionListener.ERROR_NO_MATCH ->
                    "Kuch samajh nahi aaya, dobara try karo."
                RecognitionListener.ERROR_NO_SPEECH ->
                    "Kuch sunai nahi diya, dobara try karo."
                RecognitionListener.ERROR_NETWORK,
                RecognitionListener.ERROR_NETWORK_TIMEOUT ->
                    "Voice ke liye internet connection chahiye."
                RecognitionListener.ERROR_AUDIO ->
                    "Mic mein problem aayi, dobara try karo."
                else ->
                    "Voice feature me problem aayi, dobara try karo."
            }
            onError?.invoke(message)
        }
    }
}
