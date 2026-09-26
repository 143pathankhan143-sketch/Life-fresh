package com.example.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandParserTest {

    private val voices = setOf("Kore", "Charon", "Puck", "Zephyr", "Fenrir", "Leda", "Aoede")

    @Test
    fun navigationToLeads() {
        val c = VoiceCommandParser.parse("Leads dikhao")
        assertEquals(VoiceCommand.Navigate(VocalDestination.LEADS), c)
    }

    @Test
    fun navigationToDashboard() {
        assertEquals(
            VoiceCommand.Navigate(VocalDestination.DASHBOARD),
            VoiceCommandParser.parse("Dashboard dikhao")
        )
    }

    @Test
    fun navigationToSettings() {
        assertEquals(
            VoiceCommand.Navigate(VocalDestination.SETTINGS),
            VoiceCommandParser.parse("Settings kholo")
        )
    }

    @Test
    fun navigationToHistoryMatchesOldChatHinglish() {
        val c = VoiceCommandParser.parse("purani chat dikhao")
        assertEquals(VoiceCommand.Navigate(VocalDestination.AI_HISTORY), c)
    }

    @Test
    fun navigationToLeadsByLoneWord() {
        assertEquals(
            VoiceCommand.Navigate(VocalDestination.LEADS),
            VoiceCommandParser.parse("clients")
        )
    }

    @Test
    fun backCommand() {
        assertEquals(VoiceCommand.Back, VoiceCommandParser.parse("wapas jao"))
        assertEquals(VoiceCommand.Back, VoiceCommandParser.parse("back"))
    }

    @Test
    fun searchLead() {
        val c = VoiceCommandParser.parse("Ramesh ko dhoondo")
        assertTrue(c is VoiceCommand.SearchLeads)
        assertTrue((c as VoiceCommand.SearchLeads).query.contains("Ramesh", ignoreCase = true))
        assertEquals(false, c.pendingOnly)
    }

    @Test
    fun searchPending() {
        val c = VoiceCommandParser.parse("pending wale dikhao")
        assertEquals(VoiceCommand.SearchLeads("pending", true), c)
    }

    @Test
    fun backupCloudByDefault() {
        assertEquals(
            VoiceCommand.Backup(BackupTarget.CLOUD),
            VoiceCommandParser.parse("backup karo")
        )
    }

    @Test
    fun restoreIsNotBackup() {
        val c = VoiceCommandParser.parse("backup wapas lao")
        assertEquals(VoiceCommand.Restore(BackupTarget.CLOUD), c)
    }

    @Test
    fun cloudDeleteHasPriority() {
        assertEquals(
            VoiceCommand.DeleteCloudBackup,
            VoiceCommandParser.parse("cloud backup delete karo")
        )
    }

    @Test
    fun localDelete() {
        assertEquals(
            VoiceCommand.DeleteLocalData,
            VoiceCommandParser.parse("local data delete karo")
        )
        assertEquals(
            VoiceCommand.DeleteLocalData,
            VoiceCommandParser.parse("saara data hatao")
        )
    }

    @Test
    fun languageHindi() {
        assertEquals(
            VoiceCommand.SetLanguage("hi"),
            VoiceCommandParser.parse("bhasha hindi karo")
        )
    }

    @Test
    fun languageTamil() {
        assertEquals(
            VoiceCommand.SetLanguage("ta"),
            VoiceCommandParser.parse("tamil karo")
        )
    }

    @Test
    fun setVoiceKore() {
        assertEquals(
            VoiceCommand.SetVoice("Kore"),
            VoiceCommandParser.parse("Kore awaz lagao", voices)
        )
    }

    @Test
    fun setVoiceUnknownNameOpensPicker() {
        assertEquals(
            VoiceCommand.SetVoice(""),
            VoiceCommandParser.parse("awaz badlo", voices)
        )
    }

    @Test
    fun boloModeOnAndOff() {
        assertEquals(VoiceCommand.SetBoloMode(true), VoiceCommandParser.parse("bolo mode on karo"))
        assertEquals(VoiceCommand.SetBoloMode(false), VoiceCommandParser.parse("bolo mode band karo"))
    }

    @Test
    fun askAiPhrase() {
        val c = VoiceCommandParser.parse("AI se pucho aaj kisko call karun")
        assertTrue(c is VoiceCommand.AskAI)
    }

    @Test
    fun leadCrudIsDelegatedToAI() {
        // "Ramesh complete karo" is a lead action -> AI chat (LEAD_STATUS protocol)
        assertEquals(VoiceCommand.AskAI("Ramesh complete karo"), VoiceCommandParser.parse("Ramesh complete karo"))
        assertEquals(VoiceCommand.AskAI("Naya lead banao"), VoiceCommandParser.parse("Naya lead banao"))
    }

    @Test
    fun genericDeleteDelegatedToAI() {
        assertEquals(VoiceCommand.AskAI("Ramesh delete karo"), VoiceCommandParser.parse("Ramesh delete karo"))
    }

    @Test
    fun helpCommand() {
        assertEquals(VoiceCommand.Help, VoiceCommandParser.parse("kya kya bol sakte ho"))
        assertEquals(VoiceCommand.Help, VoiceCommandParser.parse("help"))
    }

    @Test
    fun unknownBlank() {
        assertEquals(VoiceCommand.Unknown, VoiceCommandParser.parse("   "))
    }

    @Test
    fun bareYesWithNothingPendingIsNotACommand() {
        assertEquals(VoiceCommand.Unknown, VoiceCommandParser.parse("haan"))
    }
}
