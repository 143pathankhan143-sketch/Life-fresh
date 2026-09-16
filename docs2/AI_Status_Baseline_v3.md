# AI Status Baseline v3 — LifeFresh QuickNote Pro

**Date:** 16 September 2026 (after the agent-layer removal)
**Supersedes:** `AI_Status_Baseline_v2.md` (15 September 2026) and
`AI_Documentation_to_Code_Mapping.md` (July 2026, historical only)

## What changed vs v2

On 16 September 2026 the entire deterministic **agent layer was deleted**
(user decision: the first agent attempt was half-baked and untrustworthy; the
project will be a pure chatbot first, and the agent will be rebuilt from
scratch — with a fresh design — only after the chatbot is complete).

Deleted (code that understood/acted on commands):

| Area | What was removed |
|---|---|
| `ai/intent/`, `ai/extraction/`, `ai/language/`, `ai/action/` | IntentParser (9 intents), ParsedCommand, EntityExtractor, AIIntent, LanguageNormalizer, SynonymLibrary, ActionDispatcher, AITool, PendingConfirmation |
| `leads/ai/` | Whole lead-AI draft/workflow/confirmation stack (LeadAIDraftManager, LeadAIWorkflow, LeadAIController, confirmation lifecycle, answer parser) |
| `voice/` | VoiceConversationManager, SpeechRecognitionManager, TextToSpeechManager, VoiceConfirmationInterpreter (never wired to any UI) |
| `data/network/` | AIProxyService + AIProxyModels (backend proxy path) |
| `CRMViewModel` | `processAICommand` (~300 lines), `sendAIMessage` legacy path, confirm/cancel action stack, `AICommandState`, in-memory confirmation maps, voice properties, mock session pre-seed |
| `AIScreen` | Confirm/Cancel buttons + confirmation status text in message bubbles |
| `AIChatRepository` (chat) | `executeServiceQuery` / `appendExternalResult` (agent-only entry points) |
| `AIServiceRepository` | `processQuery` + proxy endpoint calls; **kept**: `testGeminiKey` (Settings → Test connection), `callDirectGeminiApi`, `isNetworkAvailable` |
| `app/src/testPlanned/` | 13 planned test suites written for the old agent design |

Kept (still part of the app):

- `leads/domain/LeadValidation.kt` (LeadDraft/LeadValidator/LeadSaveStatus) and
  `leads/operation/LeadOperationService.kt` — these serve the **manual** lead
  form (LeadFormDialog), not the AI.
- `data/security/AIQuotaManager.kt` — BYOK key storage + quota for Settings UI.
- Reminder/alarm machinery (`audio/`, `reminder/`) — not AI.

## Current architecture (pure chatbot)

```
AIScreen ── AIChatViewModel ── AIChatRepository (Default)
                              │    └── AIProviderRouter
                              │         ├── GeminiProvider  (model fallback chain)
                              │         └── GroqProvider
                              └── persistence: CRMViewModel.ensureActiveSession
                                               + saveMessage (Room, per-user)
Settings ── AIServiceRepository.testGeminiKey (key validation only)
```

- Every chat message goes through the LLM router. No local command parsing,
  no local action execution, no confirmations.
- Conversations are durable: mirrored to Room per signed-in user, restored on
  screen entry / process death, "New Chat" starts a fresh session without
  deleting history (see v2 item 11, fixed in Phase 0).

## Core AI features

| # | Feature area | Status | Evidence (code) | Notes |
|---|---|---|---|---|
| 1 | LLM chat (Gemini + Groq, provider fallback) | **Ready** | `ai/chat/provider/` | Gemini model fallback chain; auth errors fail fast |
| 2 | Chat persistence (rotation, screen exit, process death) | **Ready** | `AIChatViewModel.startPersistence`, `CRMViewModel.ensureActiveSession/loadActiveSessionMessagesOnce` | Tested in `AIChatSystemTest` |
| 3 | API-key validation ("Test connection") | **Ready** | `AIServiceRepository.testGeminiKey`, `settings/SettingsComponents.kt` | Probes models endpoint, picks a capable model, sends a live test prompt |
| 4 | Quota / BYOK management | **Ready** | `data/security/AIQuotaManager.kt` | 20/day free quota, unlimited with custom key |
| 5 | AI master feature flag | **Ready** | `config/ReleaseFeatureFlags.AI_FEATURES_ENABLED` | Gates the AI tab + persistence |
| 6 | Session history UI (list/rename/pin/delete past chats) | **Planned** (next task) | `CRMViewModel.dbChatSessions/renameSession/updateSessionPin/deleteSession` exist; no UI yet | Backend ready; build the history screen |
| 7 | Agent (command understanding + action execution + confirmations) | **Planned — from scratch** | — | Old design voided. New design to be decided after chatbot completion (stateful dialogue, tools, audit trail — see v2 items 1–24 as the old spec, history only) |

## Cross-cutting systems (non-AI, verified healthy)

| Area | Status | Evidence |
|---|---|---|
| Lead CRUD + search + filters (manual form) | Ready | `ui/screens/LeadFormDialog.kt`, `leads/operation/LeadOperationService.kt`, `data/repository/LeadRepository.kt` |
| Reminders & audio alarms | Ready | `audio/ReminderScheduler`, `reminder/AlarmScheduler`, 10 ringtones, boot reschedule |
| Multi-account isolation | Ready | `MultiAccountIsolationTest`, `account/` |
| Firestore sync (outbox/conflict/checkpoint) | Ready | `sync/`, `SyncRepositoryTest` |
| Reports + PDF export | Ready | `pdf/PdfGenerator.kt`, `ReportsTab.kt` |
| i18n (EN/HI/TA/UR runtime packs) | Ready | `data/AppStrings.kt`, `LanguagePackManager` |
| Authentication (Firebase) | Ready | `AuthScreens.kt`, `AuthViewModel` |

## Test baseline (after agent-layer removal)

- **Active & compiled:** test classes in `app/src/test/` (run by `./gradlew test`),
  including 5 restore/persistence tests in `AIChatSystemTest`.
- **Removed:** all agent test suites (`IntentParserTest`, `ActionDispatcherTest`,
  `LeadAIDraftManagerTest`, `LeadAIReminderTimeValidationTest`,
  `LeadValidationTest` agent parts, `VoiceConversationSystemTest`) and the
  13 `testPlanned/` suites.
- Proxy-related cases dropped from `AIQuotaManagerTest`; quota + BYOK +
  `testGeminiKey` cases kept.

## Process rule

At the end of every phase: update this sheet (status + evidence + issues) and
commit it with the phase commit. The old agent specification docs in `docs2/`
(`AI_Intent_Library_v1.0.md`, `AI_Permission_Engine.md`, etc.) are **history
only** — they describe the voided design and must not be treated as the current
spec.
