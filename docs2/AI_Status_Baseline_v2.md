# AI Status Baseline v2 — LifeFresh QuickNote Pro

**Date:** 15 September 2026 (after Phase 0)
**Supersedes (partially):** `AI_Documentation_to_Code_Mapping.md` (July 2026)

> The mapping doc remains a useful **historical** audit, but it describes the
> codebase of the Room schema-v7 era — before `IntentParser`, `EntityExtractor`,
> `ActionDispatcher`, the `leads/ai` workflow stack, the voice layer and the
> current chat screen existed in their current form. Use **this sheet** for the
> current truth. Update it at the end of every implementation phase.

## Legend

- **Ready** — implemented, tested/verified, production-usable
- **Partial** — implemented but with known limitations (listed)
- **Disconnected** — code exists and works, but no UI entry point currently
- **Planned** — specified in `docs2/`, not yet built (phase assigned)
- **Deferred** — intentionally not building now (decision + reason recorded)

## Core AI features

| # | Feature area | Status | Evidence (code) | Known issues / notes | Target |
|---|---|---|---|---|---|
| 1 | Intent parsing (9 intents, Hindi/Hinglish/English) | **Partial** | `ai/intent/IntentParser.kt` | Trigger-word dependent; long conversational sentences fall to `UNKNOWN`; intent priority/confidence rules from `AI_Intent_Library` not applied | Phase 1 |
| 2 | Entity extraction (name, phone, date, time, status, note) | **Partial** | `ai/extraction/EntityExtractor.kt` | Multi-word names truncated ("Rajesh Kumar" → "Rajesh"); honorifics break names ("Sharma ji ko" → "Ji"); spaced/hyphenated phones ("98765-43210" → "98765"); weekdays & specific dates unsupported | Phase 1 |
| 3 | Synonym library | **Planned** | `ai/language/SynonymLibrary.kt` exists but is **dead code** (zero references); synonyms are hard-coded inline in parser/extractor | Wire it in, or delete it | Phase 1 |
| 4 | Language normalization | **Partial** | `ai/language/LanguageNormalizer.kt` | Only lowercase + punctuation strip; no Devanagari→Latin normalization | Phase 1 |
| 5 | Action execution (create lead, reminder, status, note) | **Ready** | `ai/action/ActionDispatcher.kt` | Duplicate-phone block, past-time block, ambiguity abort all in place | — |
| 6 | Confirmation gate (no unconfirmed writes) | **Ready** | `CRMViewModel.confirmAction/cancelAction`, `leads/ai/LeadAIConfirmation*` | Pending confirmation state is in-memory (lost on process death); DB persistence of pending confirmations is planned | Phase 2 |
| 7 | Create-lead multi-turn draft workflow | **Ready** | `leads/ai/LeadAIWorkflow.kt` + coordinator/execution stack | Idempotency + ALREADY_EXECUTED semantics verified by design & tests | — |
| 8 | Stateful clarification (multi-turn completion of missing fields) | **Planned** | — | Today clarification is single-turn: after "Reminder kis din ke liye?", the answer is parsed as a brand-new command and context is lost | Phase 2 |
| 9 | Multi-action / compound sentences | **Planned** | — | "Rahul aur Salman dono ki leads banao" → only the first lead is processed | Phase 2 |
| 10 | Fuzzy lead search (typos, same-name disambiguation UX) | **Partial** | `ActionDispatcher.resolveLeads` (substring match) | No Levenshtein/phonetic fallback ("Slaman" fails); ambiguity lists names but no tappable picker | Phase 1 |
| 11 | Chat persistence (durable across rotation, screen exit, process death) | **Ready** *(fixed in Phase 0)* | `AIChatViewModel` + `CRMViewModel.ensureActiveSession/loadActiveSessionMessagesOnce` + `AIChatDao` | Previously: entire conversation was in-memory only and wiped on process death / screen exit. Now mirrored to Room and restored on re-entry. Tested in `AIChatSystemTest`. | — |
| 12 | LLM chat (Gemini + Groq, provider fallback) | **Ready** | `ai/chat/provider/AIProviderRouter/GeminiProvider/GroqProvider` | Gemini tries a model fallback chain; auth errors stop immediately; quota manager present | — |
| 13 | Voice engine (STT + TTS + voice confirmations) | **Disconnected** | `voice/VoiceConversationManager/SpeechRecognitionManager/TextToSpeechManager/VoiceConfirmationInterpreter` | Engine is complete and tested (`VoiceConversationSystemTest`), but **the current AIScreen has no mic/voice UI** — nothing in the UI calls `toggleVoiceMode`. Legacy `sendAIMessage` voice path persists to Room. | Phase 2 (re-wire) |
| 14 | Reminders & audio alarms | **Ready** | `audio/ReminderScheduler`, `reminder/AlarmScheduler`, 10 ringtones, boot reschedule | — | — |
| 15 | Pending leads / today reminders / weekly report views | **Partial** | `CRMViewModel.processAICommand` SHOW_* branches | Intents open the corresponding tab/card; weekly report is not computed inside the chat itself | Phase 4 |
| 16 | Tool registry (schema-registered actions) | **Planned** | — | The 4 actions work but are hard-wired in `ActionDispatcher`/`CRMViewModel`, not a registry with per-tool schema/risk metadata | Phase 4 |
| 17 | Additional intents (delete client, search, update/delete/done-reminder, show overdue, export/import JSON) | **Planned** | `AI_Intent_Library_v1.0.md` master list (16 intents) | 9 implemented today | Phase 4 |
| 18 | Audit logging (tamper-evident AI action trail) | **Planned** | — | No audit table yet | Phase 3 |
| 19 | Validation engine (centralized schema/bounds validation) | **Partial** | `leads/domain/LeadValidation.kt` | Exists for the lead form; not a centralized barrier for all AI writes yet | Phase 3 |
| 20 | EventBus (type-safe module messaging) | **Planned** | — | Modules still reference each other directly | Phase 5 |
| 21 | Runtime modes (offline/online/hybrid auto-switch) | **Partial** | Provider router covers LLM fallback; deterministic parser is offline | No formal runtime-mode state or guards (battery/thermal) yet | Phase 5 |
| 22 | Health monitor / feature flags (per-intent toggles) | **Partial** | `config/ReleaseFeatureFlags.kt` (single master flag) | No per-intent flags, no thermal/battery guards | Phase 5 |
| 23 | On-device model (ONNX inference) | **Deferred** | `AI_Model_Manager.md`, roadmap Phase 2 | Decision: the local deterministic parser + cloud LLM hybrid already satisfies the product need; ONNX adds ~150MB assets and heavy device-benchmark work with no user-visible benefit today | revisit only if offline LLM chat becomes a hard requirement |
| 24 | Enterprise security (RBAC/ABAC, crypto session binding, compliance engine, autonomous agents) | **Deferred** | `AI_Permission_Engine.md`, `AI_Compliance_Engine.md`, `AI_Autonomous_Engine.md` | Decision: over-engineered for the current product scope. Data-privacy principles (no PII in cloud chat payloads beyond the API itself, local-first storage) already hold | revisit only if targeting a clinical/enterprise market |

## Cross-cutting systems (non-AI, verified healthy)

| Area | Status | Evidence |
|---|---|---|
| Lead CRUD + search + filters | Ready | `ui/screens/LeadsTab.kt`, `data/repository/LeadRepository.kt` |
| Multi-account isolation | Ready | `MultiAccountIsolationTest`, `account/` |
| Firestore sync (outbox/conflict/checkpoint) | Ready | `sync/`, `SyncRepositoryTest` |
| Reports + PDF export | Ready | `pdf/PdfGenerator.kt`, `ReportsTab.kt` |
| i18n (EN/HI/TA/UR runtime packs) | Ready | `data/AppStrings.kt`, `LanguagePackManager` |
| Authentication (Firebase) | Ready | `AuthScreens.kt`, `AuthViewModel` |

## Test baseline (after Phase 0)

- **Active & compiled:** 32 test classes in `app/src/test/` (run by `./gradlew test`).
- **Planned (not compiled):** 13 suites in `app/src/testPlanned/` — each documented with the production class it waits for (`app/src/testPlanned/README.md`).
- **New in Phase 0:** 5 restore/persistence tests in `AIChatSystemTest`.

## Phase 0 changelog (15 September 2026)

1. Moved 13 planned-only test suites out of the test source set (no more
   `KotlinCompile` exclusions); documented activation steps.
2. Made AI chat durable: every message mirrored to Room; conversation restored
   on screen entry / process death; "New Chat" starts a fresh session without
   deleting history; confirmation-result persistence made deterministic
   (direct Room reads instead of stale shared-flow values).
3. Removed the global `fallbackToDestructiveMigration()` from `AppDatabase.kt`
   (silent data-erasure risk) — see `DB_Migration_Policy.md`.
4. Created this baseline sheet + `DB_Migration_Policy.md`.

## Process rule

At the end of every phase: update this sheet (status + evidence + issues),
commit it with the phase commit, and keep `AI_Documentation_to_Code_Mapping.md`
as history only.
