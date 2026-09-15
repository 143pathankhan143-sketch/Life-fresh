# AI Status Audit & docs2 Implementation Plan

**Date:** 2026-09-15 · **Method:** every claim below was verified against `app/src` with
grep/find, not taken from the docs. Each finding lists the command-shaped evidence so it can be re-checked.
**Scope:** `docs2/` (71 markdown files, 39,747 lines / 303,403 words) vs shipped AI code (39 files, 6,264 lines).

---

# PART A — How much of the AI is actually built

## A.1 The number that matters

| Metric | Value |
| --- | --- |
| Spec documents in `docs2/` | 71 files · **303,403 words** |
| AI code that exists and is **wired into the app** | 39 files · **6,264 lines** |
| Of the 73 `Expected Future Files` the docs' own mapping table plans | **3 exist → 4%** |
| Docs' own status tally (`AI_Documentation_to_Code_Mapping.md`) | 2 implemented · 28 partial · 34 not implemented · 3 doc-only |
| AI test files that **cannot compile** (classes don't exist anywhere) | **13 files · 3,777 lines** |
| Spec-to-code ratio | ~49 words of spec per line of code |

**Bottom line:** there are **two different AI systems** in this project. One is finished and
works. The other one — the one all 71 documents describe — was never written.

## A.2 ✅ SHIPPED & WORKING (the "legacy" AI — 100% functional)

Verified reachable from the UI. This is what a user actually gets today.

| Capability | Implementation | Wiring proof |
| --- | --- | --- |
| **AI chat (cloud)** | `ai/chat/` 11 files / 974 lines — `AIProviderRouter` → `GeminiProvider` → `GroqProvider` with fallback, retry, auth-error short-circuit | `AIScreen.kt:54` `chatViewModel: AIChatViewModel = viewModel()` |
| **Local intent engine** | `ai/intent/IntentParser.kt` (228 lines) — **9 intents**, all 9 handled | `CRMViewModel.processAICommand()` handles all 9 (`SHOW_PENDING_LEADS`, `SHOW_TODAY_REMINDERS`, `SHOW_WEEKLY_REPORT`, `OPEN_ADD_LEAD`, `CREATE_LEAD`, `CREATE_REMINDER`, `UPDATE_LEAD_STATUS`, `ADD_LEAD_NOTE`, `UNKNOWN`) |
| **Entity extraction** | `ai/extraction/EntityExtractor.kt` (397 lines) — names (Hinglish + Devanagari regex), phone, HH:mm & "baje" time, am/pm disambiguation, relative date, "3 ghante baad" durations, status, note text | used by `IntentParser` + `LeadAIDraftAnswerParser` |
| **Real DB writes** | `ai/action/ActionDispatcher.kt` (308 lines) — 4 tools that genuinely write Room `LeadEntity`, dedupe by phone, reject ambiguous multi-match, block past-dated reminders, then call `ReminderScheduler.scheduleReminder()` | `CRMViewModel.kt` imports `ActionDispatcher`; `ReminderScheduler` referenced from 10 files |
| **Two-phase confirmation gate** | `PendingConfirmation` + `ConfirmationStatus{PENDING,EXECUTING,SUCCESS,FAILED,CANCELLED}`; EXECUTING disables the card (double-tap safe); cancel = zero DB change | `AIScreen.kt:129-130` `confirmAction` / `cancelAction` |
| **Structured lead drafting** | `leads/ai/` 15 files / 3,642 lines — `LeadAIWorkflow`, `LeadAIDraftManager`, `LeadAIConversationCoordinator`, `LeadAIExecutionCoordinator`, `LeadAIConfirmationMapper` | `CRMViewModel.kt:845` `LeadAIControllerFactory.create(...)`, `:852` `LeadAIViewModelBridge`, `:855` `leadAIConfirmationStates` → read at `AIScreen.kt:65` |
| **Voice loop** | `voice/` 5 files / 684 lines — STT + TTS + `VoiceConfirmationInterpreter` + state machine | `VoiceConversationManager` used by `AIScreen.kt` **and** `CRMViewModel.kt` |
| **Quota / abuse guard** | `data/security/AIQuotaManager.kt` | used in `LifeFreshApplication`, `AIServiceRepository`, `SettingsTab`, `SettingsComponents` |
| **Chat persistence** | Room `AIChatSessionEntity` / `AIChatMessageEntity` / `AIChatDao` (schema v13) | in `AppDatabase.kt` |
| **Kill switch** | `BuildConfig.AI_FEATURES_ENABLED` + `ReleaseFeatureFlags` | `CRMViewModel.kt:271` early-returns when false |

Order of resolution in `AIChatViewModel.sendMessage()`: **try local `processAICommand()` first →
if not handled, fall through to cloud Gemini/Groq.** That is a real, sane design: free, offline,
deterministic for the 9 known commands; LLM only for everything else.

## A.3 ⚠️ PARTIAL / STUB (exists but thin)

| Piece | Reality |
| --- | --- |
| `ai/language/LanguageNormalizer.kt` — **16 lines** | lowercases + strips `[!?,.()-]` + collapses spaces. Doc (`AI_Language_Engine_v1.0.md`, 7,519 words) specifies tokenizers, stemming, a trie, Devanagari romanisation. |
| `ai/language/SynonymLibrary.kt` — **31 lines, 11 sets** | **Dormant dead code — 0 callers.** `IntentParser` instead re-hardcodes the same words inline (`contains("pending") \|\| contains("baaki") \|\| contains("baki") \|\| contains("बाकी")`). Doc `AI_Synonym_Library_v1.0.md` is **15,051 words / 714 table rows**. |
| `ToolRegistry` (`ai/action/ActionComponents.kt`) | `object` with one `when()` mapping intent→tool. No risk levels, no permissions, no input schema, no rollback/retry — all of which `AI_Tools_v1.0.md` (7,258 words) mandates. |
| `AIConfig.kt` | 56 lines, keys from `BuildConfig`; the "server-side proxy" path (`data/network/AIProxyService.kt`) exists but `metadata.json` advertises `MAJOR_CAPABILITY_SERVER_SIDE_GEMINI_API`. Needs verification that the proxy is the production path. |

## A.4 ❌ ZERO IMPLEMENTATION — spec'd, tested, never built

`AIManager`, `AIWorkflowEngine`, `AIAuditEngine`, `AIAnalyticsEngine`, `AIKnowledgeCacheEngine`,
`AIConversationEngine`, `AIActionEngine`, `OcrRegistry`, `DeviceHealthMonitor`, `IntentLibrary`,
`KnowledgeIndexer`, `ComplianceEvaluator`, `AIEventBus`, `AIStateMachine`, `LocalModelManager`,
ONNX runtime, plugin framework, policy/rule engines, bulk ops, scheduler engine —
**all return 0 hits** under `app/src/main/java`. Roadmap's 6 target folders:
`ai/domain`, `ai/application`, `ai/infrastructure`, `ai/presentation`, `ai/safety`, `ai/tools` —
**all 6 missing**; on-disk reality is `ai/action`, `ai/chat`, `ai/extraction`, `ai/intent`, `ai/language`.

## A.5 🚨 The trap that must be fixed first

`app/build.gradle.kts` contains **13 `exclude()` lines** on `KotlinCompile`:

```
VoiceIntelligenceTest  BulkIntelligenceTest  AIReminderIntelligenceTest  AIConversationEngineTest
AISchedulerEngineTest  DocumentIntelligenceTest  AIAnalyticsEngineTest  OcrIntelligenceTest
HybridRuntimeTest  AIKnowledgeCacheEngineTest  AIAuditEngineTest  AIWorkflowEngineTest
LLMIntegrationFrameworkTest
```

Verified: e.g. `AIWorkflowEngineTest` calls `AIManager.initialize()`, `AIWorkflowEngine.executeWorkflow()`,
`WorkflowStep`, `WorkflowDefinition`, `StepResult` — **none of those symbols is defined in `main`
OR in the test itself.** So these 3,777 lines are not "disabled tests"; they are **tests for an API
that was never written**. The exclusions were added so the build would go green.

Consequence today: `./gradlew test` passing tells you **nothing** about the AI engine layer.
Active AI tests are only: `IntentParserTest`, `ActionDispatcherTest`, `AIChatSystemTest`,
`AIChatCoreStabilityTest`, `AIMessageFormatterTest`.

## A.6 Documentation defects found

1. `AI_Implementation_Roadmap.md` §"Legacy AI Removal Status" states `com/example/ai` and
   `AIScreen.kt` *"have been completely removed"*. **False** — `com/example/ai` holds 19 files and
   `AIScreen.kt` is 739 lines. Worse: that "removed legacy AI" is the *only* AI that works.
2. `AI_Documentation_to_Code_Mapping.md` is **stale** — it lists `EntityExtractor.kt`,
   `LanguageNormalizer.kt`, `SynonymLibrary.kt` as "Expected Future Files" with status
   *Not Implemented*, but all three shipped afterwards. It under-reports real progress.
3. Every doc references `/docs/...`; the actual folder is `/docs2/...`.
4. `docs2/sprints/Sprint_01` & `Sprint_02*` describe `com/example/ui/ai/` (`AITokens.kt`,
   `AIHomeScreen.kt`, `AIHistoryScreen.kt`, routes `ai_home`/`ai_history`/`ai_settings`) —
   **package does not exist, 0 references.** A third architecture that also never landed.
5. `AI_Leads_Implementation_Audit.md` is the **only trustworthy** document: it independently
   names the same four things found here (working deterministic parser, confirmation gate,
   13 excluded test suites, `SynonymLibrary` as dormant debt). Treat it as the baseline;
   treat the other 67 as aspiration.

---

# PART B — How to implement `docs2` into the project

## B.1 The method (use this, don't hand-write)

Do **not** implement doc-by-doc in alphabetical order. Use this pipeline:

1. **Harvest the contract.** Each engine doc contains a target file list + acceptance behaviour.
   `AI_Documentation_to_Code_Mapping.md` already aggregates 73 `Expected Future Files` — that is
   the work backlog, machine-extractable:
   ```bash
   grep -A2 "Expected Future Files" docs2/AI_Documentation_to_Code_Mapping.md \
     | grep -oE '`[A-Za-z0-9_/]+\.kt`' | tr -d '`' | sed 's|.*/||' | sort -u
   ```
2. **Filter to what the app needs.** 73 planned files is not a roadmap, it's a wishlist. Keep only
   files that close a *user-visible* or *safety* gap; drop the rest into a "won't build" list with a
   reason (that's an honest doc edit, which this repo badly needs).
3. **Translate vocabulary.** The docs were drafted against a different stack (the roadmap even has a
   *"Flutter Layer Mapping"* section and `I`-prefixed interfaces). Rule for this codebase:
   no `I` prefixes, no abstract-service-locator — match existing style: `object` singletons for
   stateless registries, constructor injection via `CRMViewModel`/`LeadAIControllerFactory`,
   `StateFlow` for state, `Room` for persistence.
4. **One engine = one PR = one test file that compiles.** Never add an engine without un-excluding
   (or newly writing) its test. The exclusion list is how 3,777 lines of fiction survived.
5. **Reconcile docs at the same time** — update the doc's `Status:` line to `Implemented` in the
   same commit, so `AI_Documentation_to_Code_Mapping.md` becomes a live scoreboard again.

## B.2 ⚠️ Decide the architecture fork before writing code

The docs and the code disagree on first principles, and **you cannot have both**:

| | **Option A — Harden what exists** | **Option B — Build the docs' offline AI** |
| --- | --- | --- |
| Basis | Cloud Gemini/Groq + local deterministic parser | ONNX/GGUF local models, 256 MB RAM cap, 150 MB storage cap, airplane-mode operation |
| Docs satisfied | ~15 of 71 | all 71 |
| New code | ~1,500–2,500 lines | **~10,000–15,000 lines** + model assets + JNI/runtime deps |
| Works with no network | only the 9 intents | everything |
| Cost driver | API keys, per-request latency | model files, device profiling, quantisation, memory/thermal guards |
| Time (solo) | **2–3 weeks** | **3–6 months** |

Everything in **Phase 1 below is useful for both options** — it's the plumbing (audit, events,
states, tool contracts) the docs insist on, and it also makes the existing cloud AI safe.
So Phase 1 needs no decision; **Phase 2 does.**

## B.3 Execution plan

### Phase 0 — Truth and safety net (½ day, do this first, unblocks everything)
* Delete the 13 `exclude()` lines **and** delete the 13 test files, or convert them into
  `@Ignore("engine not implemented — see docs2/<X>.md")` stubs. Either way the build must say
  what is real. Recommended: **delete** — they test imaginary APIs and will mislead the next person.
* Fix the 5 doc defects in A.6 (roadmap's false "legacy removed" claim, stale statuses, `/docs`→`/docs2`).
* Add `docs2/AI_STATUS_AND_IMPLEMENTATION_PLAN.md`'s scoreboard section as the single source of truth.
* **Exit:** `./gradlew test` compiles all remaining AI tests, and every green test maps to real code.

### Phase 1 — Foundations the docs demand and the app lacks (~1 week)
| # | Deliverable | New files | Doc contract | Test |
| --- | --- | --- | --- | --- |
| 1.1 | AI state machine | `ai/domain/AIState.kt` | `AI_StateMachine_v1.0.md`; roadmap names the exact 10 states: `Idle, Understanding, Planning, Confirming, Executing, Verifying, Completed, Failed, RolledBack, Degraded` | `AIStateMachineTest` — transitions, thread-safety <1 ms |
| 1.2 | Event bus | `ai/event/AIEvent.kt`, `ai/event/AIEventBus.kt` | `AI_EventBus_v1.0.md`; roadmap: coroutine `SharedFlow`, non-blocking | `AIEventBusTest` — replay/buffer behaviour |
| 1.3 | **Audit trail** | `data/database/AIAuditLogEntity.kt`, `AIAuditDao.kt`, `ai/safety/AIAuditEngine.kt` | `AI_Audit_Engine.md`; roadmap gives the **exact SQL**: `ai_audit_logs(id, timestamp, tool_name, inputs_hash, signature, status)`; SHA-256 of inputs + `AppDatabase` migration **v13→v14** | `AIAuditEngineTest` (the existing 306-line test already describes the API — start from it) |
| 1.4 | Wire the audit into execution | edit `ai/action/ActionDispatcher.kt` + `leads/ai/LeadAIExecutionCoordinator.kt` | every `executeAction()` result persisted, success **and** failure | extend `ActionDispatcherTest` |

> 1.3 is the highest-value item in this whole file: today **no AI action is recorded anywhere**,
> so a wrong "mark as Complete" cannot be traced or explained. It is also the only doc'd feature that
> is both cheap and safety-relevant.

### Phase 2 — Choose (½–3 months, depends on Option A vs B)
* **Option A:** skip; keep cloud LLM as the general engine and grow the local parser.
* **Option B:** `ai/infrastructure/LocalModelManager.kt`, `HardwareProfiler.kt`, ONNX Mobile
  dependency, model asset pipeline with SHA-256 verification, 256 MB / 150 MB budget guards,
  thermal + battery(<15%) throttle → `AI_Runtime_v1.0.md`, `AI_Model_Manager.md`, `AI_Health_Monitor.md`.

### Phase 3 — Language layer (~1 week)
* Make `IntentParser` **consume** `SynonymLibrary` instead of inlining duplicates → the 714-row
  `AI_Synonym_Library_v1.0.md` becomes the data source; ship it as a Room asset or generated
  Kotlin map, not 15 KB of literals.
* Extend `LanguageNormalizer` with the doc'd romanisation/stemming rules; property-test 50 Hinglish
  phrasings per intent (`AI_Test_Scenarios_v1.0.md` has 33 scenarios to crib from).
* Replace the fragile `contains()` chains with a scored matcher (keyword hit + entity presence →
  confidence), keeping `UNKNOWN → cloud` fallback. Target: doc's "98% correct on test strings".
* Add intents the CRM already supports but the AI cannot say: delete/archive lead, search by
  disease, reschedule reminder, generate report PDF.

### Phase 4 — Tool contracts & safety gates (~1 week)
* Introduce the roadmap's base class verbatim:
  ```kotlin
  abstract class AppControlTool {
      abstract val name: String
      abstract val riskLevel: RiskLevel
      abstract val requiredPermissions: List<String>
      abstract suspend fun execute(inputs: Map<String, Any>): ToolResult
  }
  ```
  Migrate the 4 `AITool` branches into `ai/tools/crm/*Tool.kt`; `ToolRegistry` becomes real
  (manifest, input validation, cancellation, retry, rollback).
* `ai/safety/ConfirmationBarrier.kt` + `PolicyGatekeeper.kt` + `RuleEngine`
  (`AI_Confirmation_Engine.md`, `AI_Policy_Engine.md`, `AI_Rule_Engine.md`) — move the validation
  currently split across `IntentParser`/`ActionDispatcher`/`LeadValidation` into one gate.
* Add `BulkStatusUpdateTool` behind it (`AI_Bulk_Operations_v1.0.md`) — bulk edits are the scariest
  un-gated mutation left.

### Phase 5 — Ops & validation (ongoing)
`AI_Feature_Flag_Engine.md` (replace the single `AI_FEATURES_ENABLED` bool), `AI_Error_Catalog_v1.0.md`
(error codes → user-facing strings; today errors are raw exception text), `AI_Cache_Engine.md`,
`AI_Backup_Recovery_Engine.md` (AI sessions are **not** in the current export/backup path),
airplane-mode suite per `AI_Test_Scenarios_v1.0.md`.

## B.4 Sequencing summary

```
Phase 0  ██████ ½ day   no decision needed   ← start here, everything else depends on honest tests
Phase 1  ██████ 1 week  no decision needed   ← state machine, event bus, AUDIT LOG (highest value)
Phase 2  ????          DECISION REQUIRED     ← Option A (skip) vs Option B (3-6 months, ONNX)
Phase 3  ██████ 1 week  either option         ← synonyms/normalizer/intents
Phase 4  ██████ 1 week  either option         ← tool base class + policy gates + bulk
Phase 5  ░░░░░░ ongoing                       ← flags, error catalog, backup, offline tests
```

**Do not start Phase 1 until Phase 0's exclusion list is dealt with** — otherwise new tests get
added to a build whose test task is already a lie.