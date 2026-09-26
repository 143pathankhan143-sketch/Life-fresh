package com.example.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLoopTest {

    private val texts: VoiceTexts = VoiceTextDefaults.hinglish()

    private val listenCommand = VoiceEffect.StartListening(ListenReason.COMMAND)
    private val listenFollowUp = VoiceEffect.StartListening(ListenReason.FOLLOW_UP)
    private val endTail = listOf(VoiceEffect.StopListening, VoiceEffect.SessionEnded)

    private fun speak(text: String): VoiceEffect = VoiceEffect.Speak(VoiceSpeech.Literal(text))
    private fun speakKey(key: String, vararg args: Any): VoiceEffect = speak(texts.get(key, *args))

    private fun newLoop(
        enabled: Set<VoiceCapability> = VoiceCapabilities.M2,
        confirmNavigation: Boolean = true,
        mode: VoiceSessionMode = VoiceSessionMode.ONE_SHOT
    ): VoiceLoop {
        val loop = VoiceLoop(texts = texts, enabled = enabled, confirmNavigation = confirmNavigation)
        loop.startSession(mode)
        return loop
    }

    // ------------------------------------------------------------ session start

    @Test
    fun startSessionStopsAudioAndOpensMic() {
        val loop = VoiceLoop(texts = texts)
        assertEquals(
            listOf(VoiceEffect.StopSpeaking, listenCommand),
            loop.startSession(VoiceSessionMode.ONE_SHOT)
        )
        assertTrue(loop.isSessionActive)
    }

    @Test
    fun heardTextIsIgnoredWhenNoSessionIsOpen() {
        val loop = VoiceLoop(texts = texts)
        assertEquals(emptyList<VoiceEffect>(), loop.onHeard("leads dikhao"))
    }

    // -------------------------------------------------------------- navigation

    @Test
    fun navigateAsksFirstThenMovesOnYes() {
        val loop = newLoop()
        val prompt = texts.get(VoiceTextKeys.NAV_PROMPT, texts.destinationName(VocalDestination.LEADS))

        assertEquals(
            listOf(VoiceEffect.ConfirmCard(prompt), speak(prompt), listenCommand),
            loop.onHeard("leads dikhao")
        )
        assertTrue(loop.awaitingConfirmation)

        assertEquals(
            listOf(
                VoiceEffect.ConfirmCard(null),
                VoiceEffect.Navigate(VocalDestination.LEADS),
                VoiceEffect.Speak(VoiceSpeech.NavDone(VocalDestination.LEADS))
            ) + endTail,
            loop.onHeard("haan")
        )
        assertFalse(loop.awaitingConfirmation)
        assertFalse(loop.isSessionActive)
    }

    @Test
    fun navigateGoesStraightThroughWhenConfirmationIsSwitchedOff() {
        val loop = newLoop(confirmNavigation = false)
        assertEquals(
            listOf(
                VoiceEffect.Navigate(VocalDestination.LEADS),
                VoiceEffect.Speak(VoiceSpeech.NavDone(VocalDestination.LEADS))
            ) + endTail,
            loop.onHeard("leads dikhao")
        )
    }

    @Test
    fun noCancelsNavigation() {
        val loop = newLoop()
        loop.onHeard("leads dikhao")
        assertEquals(
            listOf(
                VoiceEffect.ConfirmCard(null),
                speakKey(VoiceTextKeys.CANCELLED)
            ) + endTail,
            loop.onHeard("nahi")
        )
        assertFalse(loop.awaitingConfirmation)
        assertFalse(loop.isSessionActive)
    }

    @Test
    fun boloModeKeepsListeningAfterTheReply() {
        val loop = newLoop(mode = VoiceSessionMode.CONTINUOUS)
        loop.onHeard("dashboard dikhao")
        assertEquals(
            listOf(
                VoiceEffect.ConfirmCard(null),
                VoiceEffect.Navigate(VocalDestination.DASHBOARD),
                VoiceEffect.Speak(VoiceSpeech.NavDone(VocalDestination.DASHBOARD)),
                VoiceEffect.StopListening,
                listenFollowUp
            ),
            loop.onHeard("haan")
        )
        assertTrue(loop.isSessionActive)
    }

    @Test
    fun backNeedsAConfirmationToo() {
        val loop = newLoop()
        assertEquals(
            listOf(VoiceEffect.ConfirmCard(texts.get(VoiceTextKeys.BACK_PROMPT)), speakKey(VoiceTextKeys.BACK_PROMPT), listenCommand),
            loop.onHeard("wapas jao")
        )
        assertEquals(
            listOf(
                VoiceEffect.ConfirmCard(null),
                VoiceEffect.Back,
                speakKey(VoiceTextKeys.BACK_DONE)
            ) + endTail,
            loop.onHeard("yes")
        )
    }

    // -------------------------------------------------------------- AI + help

    @Test
    fun questionGoesToTheAiChatWithoutAConfirm() {
        val loop = newLoop()
        assertEquals(
            listOf(VoiceEffect.AskAI("AI se pucho aaj kisko call karun")) + endTail,
            loop.onHeard("AI se pucho aaj kisko call karun")
        )
        assertFalse(loop.isSessionActive)
    }

    @Test
    fun unknownSentenceBecomesAQuestionForTheChat() {
        val loop = newLoop()
        assertEquals(
            listOf(VoiceEffect.AskAI("kal mausam kaisa hai")) + endTail,
            loop.onHeard("kal mausam kaisa hai")
        )
    }

    @Test
    fun helpSpeaksTheCommandList() {
        val loop = newLoop()
        assertEquals(
            listOf(speakKey(VoiceTextKeys.HELP)) + endTail,
            loop.onHeard("kya kya bol sakte ho")
        )
    }

    @Test
    fun bareYesWithNothingPendingJustAsksAgain() {
        val loop = newLoop()
        assertEquals(
            listOf(speakKey(VoiceTextKeys.SAY_YES_OR_NO)) + endTail,
            loop.onHeard("haan")
        )
    }

    // ------------------------------------------------------- not built yet (M2)

    @Test
    fun backupIsPolitelyUnavailableInM2() {
        val loop = newLoop()
        assertEquals(
            listOf(speakKey(VoiceTextKeys.NOT_YET)) + endTail,
            loop.onHeard("backup karo")
        )
    }

    @Test
    fun leadSearchIsUnavailableInM2() {
        val loop = newLoop()
        assertEquals(
            listOf(speakKey(VoiceTextKeys.NOT_YET)) + endTail,
            loop.onHeard("Ramesh ko dhoondo")
        )
    }

    @Test
    fun theDestructiveCommandsNeverRunWithoutTheirMilestone() {
        // M2: the cloud-delete sentence exists in the parser, but the
        // capability is OFF — so nothing is even offered.
        val loop = newLoop()
        assertEquals(
            listOf(speakKey(VoiceTextKeys.NOT_YET)) + endTail,
            loop.onHeard("cloud backup delete karo")
        )
        assertFalse(loop.awaitingConfirmation)
    }

    // --------------------------------------------------- destructive (M4 shape)

    @Test
    fun cloudDeleteNeedsThreeStepsAndTheExactPhrase() {
        val loop = newLoop(enabled = VoiceCapabilities.ALL)

        val p1 = texts.get(VoiceTextKeys.CLOUD_DELETE_PROMPT_1)
        val p2 = texts.get(VoiceTextKeys.CLOUD_DELETE_PROMPT_2)
        val p3 = texts.get(VoiceTextKeys.CLOUD_DELETE_PROMPT_3)

        // Step 1
        assertEquals(
            listOf(VoiceEffect.ConfirmCard(p1), speak(p1), listenCommand),
            loop.onHeard("cloud backup delete karo")
        )
        // Step 2
        assertEquals(
            listOf(VoiceEffect.ConfirmCard(p2), speak(p2), listenCommand),
            loop.onHeard("haan")
        )
        // Step 3 (exact-phrase prompt)
        assertEquals(
            listOf(VoiceEffect.ConfirmCard(p3), speak(p3), listenCommand),
            loop.onHeard("haan")
        )
        // A bare "haan" is NOT enough on the last step.
        assertEquals(
            listOf(speak(p3), listenCommand),
            loop.onHeard("haan")
        )
        assertTrue(loop.awaitingConfirmation)

        // Only the exact phrase runs it.
        assertEquals(
            listOf(VoiceEffect.ConfirmCard(null), VoiceEffect.Execute(VoiceCommand.DeleteCloudBackup)),
            loop.onHeard("haan delete karo")
        )
        assertFalse(loop.awaitingConfirmation)

        // Host reports back after the async executor finished.
        assertEquals(endTail, loop.onExecutionFinished())
    }

    @Test
    fun cloudDeleteAlsoAcceptsThePhraseInIndicScripts() {
        val loop = newLoop(enabled = VoiceCapabilities.ALL)
        loop.onHeard("cloud backup delete karo")
        loop.onHeard("haan")
        loop.onHeard("haan")
        assertEquals(
            listOf(VoiceEffect.ConfirmCard(null), VoiceEffect.Execute(VoiceCommand.DeleteCloudBackup)),
            loop.onHeard("हाँ डिलीट करो")
        )
    }

    @Test
    fun localDeleteIsADoubleConfirm() {
        val loop = newLoop(enabled = VoiceCapabilities.ALL)
        val p1 = texts.get(VoiceTextKeys.LOCAL_DELETE_PROMPT)
        val p2 = texts.get(VoiceTextKeys.PAKKA)

        assertEquals(
            listOf(VoiceEffect.ConfirmCard(p1), speak(p1), listenCommand),
            loop.onHeard("local data delete karo")
        )
        assertEquals(
            listOf(VoiceEffect.ConfirmCard(p2), speak(p2), listenCommand),
            loop.onHeard("haan")
        )
        assertEquals(
            listOf(VoiceEffect.ConfirmCard(null), VoiceEffect.Execute(VoiceCommand.DeleteLocalData)),
            loop.onHeard("haan")
        )
    }

    @Test
    fun cancellingAPendingDeleteRunsNothing() {
        val loop = newLoop(enabled = VoiceCapabilities.ALL)
        loop.onHeard("cloud backup delete karo")
        assertEquals(
            listOf(VoiceEffect.StopSpeaking, VoiceEffect.ConfirmCard(null)) + endTail,
            loop.cancelSession()
        )
        assertFalse(loop.isSessionActive)
        assertFalse(loop.awaitingConfirmation)
    }

    // ------------------------------------------------------------ barge-in etc.

    @Test
    fun rukoStopsEverything() {
        val loop = newLoop(mode = VoiceSessionMode.CONTINUOUS)
        assertEquals(
            listOf(
                VoiceEffect.StopSpeaking,
                VoiceEffect.ConfirmCard(null),
                speakKey(VoiceTextKeys.STOPPED)
            ) + endTail,
            loop.onHeard("ruko")
        )
        assertFalse(loop.isSessionActive)
    }

    @Test
    fun longSentenceWithBasIsNotABargeIn() {
        val loop = newLoop()
        // "bas" here is part of a sentence → must NOT stop; it is a question.
        assertEquals(
            listOf(VoiceEffect.AskAI("bas ek baat batado")) + endTail,
            loop.onHeard("bas ek baat batado")
        )
    }

    @Test
    fun listenFailureRetriesOnceThenCloses() {
        val loop = newLoop()
        assertEquals(
            listOf(speakKey(VoiceTextKeys.HEARD_NOTHING)) + listOf(listenCommand),
            loop.onListenFailed()
        )
        assertEquals(
            listOf(speakKey(VoiceTextKeys.HEARD_NOTHING)) + endTail,
            loop.onListenFailed()
        )
        assertFalse(loop.isSessionActive)
    }

    @Test
    fun listenFailureDuringAConfirmRepeatsTheQuestionThenCancels() {
        val loop = newLoop(enabled = VoiceCapabilities.ALL)
        loop.onHeard("cloud backup delete karo")

        assertEquals(
            listOf(speakKey(VoiceTextKeys.SAY_YES_OR_NO), listenCommand),
            loop.onListenFailed()
        )
        // Second failure: the pending delete is dropped (never executed).
        assertEquals(
            listOf(VoiceEffect.ConfirmCard(null), speakKey(VoiceTextKeys.HEARD_NOTHING)) + endTail,
            loop.onListenFailed()
        )
        assertFalse(loop.awaitingConfirmation)
        assertFalse(loop.isSessionActive)
    }

    @Test
    fun aHeardWordResetsTheFailureCount() {
        val loop = newLoop(mode = VoiceSessionMode.CONTINUOUS)
        loop.onListenFailed()
        loop.onHeard("help")
        assertEquals(
            listOf(speakKey(VoiceTextKeys.HEARD_NOTHING), listenCommand),
            loop.onListenFailed()
        )
    }

    @Test
    fun aSpokenCommandDuringAConfirmIsNotMistakenForYes() {
        val loop = newLoop()
        loop.onHeard("leads dikhao")
        // "backup karo" is a command, NOT a yes → keep waiting.
        assertEquals(
            listOf(speakKey(VoiceTextKeys.SAY_YES_OR_NO), listenCommand),
            loop.onHeard("backup karo")
        )
        assertTrue(loop.awaitingConfirmation)
    }
}
