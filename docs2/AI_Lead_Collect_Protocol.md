# AI Lead Collect Protocol (Step 1)

Conversational lead creation in the AI chat, with **Unknown** handling and a
**Drafts** folder. The AI (cloud Gemini/Groq) decides; the user always taps
the final save button. No local/offline AI is involved.

## Flow

1. User asks to add a lead in **any wording/language** ("lead add karo",
   "ek client daalo", "yah number add karo 98765...", "add a lead").
2. AI collects conversationally, one question at a time:
   **name → mobile → disease/wellness issue (optional) → note / next call (optional)**.
   - Details can be given in any order, even several in one message.
   - "naam nahi maloom" → name becomes `Unknown`.
   - "10 din baad call karna hai" / "kal subah call karna" → the AI computes a
     concrete `reminderDate` (yyyy-MM-dd) from today's date (today's date is
     injected into the system instruction at app launch) and `reminderTime`
     (HH:mm, empty when the user gives no time).
3. When name + a valid mobile are known, the AI ends its reply with a hidden
   block (never shown to the user):

   ```
   [LEAD_CONFIRM]{"name":"Rahul","mobile":"9876543210","diseases":["Diabetes"],"note":"10 din baad call karna hai","reminderDate":"2026-09-30","reminderTime":"10:00"}
   ```

4. The app strips the block and shows a **confirmation card**
   (`PendingLeadActionCard` in `AIScreen.kt`) with:
   `Save Lead` / `Draft me rakho` / `Cancel`.
5. Only the user's tap saves. The AI never writes data by itself.

## Drafts

- Cancel/pause with some details collected →
  `[LEAD_DRAFT]{"name":"Rahul","mobile":"",...}` → saved with `isDraft = true`.
- Cancel with nothing collected → nothing is saved.
- Drafts are **device-local** (never queued for cloud sync - see
  `LeadRepository.insertLead`) and appear **only** under the **Drafts** chip
  in the Leads tab (hidden from All/Pending/Complete/archive/search,
  dashboard analytics and PDF reports).
- Opening a draft opens the normal lead form; saving there completes the
  draft (`isDraft` returns to false via the shared `LeadOperationService`).

## Notes & reminders (step 2)

- `note` is stored in the lead's notes; when a reminder is set, the same
  sentence also becomes the reminder label.
- The save path validates the reminder strictly:
  - date must parse as `yyyy-MM-dd`; time must be 24h `HH:mm`
    (missing/invalid time defaults to **09:00**);
  - past dates/times, unparseable dates, and times colliding with another
    active reminder are skipped - the lead still saves, with a warning in the
    chat message;
  - valid reminders schedule a real alarm through `ReminderScheduler`
    (same scheduler as the manual form).
- Drafts store the note/reminder fields but never schedule alarms; completing
  a draft through the normal lead form schedules it there.
- The model is instructed to only collect the defined fields and never claim
  to have performed any other action (no calls, reports or settings changes).

## Key code

| Part | File |
| --- | --- |
| Protocol (system instruction) | `ai/chat/config/AIConfig.kt` |
| Today's date injection | `ai/chat/repository/AIChatRepository.kt` |
| Marker parsing (safe fallback) | `ai/chat/lead/LeadActionParser.kt` |
| Pending action state | `ai/chat/model/ChatUiState.kt`, `ai/chat/repository/AIChatRepository.kt` |
| Save/draft/cancel | `ai/chat/viewmodel/AIChatViewModel.kt` |
| Confirmation card | `ui/screens/AIScreen.kt` (`PendingLeadActionCard`) |
| Save path (validate, duplicate check, alarm, LOCAL_AI origin) | `ui/viewmodel/CRMViewModel.kt` (`saveLeadFromAIChat`) |
| Draft column + migration v13→v14 | `data/database/LeadEntity.kt`, `AppDatabase.kt` |
| Drafts filter chip + drafts empty state | `ui/screens/LeadsTab.kt` + `leads_filter_drafts` strings |

## Safety properties

- Malformed/missing marker or JSON → the reply is shown as normal text,
  nothing is saved (parser degrades to `Normal`).
- Full-lead saves validate the mobile (10-15 digits) and reject duplicates.
- A new user message, or clearing/opening a session, dismisses a pending card.
- All existing testTags are unchanged; new ones: `ai_lead_action_card`,
  `ai_lead_save_confirm`, `ai_lead_save_draft`, `ai_lead_save_cancel`.
