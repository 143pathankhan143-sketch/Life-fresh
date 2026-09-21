package com.example.ai.chat.lead

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LeadActionParserTest {

    @Test
    fun `confirm block is parsed with all fields`() {
        val reply = """Zaroor! Rahul ki details mil gayin.
[LEAD_CONFIRM]{"name":"Rahul","mobile":"9876543210","diseases":["Diabetes","High BP"]}"""
        val parsed = LeadActionParser.parse(reply)
        assertTrue(parsed is ParsedLeadReply.WithAction)
        val withAction = parsed as ParsedLeadReply.WithAction

        assertEquals(LeadAction.Kind.CONFIRM, withAction.action.kind)
        assertEquals("Rahul", withAction.action.name)
        assertEquals("9876543210", withAction.action.mobile)
        assertEquals(listOf("Diabetes", "High BP"), withAction.action.diseases)
        assertEquals("Zaroor! Rahul ki details mil gayin.", withAction.visibleText)
    }

    @Test
    fun `draft block is parsed with partial details`() {
        val reply = """Theek hai, main yahi rakh deta hoon.
[LEAD_DRAFT]{"name":"Rahul","mobile":"","diseases":[]}"""
        val parsed = LeadActionParser.parse(reply) as ParsedLeadReply.WithAction

        assertEquals(LeadAction.Kind.DRAFT, parsed.action.kind)
        assertEquals("Rahul", parsed.action.name)
        assertEquals("", parsed.action.mobile)
        assertTrue(parsed.action.diseases.isEmpty())
    }

    @Test
    fun `reply without marker stays normal and unchanged`() {
        val reply = "Hello! Main aapki kya madad kar sakta hoon?"
        val parsed = LeadActionParser.parse(reply)
        assertTrue(parsed is ParsedLeadReply.Normal)
        assertEquals(reply, (parsed as ParsedLeadReply.Normal).text)
    }

    @Test
    fun `malformed json falls back to normal with raw text`() {
        val reply = "Done! [LEAD_CONFIRM]{this is not json"
        val parsed = LeadActionParser.parse(reply)
        assertTrue(parsed is ParsedLeadReply.Normal)
        assertEquals(reply, (parsed as ParsedLeadReply.Normal).text)
    }

    @Test
    fun `missing closing tag still parses`() {
        val reply = "Save karne ke liye button dabaiye.\n[LEAD_CONFIRM]{\"name\":\"Amit\",\"mobile\":\"9812345678\",\"diseases\":[]}"
        val parsed = LeadActionParser.parse(reply) as ParsedLeadReply.WithAction
        assertEquals(LeadAction.Kind.CONFIRM, parsed.action.kind)
        assertEquals("Amit", parsed.action.name)
        assertEquals("9812345678", parsed.action.mobile)
    }

    @Test
    fun `closing tag is stripped from visible text`() {
        val reply = """Ready hai!
[LEAD_CONFIRM]{"name":"Sara","mobile":"9988776655","diseases":["Asthma"]}
[/LEAD_CONFIRM]"""
        val parsed = LeadActionParser.parse(reply) as ParsedLeadReply.WithAction
        assertEquals("Sara", parsed.action.name)
        assertEquals("Ready hai!", parsed.visibleText)
    }

    @Test
    fun `empty name and mobile is not an action`() {
        val reply = "[LEAD_DRAFT]{\"name\":\"\",\"mobile\":\"\"}"
        val parsed = LeadActionParser.parse(reply)
        assertTrue(parsed is ParsedLeadReply.Normal)
    }

    @Test
    fun `extra keys are ignored and lengths are capped`() {
        val longName = "x".repeat(200)
        val reply = "[LEAD_CONFIRM]{\"name\":\"$longName\",\"mobile\":\"987654321001234567891\",\"diseases\":[\"A\",\"B\",\"C\",\"D\",\"E\",\"F\",\"G\"],\"unknownKey\":42}"
        val parsed = LeadActionParser.parse(reply) as ParsedLeadReply.WithAction
        assertEquals(80, parsed.action.name.length)
        assertEquals(20, parsed.action.mobile.length)
        assertEquals(listOf("A", "B", "C", "D", "E"), parsed.action.diseases)
    }

    @Test
    fun `duplicate diseases are collapsed case-insensitively`() {
        val reply = "[LEAD_CONFIRM]{\"name\":\"Ravi\",\"mobile\":\"9876543210\",\"diseases\":[\"Diabetes\",\"diabetes\",\"  Diabetes  \"]}"
        val parsed = LeadActionParser.parse(reply) as ParsedLeadReply.WithAction
        assertEquals(listOf("Diabetes"), parsed.action.diseases)
    }

    @Test
    fun `note and reminder are parsed`() {
        val reply = "[LEAD_CONFIRM]{\"name\":\"Ravi\",\"mobile\":\"9876543210\",\"diseases\":[],\"note\":\"10 din baad call karna hai\",\"reminderDate\":\"2026-09-30\",\"reminderTime\":\"10:00\"}"
        val parsed = LeadActionParser.parse(reply) as ParsedLeadReply.WithAction
        assertEquals("10 din baad call karna hai", parsed.action.note)
        assertEquals("2026-09-30", parsed.action.reminderDate)
        assertEquals("10:00", parsed.action.reminderTime)
    }

    @Test
    fun `missing optional fields default to empty`() {
        val reply = "[LEAD_CONFIRM]{\"name\":\"Amit\",\"mobile\":\"9876543210\",\"diseases\":[]}"
        val parsed = LeadActionParser.parse(reply) as ParsedLeadReply.WithAction
        assertEquals("", parsed.action.note)
        assertEquals("", parsed.action.reminderDate)
        assertEquals("", parsed.action.reminderTime)
    }

    @Test
    fun `note is length capped and reminder fields are short-capped`() {
        val longNote = "n".repeat(300)
        val reply = "[LEAD_CONFIRM]{\"name\":\"A\",\"mobile\":\"9876543210\",\"diseases\":[],\"note\":\"$longNote\",\"reminderDate\":\"2026-09-30EXTRA\",\"reminderTime\":\"10:00:99\"}"
        val parsed = LeadActionParser.parse(reply) as ParsedLeadReply.WithAction
        assertEquals(120, parsed.action.note.length)
        assertEquals("2026-09-30", parsed.action.reminderDate)
        assertEquals("10:00", parsed.action.reminderTime)
    }

    @Test
    fun `status block is parsed with normalized status`() {
        val reply = """Rahul ko complete mark kar do?
[LEAD_STATUS]{"name":"Rahul","mobile":"9876543210","status":"completed"}"""
        val parsed = LeadActionParser.parse(reply) as ParsedLeadReply.WithAction
        assertEquals(LeadAction.Kind.STATUS, parsed.action.kind)
        assertEquals("Rahul", parsed.action.name)
        assertEquals("9876543210", parsed.action.mobile)
        assertEquals("Complete", parsed.action.status)
        assertEquals("Rahul ko complete mark kar do?", parsed.visibleText)
    }

    @Test
    fun `status block with invalid status is not an action`() {
        val reply = "[LEAD_STATUS]{\"name\":\"Rahul\",\"mobile\":\"9876543210\",\"status\":\"Weird\"}"
        val parsed = LeadActionParser.parse(reply)
        assertTrue(parsed is ParsedLeadReply.Normal)
    }

    @Test
    fun `status block without name and mobile is not an action`() {
        val reply = "[LEAD_STATUS]{\"name\":\"\",\"mobile\":\"\",\"status\":\"Pending\"}"
        val parsed = LeadActionParser.parse(reply)
        assertTrue(parsed is ParsedLeadReply.Normal)
    }

    @Test
    fun `status block with only mobile is parsed`() {
        val reply = "[LEAD_STATUS]{\"name\":\"\",\"mobile\":\"9812345678\",\"status\":\"Pending\"}"
        val parsed = LeadActionParser.parse(reply) as ParsedLeadReply.WithAction
        assertEquals(LeadAction.Kind.STATUS, parsed.action.kind)
        assertEquals("9812345678", parsed.action.mobile)
        assertEquals("Pending", parsed.action.status)
    }

    @Test
    fun `update block is parsed with all change fields`() {
        val reply = """Rahul ka number badal deta hoon.
[LEAD_UPDATE]{"name":"Rahul","mobile":"9876543210","setMobile":"9812345678","setName":"","addDiseases":["High BP"],"note":"kal subah call karna hai","setReminderDate":"2026-09-30","setReminderTime":"10:00","removeReminder":false}"""
        val parsed = LeadActionParser.parse(reply) as ParsedLeadReply.WithAction
        assertEquals(LeadAction.Kind.UPDATE, parsed.action.kind)
        assertEquals("Rahul", parsed.action.name)
        assertEquals("9876543210", parsed.action.mobile)
        assertEquals("9812345678", parsed.action.setMobile)
        assertEquals("", parsed.action.setName)
        assertEquals(listOf("High BP"), parsed.action.addDiseases)
        assertEquals("kal subah call karna hai", parsed.action.note)
        assertEquals("2026-09-30", parsed.action.setReminderDate)
        assertEquals("10:00", parsed.action.setReminderTime)
        assertFalse(parsed.action.removeReminder)
        assertEquals("Rahul ka number badal deta hoon.", parsed.visibleText)
    }

    @Test
    fun `update block with no changes is not an action`() {
        val reply = "[LEAD_UPDATE]{\"name\":\"Rahul\",\"mobile\":\"9876543210\",\"setMobile\":\"\",\"setName\":\"\",\"addDiseases\":[],\"note\":\"\",\"setReminderDate\":\"\",\"removeReminder\":false}"
        val parsed = LeadActionParser.parse(reply)
        assertTrue(parsed is ParsedLeadReply.Normal)
    }

    @Test
    fun `update block with only remove reminder is an action`() {
        val reply = "[LEAD_UPDATE]{\"name\":\"Rahul\",\"mobile\":\"9876543210\",\"removeReminder\":true}"
        val parsed = LeadActionParser.parse(reply) as ParsedLeadReply.WithAction
        assertEquals(LeadAction.Kind.UPDATE, parsed.action.kind)
        assertTrue(parsed.action.removeReminder)
        assertTrue(parsed.action.setMobile.isEmpty())
    }

    @Test
    fun `update block without name and mobile is not an action`() {
        val reply = "[LEAD_UPDATE]{\"name\":\"\",\"mobile\":\"\",\"setMobile\":\"9812345678\"}"
        val parsed = LeadActionParser.parse(reply)
        assertTrue(parsed is ParsedLeadReply.Normal)
    }

    @Test
    fun `archive block is parsed with name`() {
        val reply = """Rahul ko archive kar do.
[LEAD_ARCHIVE]{"name":"Rahul","mobile":""}"""
        val parsed = LeadActionParser.parse(reply) as ParsedLeadReply.WithAction
        assertEquals(LeadAction.Kind.ARCHIVE, parsed.action.kind)
        assertEquals("Rahul", parsed.action.name)
        assertTrue(parsed.action.mobile.isEmpty())
        assertEquals("Rahul ko archive kar do.", parsed.visibleText)
    }

    @Test
    fun `delete block is parsed with mobile only`() {
        val reply = "[LEAD_DELETE]{\"name\":\"\",\"mobile\":\"9876543210\"}"
        val parsed = LeadActionParser.parse(reply) as ParsedLeadReply.WithAction
        assertEquals(LeadAction.Kind.DELETE, parsed.action.kind)
        assertEquals("9876543210", parsed.action.mobile)
    }

    @Test
    fun `archive and delete blocks without any identifier are not actions`() {
        assertTrue(LeadActionParser.parse("[LEAD_ARCHIVE]{\"name\":\"\",\"mobile\":\"\"}") is ParsedLeadReply.Normal)
        assertTrue(LeadActionParser.parse("[LEAD_DELETE]{\"name\":\"\",\"mobile\":\"\"}") is ParsedLeadReply.Normal)
    }

    @Test
    fun `whatsapp block is parsed with mobile`() {
        val reply = "[LEAD_WHATSAPP]{\"name\":\"Rahul\",\"mobile\":\"9876543210\"}"
        val parsed = LeadActionParser.parse(reply) as ParsedLeadReply.WithAction
        assertEquals(LeadAction.Kind.WHATSAPP, parsed.action.kind)
        assertEquals("Rahul", parsed.action.name)
        assertEquals("9876543210", parsed.action.mobile)
    }

    @Test
    fun `whatsapp block without mobile is not an action`() {
        val reply = "[LEAD_WHATSAPP]{\"name\":\"Rahul\",\"mobile\":\"\"}"
        val parsed = LeadActionParser.parse(reply)
        assertTrue(parsed is ParsedLeadReply.Normal)
    }
}
