package com.example.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandParserTest {

    // The app offers exactly two voices now; the parser is tested against that
    // same set (VoiceIds.ALL) instead of a copy that could drift.
    private val voices = VoiceIds.ALL.toSet()

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
    fun setVoiceByGenderWordInEveryLanguage() {
        // The user says "male/female" (or the same word in their own language);
        // speech recognition returns that language even when the app is English.
        val male = listOf("male awaz lagao", "purush awaz lagao", "आदमी आवाज़ लगाओ", "ஆண் குரல்", "مرد کی آواز")
        val female = listOf("female awaz lagao", "mahila awaz lagao", "औरत की आवाज़ लगाओ", "பெண் குரல்", "عورت کی آواز")
        for (text in male) {
            assertEquals("'$text' should pick the male voice", VoiceCommand.SetVoice(VoiceIds.MALE), VoiceCommandParser.parse(text, voices))
        }
        for (text in female) {
            assertEquals("'$text' should pick the female voice", VoiceCommand.SetVoice(VoiceIds.FEMALE), VoiceCommandParser.parse(text, voices))
        }
    }

    @Test
    fun setVoiceByNameStillWorksAndOnlyTheOfferedNames() {
        assertEquals(VoiceCommand.SetVoice(VoiceIds.FEMALE), VoiceCommandParser.parse("kore awaz lagao", voices))
        assertEquals(VoiceCommand.SetVoice(VoiceIds.MALE), VoiceCommandParser.parse("Orus awaz lagao", voices))
        // A removed voice name is no longer recognised as a voice -> the picker
        // hint comes instead of silently sending an id the API would reject.
        assertEquals(VoiceCommand.SetVoice(""), VoiceCommandParser.parse("charon awaz lagao", voices))
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
