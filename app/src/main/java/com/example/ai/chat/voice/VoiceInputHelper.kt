package com.example.ai.chat.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.lang.reflect.Proxy
import java.util.Locale

/**
 * Thin wrapper around the Android built-in [SpeechRecognizer] for the AI chat
 * composer mic button. No API key and no extra library - it uses the phone's
 * own voice service (Google/OS speech).
 *
 * PLATFORM COMPATIBILITY NOTE:
 * Android 16.1 (API 36.1, Baklava QPR2) reorganized the speech API - the
 * listener interface moved to the top-level [RecognitionListener], the setter
 * was renamed to [SpeechRecognizer.setRecognitionListener] and
 * [SpeechRecognizer.destroy] replaced the old shutdown(). This app compiles
 * against API 36.1, so [attachListener] uses the new API on 36.1+ devices and
 * falls back to the classic SpeechRecognizer$RecognitionListener (via
 * reflection) on older platforms. The same fallback strategy is used for
 * destroy()/shutdown().
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
 * Listener callbacks are delivered on the main thread, so callers can safely
 * update UI state from the result/error lambdas.
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

        if (!attachListener(sr)) {
            destroySafe(sr)
            onError("Voice feature is not available on this phone.")
            return
        }
        recognizer = sr

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
        val sr = recognizer ?: return
        try {
            sr.stopListening()
        } catch (e: Throwable) {
            release()
        }
    }

    /** Drops the current audio without delivering any transcript. */
    fun cancel() {
        val sr = recognizer
        if (sr != null) {
            try {
                sr.cancel()
            } catch (e: Throwable) {
                // ignore - release below
            }
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
        val sr = recognizer
        recognizer = null
        isListening = false
        if (sr != null) destroySafe(sr)
    }

    /**
     * Attaches the result listener to [sr].
     *
     * 36.1+ : new API - setRecognitionListener(top-level RecognitionListener).
     * Older : classic SpeechRecognizer$RecognitionListener via reflection,
     *         because the old type no longer exists when compiling against
     *         API 36.1.
     * Returns false when neither path worked (feature unavailable).
     */
    private fun attachListener(sr: SpeechRecognizer): Boolean {
        // --- New API (Android 16.1 / API 36.1+) ---
        try {
            val listener = object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(event: Int, params: Bundle?) {}
                override fun onResults(results: Bundle?) = handleResults(results)
                override fun onError(error: Int) = handleListenerError(error)
            }
            sr.setRecognitionListener(listener)
            return true
        } catch (e: Throwable) {
            // Older platform: the top-level interface / renamed setter is
            // not there (class load or NoSuchMethodError) - try the classic
            // API next.
        }
        // --- Classic API (Android 16.0 and older) via reflection ---
        return try {
            val oldInterface =
                Class.forName("android.speech.SpeechRecognizer\$RecognitionListener")
            val proxy = Proxy.newProxyInstance(
                oldInterface.classLoader,
                arrayOf(oldInterface)
            ) { _, method, args ->
                when (method.name) {
                    "onResults" -> handleResults(args?.getOrNull(0) as? Bundle)
                    "onError" -> handleListenerError(args?.getOrNull(0) as? Int ?: -1)
                    else -> {}
                }
                null
            }
            sr.javaClass
                .getMethod("setOnRecognitionListener", oldInterface)
                .invoke(sr, proxy)
            true
        } catch (e: Throwable) {
            false
        }
    }

    /** destroy() on 36.1+, classic shutdown() on older platforms. */
    private fun destroySafe(sr: SpeechRecognizer) {
        try {
            sr.destroy()
        } catch (e: Throwable) {
            try {
                sr.javaClass.getMethod("shutdown").invoke(sr)
            } catch (e2: Throwable) {
                // ignore - recognizer already unusable
            }
        }
    }

    /** Shared result handling for both listener paths (main thread). */
    private fun handleResults(results: Bundle?) {
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

    /** Shared error handling for both listener paths (main thread). */
    private fun handleListenerError(error: Int) {
        val wasListening = isListening
        release()
        // ERROR_CLIENT = cancelled by the user or system (back press,
        // lifecycle) - reset the UI quietly, no scary message (empty string
        // means "silent reset").
        if (error == SpeechRecognizer.ERROR_CLIENT || !wasListening) {
            onError?.invoke("")
            return
        }
        val message = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH ->
                "Kuch samajh nahi aaya, dobara try karo."
            ERROR_NO_SPEECH_LEGACY ->
                "Kuch sunai nahi diya, dobara try karo."
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                "Voice ke liye internet connection chahiye."
            SpeechRecognizer.ERROR_AUDIO ->
                "Mic mein problem aayi, dobara try karo."
            else ->
                "Voice feature me problem aayi, dobara try karo."
        }
        onError?.invoke(message)
    }

    companion object {
        // The classic SpeechRecognizer$RecognitionListener.ERROR_NO_SPEECH
        // value. The constant was dropped from the SpeechRecognizer surface in
        // API 36.1, so reference the literal (same value on all platforms).
        private const val ERROR_NO_SPEECH_LEGACY = 6
    }
}
