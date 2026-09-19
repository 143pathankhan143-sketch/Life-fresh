# LifeFresh QuickNote Pro — Codex Handoff

## Current working project
Path:
~/lifefresh-project-check

This is the latest working source used to build and test the current signed APK.

## Backup
Full private backup created:
~/lifefresh-project-check-BACKUP-AI-WORKING-2026-09-02

## Current AI scope
Only the general-purpose chatbot is being worked on.

Current goal:
- text chat
- AI replies
- multi-turn conversation context
- Groq primary
- Gemini fallback
- loading/thinking
- retry
- clear/new chat
- duplicate-send protection
- Back button

Do NOT currently work on:
- CRM AI
- leads AI
- reminders AI
- voice/TTS
- AI history UI
- AI settings UI
- OCR/docs
- analytics
- scheduler
- bulk tools

## Active AI provider files
- app/src/main/java/com/example/ai/chat/config/AIConfig.kt
- app/src/main/java/com/example/ai/chat/provider/AIProvider.kt
- app/src/main/java/com/example/ai/chat/provider/AIProviderResult.kt
- app/src/main/java/com/example/ai/chat/provider/AIProviderRouter.kt
- app/src/main/java/com/example/ai/chat/provider/GroqProvider.kt
- app/src/main/java/com/example/ai/chat/provider/GeminiProvider.kt
- app/src/main/java/com/example/ai/chat/repository/AIChatRepository.kt
- app/src/main/java/com/example/ai/chat/viewmodel/AIChatViewModel.kt
- app/src/main/java/com/example/ui/screens/AIScreen.kt

## API configuration
Root .env is the secrets source.

Expected variables:
GROQ_API_KEY
GEMINI_API_KEY

Never print, expose, or commit real secret values.

Both keys were verified as present without printing values.

Direct Termux connectivity checks:
- Groq /models -> HTTP 200
- Gemini /models -> HTTP 200

## Provider fixes completed

### Groq
Old model:
llama-3.3-70b-versatile

Current model:
openai/gpt-oss-120b

Reason:
The old model stopped working for the developer/free-tier configuration.

### Gemini
Current model:
gemini-2.5-flash

Authentication changed from API key in the URL query string to:
x-goog-api-key header

## Chat stability fixes completed
- Back button wired through AIScreen onExit.
- Overlapping sends rejected.
- Active send/retry job tracked.
- Clear/New Chat cancels active request.
- Stale late responses are discarded.
- Retry does not duplicate the user message.
- Clear resets messages, thinking, errors, retry state, and active provider.
- Unconfigured providers are skipped.
- Groq -> Gemini fallback order retained.
- Provider HTTP response bodies are not logged.
- CancellationException is rethrown correctly.

## Focused tests
Command:
gradle testDebugUnitTest --tests com.example.ai.chat.AIChatCoreStabilityTest

Result:
BUILD SUCCESSFUL

4 tests passed
0 failures
0 errors
0 skipped

Tested:
- Groq unavailable -> Gemini routing
- neither provider configured -> friendly config error
- concurrent duplicate sends rejected
- clear invalidates stale responses and resets state

## Real-device verification
Signed APK was built using:
build-lifefresh-signed-apk

Latest tested APK path:
~/storage/shared/Download/LifeFresh-QuickNote-Pro-v1.0-signed-TEST.apk
(actual Android storage path may appear as /storage/emulated/0/Download/...)

Real-device results:
- AI screen opens
- Back button works visually
- AI sends real provider responses
- previous generic service error is resolved
- multi-turn context in the SAME chat session works
- AI successfully recalled multiple earlier questions from the same conversation

Important:
Persistent chat history across app restart/new chat is NOT implemented yet.

## Known pending issues

1. Persistent conversation history
Current chatbot conversation is in-memory only.

2. Provider indicator
A small UI indicator showing the provider used for the latest reply
(Groq/Gemini) may be added later.

3. AI identity/persona
The model has made invented statements such as saying a "LifeFresh product team"
or "data scientists" trained it.

This is a persona/system-prompt issue, not a provider/context failure.
It should later be corrected with a truthful system prompt.

4. Old CRM-oriented UI copy
Some AI UI text still mentions leads/CRM.
Do not redesign UI unless explicitly requested.

## Important project/build environment
Do NOT change the proven Termux Android build setup unless necessary.

Current project:
~/lifefresh-project-check

Build test APK:
build-lifefresh-signed-apk

Play AAB:
build-lifefresh

Do NOT use direct assembleRelease because it has produced malformed APKs
in this Termux environment.

## Security rules
- Never print or cat .env
- Never expose API keys
- Never expose signing passwords
- Never expose keystore credentials
- Do not add secrets to .env.example
- Do not commit .env
- BuildConfig API keys are extractable from a distributed APK, so this setup
  is acceptable for current testing but not ideal for secure public production.

## Recommended next work
1. Preserve current working state.
2. Add a truthful AI system/persona prompt.
3. Later add optional provider indicator.
4. Later decide whether persistent chat history is needed.
5. Keep CRM AI isolated until explicitly requested.
