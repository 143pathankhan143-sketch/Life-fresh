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

## CRM awareness (step 3)

Before **every** request the app appends a compact read-only `CRM DATA
SNAPSHOT` to the system instruction (counts, 15 most recent clients, up to
10 drafts - name/phone/status/reminder date/wellness). It is rebuilt from
the in-memory leads StateFlow, so it is always fresh and costs no database
access. The model answers questions ("kitne pending hain?", "Rahul ka
phone?") from it and is instructed never to claim an answer changed data.

## Draft completion (step 3)

- "Mere drafts dekho" → answered from the snapshot's draft list.
- "Rahul ka draft complete karo" → the model reuses the draft's known
  details (visible in the snapshot), asks only for what is missing, and
  emits a normal `LEAD_CONFIRM` block.
- The save path then **matches the draft** (by mobile, or by name for
  draft saves without a mobile) and reuses that draft's row - the draft
  becomes a real lead instead of a duplicate. Drafts no longer block the
  duplicate-mobile check either.

## Lead status (step 3)

- "Rahul complete karo" / "Rahul ko pending karo" → the model identifies
  the lead from the snapshot (phone preferred) and emits:
  `[LEAD_STATUS]{"name":"Rahul","mobile":"98...","status":"Complete"}`
- The app shows a confirmation card ("Update karo" / "Cancel"); only the
  user's tap applies the change via `CRMViewModel.updateLeadStatusFromAIChat`
  (matched by mobile, then unique name - ambiguous names are rejected so
  the model asks for the phone number). Complete cancels the alarm;
  Pending reschedules it when a reminder exists.

## Lead update (step 4)

- "Rahul ka number ... karo" / "Rahul me high BP add karo" / "Rahul ke
  notes me ... likho" / "Rahul ka reminder badlo" / "Rahul ka reminder hata
  do" → the model emits a `LEAD_UPDATE` block with ONLY the changing keys
  (`setMobile`, `setName`, `addDiseases`, `note` = append,
  `setReminderDate` + `setReminderTime`, `removeReminder`). A block with
  no change at all is not an action (parsed as normal text).
- The app shows a confirmation card listing every change
  ("Update karo" / "Cancel"). `CRMViewModel.updateLeadFromAIChat` applies
  the valid changes and skips the invalid ones with a Hinglish warning
  (bad number, duplicate number, past/duplicate reminder). The alarm is
  rescheduled or cancelled accordingly. Notes append (capped at 1000
  chars); wellness issues merge (capped at 10, de-duplicated).

## Delete & archive (step 4)

- "Rahul delete karo" (active lead) → `LEAD_ARCHIVE` - executed DIRECTLY,
  no card: the lead moves to Archived, the alarm is cancelled, and the chat
  replies "📦 'X' archived me chala gaya...".
- "archived se bhi delete karo" → `LEAD_DELETE` → a light confirmation
  card ("Haan, delete karo" / "Cancel"). `CRMViewModel.deleteLeadFromAIChat`
  permanently deletes only when the matched lead IS archived.
  **App-side guard:** a `LEAD_DELETE` targeting a non-archived lead is
  converted to an archive, so chat can never permanently delete a live lead.
- Archiving an already-archived lead is answered "pehle se archived me hai".
- Matching for all of the above: mobile (exact digits) first, then a unique
  exact name; ambiguous names are rejected so the model asks for the phone.

## Daily brief (step 4)

- New empty-state suggestion chip: "Aaj ke top 3 calls kaun se hain?".
- The prompt's CRM AWARENESS section ranks from the snapshot: (1) reminders
  due today, (2) overdue reminders, (3) pending clients without reminders -
  at most 3-5 clients, one line each. Archived leads are excluded from the
  counts, the ranking and the recent-clients list; they appear in the
  snapshot with a `[ARCHIVED]` marker (plus reminder time and lastCall).

## WhatsApp (step 4)

- "Rahul ko WhatsApp karo" → `LEAD_WHATSAPP{"name","mobile"}` - executed
  DIRECTLY (a block without a number is not an action): the app opens
  `https://wa.me/91<10 digits>` (11-15 digit numbers used as-is) via
  ACTION_VIEW + FLAG_ACTIVITY_NEW_TASK. If WhatsApp is not installed, a
  toast shows the number instead of crashing.

## Voice input / STT (step 5)

- Mic button (`mic_button`) in the AI chat composer. First use triggers the
  system `RECORD_AUDIO` permission dialog (manifest permission already
  existed). Uses the phone's built-in `SpeechRecognizer` (`VoiceInputHelper`)
  - no API key, no extra library, device locale (Hindi/English/Hinglish).
- Two ways to finish (ChatGPT-style):
  - **Stop to review:** tap the mic again → transcript lands in the textbox,
    user edits, then sends normally.
  - **Direct send:** tap Send while the mic is still on → transcript is sent
    straight to the AI without appearing in the textbox.
- Empty transcript sends nothing (friendly toast). Back-press during
  listening resets silently. A 10s safety net releases a stuck
  "converting" state. All voice code is try/catch-wrapped - a missing
  voice engine never crashes the app.
- The AI still replies in TEXT (TTS/voice reply is intentionally not part
  of this step).

## Chat UI improvements (step 6)
- Composer: mic + send buttons are 30dp with 4dp spacing and the textbox
  uses `widthIn(min = 0.dp)` so the two buttons never overlap on narrow screens.
- New chat button in the header uses the pencil icon (`Icons.Filled.Edit`).
- Chat history is a half-screen panel sliding in from the left (scrim tap or
  X closes it). Top of the panel: "New Chat" button (pencil icon, English
  label). Existing delete (with confirm) per session stays.
- Every AI reply shows how long the AI took (e.g. "3.2 s" / "1 min 05 s")
  under the bubble; stored per message in Room
  (`ai_chat_messages.responseDurationMs`, DB v15 migration).
- AI replies stream in token-by-token (ChatGPT-style, SSE via Groq
  `stream: true` / Gemini `:streamGenerateContent?alt=sse`) with a blinking
  cursor; if the body is not SSE the provider falls back to the classic
  one-shot response automatically. Partial streamed text is never persisted;
  the provider fallback never switches provider mid-stream.
- Every completed AI reply has visible Copy + Share buttons under the text
  (copy → clipboard + "Copied" toast; share → system chooser).
- Retry button on the last AI reply (error or normal): error reply is dropped
  (or partial streamed text + error dropped) and the last user question is
  re-asked.
- History options: each chat row has a 3-dot menu with Pin/Unpin
  (pinned sort first, pin icon shown), Rename (title edit dialog) and
  Archive/Unarchive (archived chats move to an "ARCHIVED" section at the
  bottom; DB v16 migration adds `ai_chat_sessions.isArchived`). Delete
  (with confirm) stays in the same menu.

New testTags: `history_panel`, `history_new_chat_btn`, `ai_reply_duration`,
`ai_msg_copy_btn`, `ai_msg_share_btn`, `history_menu_btn`, `history_menu`,
`rename_input`, `rename_confirm_btn`.

## Safety properties

- Malformed/missing marker or JSON → the reply is shown as normal text,
  nothing is saved (parser degrades to `Normal`).
- Full-lead saves validate the mobile (10-15 digits) and reject duplicates.
- A new user message, or clearing/opening a session, dismisses a pending card.
- All existing testTags are unchanged; new ones: `ai_lead_action_card`,
  `ai_lead_save_confirm`, `ai_lead_save_draft`, `ai_lead_save_cancel`,
  `ai_lead_status_confirm`, `ai_lead_update_confirm`, `ai_lead_delete_confirm`,
  `mic_button`.
