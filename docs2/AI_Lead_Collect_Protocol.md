# AI Lead Collect Protocol (Step 1)

Conversational lead creation in the AI chat, with **Unknown** handling and a
**Drafts** folder. The AI (cloud Gemini/Groq) decides; the user always taps
the final save button. No local/offline AI is involved.

## Flow

1. User asks to add a lead in **any wording/language** ("lead add karo",
   "ek client daalo", "yah number add karo 98765...", "add a lead").
2. AI collects conversationally, one question at a time:
   **name → mobile → disease/wellness issue (optional)**.
   - Details can be given in any order, even several in one message.
   - "naam nahi maloom" → name becomes `Unknown`.
3. When name + a valid mobile are known, the AI ends its reply with a hidden
   block (never shown to the user):

   ```
   [LEAD_CONFIRM]{"name":"Rahul","mobile":"9876543210","diseases":["Diabetes"]}
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

## Key code

| Part | File |
| --- | --- |
| Protocol (system instruction) | `ai/chat/config/AIConfig.kt` |
| Marker parsing (safe fallback) | `ai/chat/lead/LeadActionParser.kt` |
| Pending action state | `ai/chat/model/ChatUiState.kt`, `ai/chat/repository/AIChatRepository.kt` |
| Save/draft/cancel | `ai/chat/viewmodel/AIChatViewModel.kt` |
| Confirmation card | `ui/screens/AIScreen.kt` (`PendingLeadActionCard`) |
| Save path (duplicate check, LOCAL_AI origin) | `ui/viewmodel/CRMViewModel.kt` (`saveLeadFromAIChat`) |
| Draft column + migration v13→v14 | `data/database/LeadEntity.kt`, `AppDatabase.kt` |
| Drafts filter chip | `ui/screens/LeadsTab.kt` + `leads_filter_drafts` strings |

## Safety properties

- Malformed/missing marker or JSON → the reply is shown as normal text,
  nothing is saved (parser degrades to `Normal`).
- Full-lead saves validate the mobile (10-15 digits) and reject duplicates.
- A new user message, or clearing/opening a session, dismisses a pending card.
- All existing testTags are unchanged; new ones: `ai_lead_action_card`,
  `ai_lead_save_confirm`, `ai_lead_save_draft`, `ai_lead_save_cancel`.
