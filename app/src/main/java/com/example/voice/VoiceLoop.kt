package com.example.voice

/** How long a voice session stays open. */
enum class VoiceSessionMode {
    /** Mic tap = one command; the session closes after the reply. */
    ONE_SHOT,

    /** Bolo mode: keep listening for the next command after every reply. */
    CONTINUOUS
}

/**
 * Which commands need how many spoken confirmations (work.md §6).
 *
 *  - TRIPLE : cloud backup delete ONLY — the last step must be the exact
 *             phrase "haan delete karo" / "yes delete" (enforced by
 *             [VoiceConfirmGate], not by this policy).
 *  - DOUBLE : local data delete, restore, bulk lead changes.
 *  - SINGLE : navigation, search, backup, settings changes — the spoken
 *             question doubles as a "did I hear you right?" check, which is
 *             exactly what a mis-heard name or screen needs.
 *  - null   : read-only/no-op commands (a question, help, back).
 *
 * [DEFAULT_CONFIRM_NAVIGATION] keeps the planned behaviour; flipping the
 * `confirmNavigation` flag (one place: [VoiceLoop]) makes screen switching
 * instant while every destructive gate stays exactly as strict.
 */
object VoiceConfirmPolicy {

    const val DEFAULT_CONFIRM_NAVIGATION: Boolean = true

    fun levelFor(
        command: VoiceCommand,
        confirmNavigation: Boolean = DEFAULT_CONFIRM_NAVIGATION
    ): ConfirmLevel? = when (command) {
        VoiceCommand.DeleteCloudBackup -> ConfirmLevel.TRIPLE
        VoiceCommand.DeleteLocalData -> ConfirmLevel.DOUBLE
        is VoiceCommand.Restore -> ConfirmLevel.DOUBLE
        is VoiceCommand.Backup -> ConfirmLevel.SINGLE
        is VoiceCommand.SearchLeads -> ConfirmLevel.SINGLE
        is VoiceCommand.Navigate -> if (confirmNavigation) ConfirmLevel.SINGLE else null
        VoiceCommand.Back -> if (confirmNavigation) ConfirmLevel.SINGLE else null
        is VoiceCommand.AskAI -> null
        VoiceCommand.Help -> null
        VoiceCommand.Unknown -> null
        is VoiceCommand.SetLanguage,
        is VoiceCommand.SetVoice,
        is VoiceCommand.SetBoloMode -> ConfirmLevel.SINGLE
    }
}

/**
 * The pure voice turn engine: heard text in, ordered [VoiceEffect] script out.
 *
 * It owns exactly one thing — the conversation state (session mode, pending
 * confirmation, listen failures) — and nothing Android. The host only plays
 * the script: speak what it says, navigate where it says, and call back with
 * what was heard. That split is what makes every rule (including the triple
 * cloud-delete gate) deterministic and testable without a device.
 *
 * Host contract:
 *  1. Play the effects IN ORDER, suspending on [VoiceEffect.Speak] until the
 *     audio ends (so the mic never opens while the phone is still talking).
 *  2. Call [onHeard] with the transcript, [onListenFailed] when the recognizer
 *     reported nothing, and [onExecutionFinished] after an
 *     [VoiceEffect.Execute] finished (M3/M4).
 *  3. [VoiceEffect.StartListening], [VoiceEffect.StopListening] and
 *     [VoiceEffect.SessionEnded] are always the last effect of a list.
 */
class VoiceLoop(
    private val texts: VoiceTexts,
    private val enabled: Set<VoiceCapability> = VoiceCapabilities.M2,
    private val confirmNavigation: Boolean = VoiceConfirmPolicy.DEFAULT_CONFIRM_NAVIGATION,
    private val knownVoices: () -> Set<String> = { emptySet() },
    private val maxListenFailures: Int = 2,
    private val gate: VoiceConfirmGate = VoiceConfirmGate()
) {

    /**
     * How a script ends.
     *  - RETRY_COMMAND : listen again for a command (or for haan/nahi).
     *  - AFTER_TURN    : Bolo mode keeps listening, one-shot mode closes.
     *  - END           : close the session now.
     *  - NOTHING       : no terminal effect — the host settles the turn later
     *                    (used after [VoiceEffect.Execute], which is async).
     */
    private enum class Next { RETRY_COMMAND, AFTER_TURN, END, NOTHING }

    var isSessionActive: Boolean = false
        private set

    var mode: VoiceSessionMode = VoiceSessionMode.ONE_SHOT
        private set

    /** True while the app is waiting for "haan/nahi" (or the exact phrase). */
    val awaitingConfirmation: Boolean get() = gate.isActive

    /** Last parsed command — handy for tests and for the debug overlay. */
    var lastCommand: VoiceCommand? = null
        private set

    private var pending: VoiceCommand? = null
    private var listenFailures: Int = 0

    // ------------------------------------------------------------------ turns

    /** Opens the mic (barge-in first: any running audio is stopped). */
    fun startSession(sessionMode: VoiceSessionMode = VoiceSessionMode.ONE_SHOT): List<VoiceEffect> {
        mode = sessionMode
        isSessionActive = true
        pending = null
        listenFailures = 0
        gate.reset()
        return listOf(
            VoiceEffect.StopSpeaking,
            VoiceEffect.StartListening(ListenReason.COMMAND)
        )
    }

    /** One heard utterance → the next script. */
    fun onHeard(rawText: String): List<VoiceEffect> {
        if (!isSessionActive) return emptyList()
        val text = rawText.trim()
        if (text.isEmpty()) return listOf(VoiceEffect.StartListening(ListenReason.COMMAND))
        listenFailures = 0

        // 1. A confirmation is open: the NEXT word decides (yes / no / exact phrase).
        if (gate.isActive) return onConfirmHeard(text)

        // 2. "ruko / chup / stop" = stop everything, anytime.
        if (VoiceUtterance.isBargeIn(text)) return stopEverything()

        // 3. Normal command.
        return dispatch(VoiceCommandParser.parse(text, knownVoices()))
    }

    /** Recognizer heard nothing (or failed) → retry once, then close. */
    fun onListenFailed(): List<VoiceEffect> {
        if (!isSessionActive) return emptyList()
        listenFailures++
        val gaveUp = listenFailures >= maxListenFailures

        if (gate.isActive) {
            if (gaveUp) {
                // Never leave a confirmation hanging: cancel it and close,
                // because the action was never executed.
                gate.reset()
                pending = null
                return script(
                    listOf(VoiceEffect.ConfirmCard(null), speakKey(VoiceTextKeys.HEARD_NOTHING)),
                    Next.END
                )
            }
            return script(listOf(speakKey(VoiceTextKeys.SAY_YES_OR_NO)), Next.RETRY_COMMAND)
        }

        return if (gaveUp) script(listOf(speakKey(VoiceTextKeys.HEARD_NOTHING)), Next.END)
        else script(listOf(speakKey(VoiceTextKeys.HEARD_NOTHING)), Next.RETRY_COMMAND)
    }

    /** The host finished an [VoiceEffect.Execute] (M3/M4) — speak and settle. */
    fun onExecutionFinished(): List<VoiceEffect> = script(emptyList(), Next.AFTER_TURN)

    /**
     * Silently drops the whole turn state — used when the mic was lost, the
     * user cancelled inside the recognizer, or the screen went away. Speaks
     * nothing and executes nothing.
     */
    fun abandon() {
        isSessionActive = false
        pending = null
        listenFailures = 0
        gate.reset()
    }

    /** Mic tap while a session is open, or any other hard stop by the user. */
    fun cancelSession(): List<VoiceEffect> {
        if (!isSessionActive) return emptyList()
        pending = null
        gate.reset()
        return endNow(listOf(VoiceEffect.StopSpeaking, VoiceEffect.ConfirmCard(null)))
    }

    // ------------------------------------------------------------- confirmations

    private fun onConfirmHeard(text: String): List<VoiceEffect> {
        val command = pending
        return when (val step = gate.feed(text)) {
            is ConfirmStep.Ask ->
                script(listOf(VoiceEffect.ConfirmCard(step.prompt), speak(step.prompt)), Next.RETRY_COMMAND)

            // TRIPLE last step heard the wrong words: ask for the exact phrase again.
            is ConfirmStep.RetryExactPhrase ->
                script(listOf(speak(step.prompt)), Next.RETRY_COMMAND)

            ConfirmStep.Confirmed -> {
                pending = null
                executeConfirmed(command)
            }

            ConfirmStep.Cancelled -> {
                pending = null
                script(
                    listOf(VoiceEffect.ConfirmCard(null), speakKey(VoiceTextKeys.CANCELLED)),
                    Next.AFTER_TURN
                )
            }

            ConfirmStep.Waiting ->
                script(listOf(speakKey(VoiceTextKeys.SAY_YES_OR_NO)), Next.RETRY_COMMAND)
        }
    }

    /** Gate said yes → really run the command. */
    private fun executeConfirmed(command: VoiceCommand?): List<VoiceEffect> =
        if (command == null) script(listOf(VoiceEffect.ConfirmCard(null)), Next.AFTER_TURN)
        else script(
            listOf(VoiceEffect.ConfirmCard(null)) + runNow(command),
            executionNext(command)
        )

    /** Effects that perform a command once it is allowed to run. */
    private fun runNow(command: VoiceCommand): List<VoiceEffect> = when (command) {
        is VoiceCommand.Navigate -> listOf(
            VoiceEffect.Navigate(command.destination),
            VoiceEffect.Speak(VoiceSpeech.NavDone(command.destination))
        )

        VoiceCommand.Back -> listOf(
            VoiceEffect.Back,
            speakKey(VoiceTextKeys.BACK_DONE)
        )

        is VoiceCommand.AskAI -> listOf(VoiceEffect.AskAI(command.prompt))

        VoiceCommand.Help -> listOf(speakKey(VoiceTextKeys.HELP))

        VoiceCommand.Unknown -> listOf(speakKey(VoiceTextKeys.SAY_YES_OR_NO))

        // M3/M4/M5 executors (leads, backup/restore/deletes, settings).
        else -> listOf(VoiceEffect.Execute(command))
    }

    /** `Execute` is async on the host side, so the turn settles later. */
    private fun executionNext(command: VoiceCommand): Next = when (command) {
        is VoiceCommand.Navigate, VoiceCommand.Back, VoiceCommand.Help, VoiceCommand.Unknown ->
            Next.AFTER_TURN
        // The chat screen takes over the mic from here.
        is VoiceCommand.AskAI -> Next.END
        // M3/M4/M5 executors report back through onExecutionFinished().
        else -> Next.NOTHING
    }

    // ---------------------------------------------------------------- dispatch

    private fun dispatch(command: VoiceCommand): List<VoiceEffect> {
        lastCommand = command

        // A bare "haan"/"nahi" with nothing pending is not a command.
        if (command == VoiceCommand.Unknown) {
            return script(listOf(speakKey(VoiceTextKeys.SAY_YES_OR_NO)), Next.AFTER_TURN)
        }

        val capability = command.capability()
        if (capability == null || capability !in enabled) {
            return script(listOf(speakKey(VoiceTextKeys.NOT_YET)), Next.AFTER_TURN)
        }

        val level = VoiceConfirmPolicy.levelFor(command, confirmNavigation)
            ?: return script(runNow(command), executionNext(command))

        val first = beginConfirm(command, level)
        return script(listOf(VoiceEffect.ConfirmCard(first), speak(first)), Next.RETRY_COMMAND)
    }

    /** Opens the gate and returns the first prompt to speak. */
    private fun beginConfirm(command: VoiceCommand, level: ConfirmLevel): String {
        val prompts = promptsFor(command, level)
        val safe = if (prompts.size == level.steps) prompts
        else List(level.steps) { index ->
            if (index == 0) texts.get(VoiceTextKeys.CONFIRM_OK) else texts.get(VoiceTextKeys.PAKKA)
        }
        pending = command
        return gate.begin(level, safe)
    }

    private fun promptsFor(command: VoiceCommand, level: ConfirmLevel): List<String> = when (command) {
        VoiceCommand.DeleteCloudBackup -> listOf(
            texts.get(VoiceTextKeys.CLOUD_DELETE_PROMPT_1),
            texts.get(VoiceTextKeys.CLOUD_DELETE_PROMPT_2),
            texts.get(VoiceTextKeys.CLOUD_DELETE_PROMPT_3)
        )

        VoiceCommand.DeleteLocalData -> listOf(
            texts.get(VoiceTextKeys.LOCAL_DELETE_PROMPT),
            texts.get(VoiceTextKeys.PAKKA)
        )

        is VoiceCommand.Restore -> listOf(
            texts.get(VoiceTextKeys.RESTORE_PROMPT),
            texts.get(VoiceTextKeys.PAKKA)
        )

        is VoiceCommand.Backup -> listOf(texts.get(VoiceTextKeys.BACKUP_PROMPT))

        is VoiceCommand.Navigate -> listOf(
            texts.get(VoiceTextKeys.NAV_PROMPT, texts.destinationName(command.destination))
        )

        VoiceCommand.Back -> listOf(texts.get(VoiceTextKeys.BACK_PROMPT))

        is VoiceCommand.SearchLeads -> listOf(
            texts.get(VoiceTextKeys.SEARCH_PROMPT, command.query)
        )

        else -> List(level.steps) { index ->
            if (index == 0) texts.get(VoiceTextKeys.CONFIRM_OK) else texts.get(VoiceTextKeys.PAKKA)
        }
    }

    // ------------------------------------------------------------------- tails

    /** Stops the session right now (barge-in words, screen changes). */
    private fun stopEverything(): List<VoiceEffect> {
        pending = null
        gate.reset()
        return endNow(
            listOf(
                VoiceEffect.StopSpeaking,
                VoiceEffect.ConfirmCard(null),
                speakKey(VoiceTextKeys.STOPPED)
            )
        )
    }

    private fun endNow(parts: List<VoiceEffect>): List<VoiceEffect> {
        isSessionActive = false
        pending = null
        gate.reset()
        return parts + listOf(VoiceEffect.StopListening, VoiceEffect.SessionEnded)
    }

    /** Appends how the turn ends: listen again, settle, or close. */
    private fun script(parts: List<VoiceEffect>, next: Next): List<VoiceEffect> {
        val tail = when (next) {
            Next.RETRY_COMMAND -> listOf(VoiceEffect.StartListening(ListenReason.COMMAND))

            Next.AFTER_TURN -> if (mode == VoiceSessionMode.CONTINUOUS) {
                listOf(VoiceEffect.StopListening, VoiceEffect.StartListening(ListenReason.FOLLOW_UP))
            } else {
                listOf(VoiceEffect.StopListening, VoiceEffect.SessionEnded)
            }

            Next.END -> listOf(VoiceEffect.StopListening, VoiceEffect.SessionEnded)

            Next.NOTHING -> emptyList()
        }
        if (tail.any { it === VoiceEffect.SessionEnded }) {
            isSessionActive = false
            pending = null
            gate.reset()
        }
        return parts + tail
    }

    private fun speak(text: String): VoiceEffect = VoiceEffect.Speak(VoiceSpeech.Literal(text))

    private fun speakKey(key: String, vararg args: Any): VoiceEffect =
        VoiceEffect.Speak(VoiceSpeech.Literal(texts.get(key, *args)))
}
