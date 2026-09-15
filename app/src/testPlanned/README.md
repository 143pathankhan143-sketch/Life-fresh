# Planned (Not Yet Compiled) Test Suites

The test files in this directory are **intentionally not part of the Gradle
test source set**. They were previously excluded from `KotlinCompile` in
`app/build.gradle.kts` because they reference production classes that do not
exist in this codebase yet:

| Test file | Production class it targets | Status |
|---|---|---|
| `AIConversationEngineTest` | `AIConversationEngine` | Planned — see `docs2/AI_Conversation_Engine` / `AI_Dialogue_Engine` specs |
| `AIAnalyticsEngineTest` | `AIAnalyticsEngine` | Planned — `docs2/AI_Analytics_Engine.md` |
| `AIAuditEngineTest` | `AIAuditEngine` | Planned — `docs2/AI_Audit_Engine.md` |
| `AIKnowledgeCacheEngineTest` | `AIKnowledgeCache` | Planned — `docs2/AI_Cache_Engine.md` / `AI_Knowledge_Engine_v1.0.md` |
| `AIReminderIntelligenceTest` | `NaturalLanguageTimeParser` | Planned — `docs2/AI_Scheduler_Engine_v1.0.md` |
| `AISchedulerEngineTest` | `AISchedulerEngine` | Planned — `docs2/AI_Scheduler_Engine_v1.0.md` |
| `AIWorkflowEngineTest` | `AIWorkflowEngine` | Planned — `docs2/AI_Workflow_Engine_v1.0.md` |
| `BulkIntelligenceTest` | `BulkIntelligenceEngine` | Planned — `docs2/AI_Bulk_Operations_v1.0.md` |
| `DocumentIntelligenceTest` | `DocumentIntelligence` | Planned — document/OCR intelligence roadmap |
| `HybridRuntimeTest` | `HybridRuntime` | Planned — `docs2/AI_Runtime_v1.0.md` |
| `LLMIntegrationFrameworkTest` | `AIManager` | Planned — `docs2/AI_Model_Manager.md` |
| `OcrIntelligenceTest` | `OcrIntelligence` | Planned — OCR integration roadmap |
| `VoiceIntelligenceTest` | `VoiceIntelligence` | Planned — `docs2/AI_Input_System_v1.0.md` (voice engine already exists under `com.example.voice` under a different name; test needs re-targeting) |

## How to activate a suite

1. Implement (or rename) the production class the suite targets.
2. Move the file back into `app/src/test/java/` (same package path).
3. Fix any compile errors, then make sure it passes `./gradlew test`.

Nothing in this folder is compiled by `./gradlew test`, so the build is green
while these remain planned.
