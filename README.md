# LifeFresh QuickNote Pro

Native Android **CRM + Quick Notes** app (Kotlin / Jetpack Compose), originally generated in
Google AI Studio and converted from the LifeFresh CRM. Offline-first by design: Room is the
source of truth, Firestore is an optional sync target.

**Package:** `com.lifefreshcrm.pro.inkgql` · **Version:** 1.0.1 (code 2)
**minSdk:** 24 · **targetSdk / compileSdk:** 36

> This repository previously shipped the whole project inside
> `remix-lifefresh-quick-note-pro.zip`. The zip has been unpacked so the sources,
> build scripts and docs are real, reviewable files. The original archive is still
> recoverable from git history (commit `df85c4a`).

---

## Feature map

| Area | What it does | Key code |
| --- | --- | --- |
| **Dashboard** | Lead analytics, performance overview, pending/completed counts | `ui/screens/DashboardTab.kt` |
| **Leads** | Lead CRUD, native search, multi-select filters, bulk ops, WhatsApp follow-up | `ui/screens/LeadsTab.kt`, `leads/` |
| **AI chat** | Conversational assistant with provider routing + quota guard | `ai/chat/`, `ui/screens/AIScreen.kt` |
| **AI actions** | Local intent parsing, entity extraction, confirmation-gated execution | `ai/intent/`, `ai/extraction/`, `leads/ai/` |
| **Reminders** | Exact alarms, 10 ringtones, snooze/dismiss/complete actions, boot reschedule | `audio/`, `reminder/` |
| **Voice** | Speech recognition + TTS conversation loop with voice confirmations | `voice/` |
| **Sync** | Outbox / conflict / checkpoint tables, WorkManager auto-sync | `sync/` |
| **Reports** | PDF export via FileProvider | `pdf/PdfGenerator.kt`, `ui/screens/ReportsTab.kt` |
| **i18n** | English + Hindi, Tamil, Urdu; runtime string packs | `data/AppStrings.kt`, `res/values-*/` |
| **Account** | Firebase auth, multi-account isolation, account deletion | `account/`, `ui/viewmodel/AuthViewModel.kt` |

## Tech stack

Compose + Material 3 · Navigation Compose · Room 2.7 (schema v13, migrations 5→13) ·
Retrofit + OkHttp + Moshi · Firebase Auth & Firestore · WorkManager · Play Feature Delivery ·
JUnit + Robolectric + Roborazzi (screenshot tests) · AGP 9.1.1 · Kotlin 2.2.10

---

## Run locally

**Prerequisites:** [Android Studio](https://developer.android.com/studio) and a JDK 17+.

1. Open Android Studio → **Open** → select this project directory.
   Allow Android Studio to fix any import incompatibilities.
2. Create your secrets file and add a Gemini key (Groq is optional):
   ```bash
   cp .env.example .env
   # then edit .env and set GEMINI_API_KEY=...
   ```
   Keys are injected into `BuildConfig` by `app/build.gradle.kts`; they are never hardcoded.
3. **First build only:** `app/build.gradle.kts` wires the debug build to a `debug.keystore`
   that is not committed. Either drop a `debug.keystore` in the project root, or delete the
   line `signingConfig = signingConfigs.getByName("debugConfig")` from the `debug` block.
4. Run on an emulator or physical device.
5. If you have previously published this app from AI Studio,
   [request an upload key reset](https://support.google.com/googleplay/android-developer/answer/9842756)
   in the Google Play Console.

### Gradle wrapper

The upstream archive did **not** include `gradlew`, `gradlew.bat` or
`gradle/wrapper/gradle-wrapper.jar`. Generate them once locally with an installed Gradle:

```bash
gradle wrapper --gradle-version 8.14
```

Or just let Android Studio manage the build.

## Tests

```bash
./gradlew test            # unit + Robolectric + Roborazzi screenshots
./gradlew connectedCheck  # instrumented (needs a device/emulator)
```

> ℹ️ Planned test suites: 13 advanced AI test classes (e.g. `VoiceIntelligenceTest`,
> `LLMIntegrationFrameworkTest`, `AIAuditEngineTest`) target production classes that
> do not exist yet. They now live in `app/src/testPlanned/` (not a Gradle source set,
> so not compiled) instead of being `exclude`d from `KotlinCompile`. See
> `app/src/testPlanned/README.md` for the mapping and activation steps.

## Project layout

```
app/                     Android module (111 Kotlin sources, ~35k LOC)
  src/main/java/com/example/
    ai/                  chat providers, intent, extraction, language, actions
    leads/               AI workflow, validation, operation service
    audio/  reminder/    alarms, ringtones, scheduling, boot receiver
    voice/               STT / TTS conversation manager
    sync/                outbox, conflicts, checkpoints, Firestore data source
    data/                Room DB, repositories, language packs, AI quota
    ui/                  screens (Compose), theme, viewmodels
  src/test/java/         32 active test classes
  src/testPlanned/       13 planned test suites (not compiled; see its README)
docs2/                   68 AI/engineering specification docs + sprints/
gradle/libs.versions.toml  version catalog
```

## Documentation notes

`docs2/` holds the specification set for the AI subsystem.

- **Start here for current truth:** `docs2/AI_Status_Baseline_v2.md` — the
  feature-by-feature status sheet (Ready / Partial / Disconnected / Planned /
  Deferred), updated at the end of every implementation phase.
- `docs2/DB_Migration_Policy.md` — Room migration rules (no destructive
  fallbacks, batched version bumps, migration tests mandatory).
- `docs2/AI_Documentation_to_Code_Mapping.md` — the original (July 2026)
  spec-to-code audit; kept as **history** (it predates the current
  parser/voice/chat stack). Its verdict at the time: **2 implemented ·
  28 partially implemented · 34 not implemented · 3 documentation-only**.

Two known documentation/code mismatches to keep in mind:

* `docs2/sprints/Sprint_01_*` and `Sprint_02_*` describe a `com.example.ui.ai` package
  (`AITokens.kt`, `AIHomeScreen.kt`, `AIHistoryScreen.kt`, routes `ai_home`, `ai_history`, …)
  that **does not exist** in this source snapshot.
* The docs reference `/docs/...` paths, but the folder in this repo is `/docs2/...`.

## Security note

`firebase-applet-config.json` is a leftover from the AI Studio applet scaffold — nothing in
`app/src` reads it. `app/google-services.json` (project `lifefresh-quicknote-pro`) is the
config actually used by the Firebase Gradle plugin. Review both before any public release and
rotate any key that has lived in a public repository.
