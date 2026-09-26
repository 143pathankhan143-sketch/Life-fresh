# Voice Full App Control — Agent Work Plan (`work.md`)

> **Spec:** VOICE_FULL_APP_CONTROL_SPEC.md v1.0 (full app voice control for illiterate users)
> **Branch:** `arena/01a0d87f-life-fresh`
> **Base:** `main` @ `3b25b51` + streaming voice fix (`e5fc443`, speakToken/barge-in)
> **Guardrail (NEVER forget):** PR merge ONLY on exact phrase **"bhaiya ji pr merged karo"**.
> **Status:** PLAN — implementation starts only after user says **"shuru karo"**.

---

## 0. Goal (ek line)

Anpadh user **sirf bolkar** poora LifeFresh chalaye — navigation, leads CRUD, backup/restore,
settings, AI chat — sab voice se, har action pe TTS feedback, aur **cloud backup delete pe
triple confirmation** (baar-baar). Text padhne ya type karne ki zarurat nahi.

---

## 1. Current code map (VERIFIED — fate hain)

### 1.1 Voice layer (already ready, reuse karo)
| Component | File | Public API we reuse |
|---|---|---|
| STT (mic) | `app/src/main/java/com/example/ai/chat/voice/VoiceInputHelper.kt` | `start(onResult,onError)`, `stop()`, `cancel()`, `shutdown()`, `isListening`, `isAvailable()` |
| TTS router | `app/src/main/java/com/example/ai/chat/voice/AiVoicePlayer.kt` | `speakSuspend(context,text)`, `testSpeak(context,sample)`, `stop()`, `isNaturalEnabled(context)` — aur ab **`speakToken` + barge-in wake** (fix `e5fc443`) |
| Device TTS | `app/src/main/java/com/example/ai/chat/voice/AiTts.kt` | `speak(context,raw,onFinished)`, `stop()`, `cleanForVoice()`, `onVoiceUnavailable` — `stop()` ab pendingText/onDone flush karta hai |
| Cloud TTS | `app/src/main/java/com/example/ai/chat/voice/GeminiTtsClient.kt` | `VOICES` (30 voices), `synthesize()`, `stableVoice()`, `isConfigured()` |
| Yes/No | `app/src/main/java/com/example/ai/chat/voice/VoiceWordMatcher.kt` | `isNegation(text)`, `isAffirmation(text)` — multilingual haan/nahi |

### 1.2 Lead actions (AI chat already does this — REUSE, not rebuild)
| What | Where |
|---|---|
| `LeadAction.Kind`: CONFIRM/DRAFT/STATUS/UPDATE/ARCHIVE/DELETE/BULK/WHATSAPP | `app/src/main/java/com/example/ai/chat/lead/LeadActionParser.kt` (class `LeadAction` line 15, parser line 99) |
| Hidden-block protocol `[LEAD_CONFIRM]...` parse + confirm card | `LeadActionParser.parse()`, `AIChatRepository.applyResultIfCurrent()` |
| Executors (card tap = same as agent) | `app/src/main/java/com/example/ui/viewmodel/CRMViewModel.kt`: `saveLeadFromAIChat` (1424), `updateLeadStatusFromAIChat` (1560), `updateLeadFromAIChat` (1663), `applyBulkFromAIChat` (1858), `archiveLeadFromAIChat` (1979), `deleteLeadFromAIChat` (2022), `openWhatsAppForLeadFromAIChat` (2075), `buildCrmSnapshot` (1255), `deleteLead(lead)` (2147) |

### 1.3 Backup / restore / delete (already exist — wrap with voice + gate)
| Action | Function in `CRMViewModel.kt` | Confirm level (spec) |
|---|---|---|
| Cloud backup | `backupAllToCloud(onComplete)` (614) | 1× haan |
| Cloud restore | `manualRestoreFromCloud(onComplete)` (762) | 1× haan (spec: pehle haan confirm) |
| **Cloud backup DELETE** | `deleteCloudBackup(onComplete)` (675) — Firestore users/{uid}/leads + sync wipe | **3× TRIPLE** |
| Local data delete | `resetAllData(onResult)` (2262) | 2× haan |
| Local JSON export/import | `exportBackupJson()` (2624), `importBackupJson` (2798) | 1× haan |

> **Note:** spec kehta hai cloud delete **triple** (exact `haan delete karo` / `yes delete` third step pe).
> Local delete **double** pe ho sakta hai kyunki backup se wapas aa sakta hai.

### 1.4 Navigation (jise voice se chalana hai)
| Route | Where | Kaise |
|---|---|---|
| `welcome`, `login`, `register`, `forgot_password` | `MainActivity.kt` NavHost (187–222) | leave as-is (auth ke pehle voice nahi) |
| `dashboard`, `leads`, `ai`, `reports`, `settings` | `MainActivity.kt` `navigateToTab` (428), bottom bar (487–555) | voice tab switch |
| `add_lead`, `edit_lead/{id}`, `profile/{id}`, `policy/{type}` | `MainActivity.kt` NavHost (579–651) | voice open/back |
| Settings sub-screens | `SettingsTab.kt` `activeSubScreen` string keys (228, 363) + `onActiveSubScreenChange` | voice open/back |

### 1.5 Settings voice targets
| Setting | Existing |
|---|---|
| App language | `AppLanguageManager.setLanguage(context, ...)` |
| AI natural voice + picker | `AIQuotaManager.setNaturalTtsEnabled`, `setTtsVoiceName`, `GeminiTtsClient.VOICES` |
| Bolo mode / voice reply | `AIQuotaManager.setBoloModeEnabled`, `setVoiceReplyEnabled` |
| i18n | `AppStrings` (`data/AppStrings.kt`) + `res/values-{hi,ta,ur}/strings.xml` |

---

## 2. Architecture (new)

```
                ┌───────────────────────────────────────────────┐
   mic (STT) ──▶│               VoiceAppController               │
  "bolo/sun raha"│   (single entry; owns the listen→act→speak    │
                │    loop + barge-in + 2s hear-window)           │
                └──────┬───────────────┬───────────────┬─────────┘
                       │               │               │
               VoiceCommandParser  VoiceConfirmGate  VoiceFeedback(TTS)
                       │               │               │
        VoiceWordMatcher (haan/nahi)  │ 1x/2x/3x(Triple)│
                       │               │               │
              rule-based command map  + exact phrase   + context announce
                       │               │  "haan delete karo"
                       ▼               ▼               ▼
          ┌────────────────────────────────────────────────┐
          │        App Action Bridge (VoiceNavigator)       │
          │  navController.navigate(...)  +  CRMViewModel.* │
          │  (lead CRUD, backup/restore/delete, settings)   │
          └────────────────────────────────────────────────┘
```

**Design rules:**
1. `VoiceAppController` = ek `object` (jaise `AiVoicePlayer`) — sab kuch yahi se.
2. `VoiceCommandParser` = **pehle rule-based multilingual keyword matcher** (fast, offline,
   haan/nahi reuse). Complex lead baatein (add/update/bulk) **AI chat ke LEAD_* flow pe
   delegate** kar do — wo already ready hai (Bolo mode wahi karta hai).
3. `VoiceConfirmGate` = pure Kotlin state machine, **koi Android dependency nahi** → deterministic
   test ke liye ready (jaise pichhla speakToken test).
4. Har destructive action `suspend fun` ke andar guard:
   `ConfirmGate.require(CONFIRM_LEVEL, kind)` — agar pass nahi, **block**.

---

## 3. New files to create

| File | Package | Role |
|---|---|---|
| `VoiceCommand.kt` | `com.example.voice` | `sealed class VoiceCommand` (NAVIGATE, SEARCH, ADD_LEAD, UPDATE_LEAD, STATUS_LEAD, DELETE_LEAD, BULK, BACKUP, RESTORE, DELETE_CLOUD, DELETE_LOCAL, SET_LANGUAGE, SET_VOICE, SET_BOLO, ASK_AI, BACK, HELP, YES, NO, UNKNOWN) + payload fields |
| `VoiceCommandParser.kt` | `com.example.voice` | Rule-based multilingual matcher (HI/TA/UR/EN keywords) + `parse(text): VoiceCommand`; complex phrases → `ASK_AI` (delegate to AI chat) |
| `VoiceConfirmGate.kt` | `com.example.voice` | Levels (SINGLE=1, DOUBLE=2, TRIPLE=3); tracks state per action kind; **third step requires exact phrase** `haan delete karo` / `yes delete`; any `nahi` = cancel + reset |
| `VoiceAppController.kt` | `com.example.voice` | Object. `startListening()`, `handleText(text)`, `runLoop()`, `bargeIn()`, `shutdown()`; wires mic→TTS→actions |
| `VoiceNavigator.kt` | `com.example.voice` | Wraps `NavController` + tab switch + back; exposes `VoiceNavSink` interface so it's testable without Android |
| `VoiceFeedback.kt` | `com.example.voice` | `announce(context, text)` (AiVoicePlayer) + contextual announcements ("Leads screen khul gaya, 5 pending hain") |
| `VoiceAnnounce.kt` (screen enter) | `com.example.voice` | Map route → announcement string + data lookup (pending count etc.) |

## 4. Files to edit

| File | Change |
|---|---|
| `MainActivity.kt` | Hook `VoiceAppController`: global mic entry (FAB on main tabs), pass `VoiceNavigator` around NavHost, announce on route change (`currentBackStackEntry`), wire back command |
| `SettingsTab.kt` | Backup/restore/delete buttons ke behind reusable `suspend` paths (ya callbacks) jo `VoiceAppController` bhi call kar sake + confirm dialogs → voice gate se chalein |
| `SettingsApiKeysScreen.kt` | (koi voice entry NAHI — API keys sirf type; bas TTS announce "ye sirf type karke hota hai") |
| `CRMViewModel.kt` | Expose: pending count (announce ke liye, shayad already hai report map), language set wrapper, aur `deleteCloudBackup` ko gate-guard call ke saath | 
| `strings.xml` (×4) + `AppStrings.kt` | Naye voice command + confirmation + feedback strings |

---

## 5. Voice command map (phrase → action → confirm level)

| Voice kahe (HI/TA/UR/EN mix OK) | Command | Action | Confirm |
|---|---|---|---|
| "Leads dikhao" / "clients dikhao" | NAVIGATE | tab `leads` + TTS count | — |
| "Dashboard dikhao" | NAVIGATE | tab `dashboard` | — |
| "History dikhao" / "purani chat" | NAVIGATE | AI history (AIScreen side panel / chat) | — |
| "Settings kholo" | NAVIGATE | tab `settings` | — |
| "Wapas jao" / "back" | BACK | `navController.popBackStack()` | — |
| "Naya lead banao ..." | ADD_LEAD | AI chat flow (one-question-at-a-time, voice) | card 1× haan |
| "Ramesh dhoondo" / "pending dikhao" | SEARCH | LeadsTab filter + TTS list | — |
| "Ramesh ka number badlo ..." | UPDATE_LEAD | `LEAD_UPDATE` card | 1× haan |
| "Ramesh complete karo" | STATUS_LEAD | `LEAD_STATUS` card | 1× haan |
| "Ramesh delete karo" (local/soft) | DELETE_LEAD | `LEAD_ARCHIVE` (soft) | 1× haan |
| "Jo 15 din se pending ... reminder kal" | BULK | `LEAD_BULK` card (list padho) | 1× haan |
| "Backup karo" | BACKUP | `backupAllToCloud` | 1× haan |
| "Restore karo" | RESTORE | `manualRestoreFromCloud` | 1× haan |
| "Local data delete karo" | DELETE_LOCAL | `resetAllData` | **2× haan** |
| "Cloud backup delete karo" | DELETE_CLOUD | `deleteCloudBackup` | **3× TRIPLE** |
| "Bhasha Hindi karo" | SET_LANGUAGE | `AppLanguageManager.setLanguage` | 1× haan + TTS usi bhasha me |
| "Kore awaz lagao" | SET_VOICE | `AIQuotaManager.setTtsVoiceName` + testSpeak | 1× haan |
| "Bolo mode on karo" | SET_BOLO | `AIQuotaManager.setBoloModeEnabled(true)` | — |
| "AI se pucho ..." | ASK_AI | AI chat (existing) | card ke hisaab se |

---

## 6. Confirmation gate (3 levels) — exact rules

| Level | Kab | Flow |
|---|---|---|
| **1× SINGLE** | navigation, search, backup, status change | TTS "… kar du? haan ya nahi bolo" → `haan` = done |
| **2× DOUBLE** | local delete, restore, bulk archive | 1) "…kar du?" → haan  2) "Pakka? fir se haan bolo" → haan |
| **3× TRIPLE** | **Cloud backup delete ONLY** | 1) "Cloud backup hamesha ke liye delete kar du?" → haan  2) "Ye wapas nahi aayega, pakka haan?" → haan  3) "Aakhri baar — 'haan delete karo' bolo" → **exact `haan delete karo` / `yes delete`** |

- Kisi bhi step pe `nahi` (any language) = **cancel + gate reset**.
- Gate state per action kind; retry fresh se shuru.
- `VoiceConfirmGate` = pure Kotlin + deterministic (test-friendly).

---

## 7. Voice flow loop (listen → parse → act → speak)

```
1. mic dabao / Bolo wake  → AiVoicePlayer.stop()  (barge-in: turant chup)
2. VoiceInputHelper.start() → STT text
3. VoiceWordMatcher: haan/nahi? → gate ko feed (nahi → reset; haan → next step)
4. Nahi to VoiceCommandParser.parse(text)
5. Command → VoiceNavigator / CRMViewModel action
6. VoiceFeedback.announce(result)  (TTS, app language)
7. 2-second hear-window (user haan/nahi ya agla command bole) → wapas step 2/1
```

**Barge-in:** "ruko", "chup", "stop", ya mic tap → `AiVoicePlayer.stop()` + `VoiceInputHelper.cancel()` (channel flush). `speakSuspend` ka `speakToken` already stale-speech safe hai (fix `e5fc443`).

---

## 8. Hard safety rules (kabhi nahi todenge)

1. **Cloud delete bina triple ke KABHI nahi** — guard `VoiceConfirmGate` ke andar;
   AI chat se bhi cloud delete ko trigger karne ka **koi path nahi** hoga (koi LEAD_* block → DELETE_CLOUD map nahi).
2. **API keys kabhi voice se nahi** — `SettingsApiKeysScreen` voice-command map me hi nahi.
3. **Paise wala kaam nahi** — sab free (Gemini free tier + device TTS fallback).
4. **Galat suna?** → confirm step bolta hai *"Maine 'Ramesh' suna, sahi hai? haan ya nahi"* — `nahi` = dobara.
5. Unknown phrase = **normal chat** (jaise `VoiceWordMatcher` — chat me bhej do), action assume mat karo.

---

## 9. i18n additions (EN/HI/TA/UR — sab 4 locales)

- Navigation announcements ("Leads screen khul gaya…")
- Confirmation prompts (3 levels + exact phrase)
- Feedback strings ("backup ho gaya", "delete ho gaya", "maine 'X' suna...")
- Barge-in / help command list ("kya-kya bol sakte ho")

Add in `res/values/strings.xml` + `values-hi/ta/ur`, register in `AppStrings.STRING_RESOURCE_MAP`.

---

## 10. Test plan (deterministic — same toolchain as `e5fc443` verify)

Pure Kotlin (no Android) — compile + run with the `kotlin-jupyter-kernel` Kotlin compiler in sandbox:
1. `VoiceCommandParserTest` — har language ke phrases → sahi `VoiceCommand`; unknown → ASK_AI.
2. `VoiceConfirmGateTest` — single/double/triple states; `nahi` reset; **triple sirf exact
   `"haan delete karo"` / `"yes delete"` se pass** (dusra wording fail).
3. `VoiceWordMatcherTest` (extend) — haan/nahi multilingual + negation wins.
4. Loop cancellation test — `isActive` + pending clear + barge-in (jaise speakToken test).
5. Compile real files under android stubs (jaise pichli baar) — `VoiceAppController` confirm gate guard compile-check.

---

## 11. Milestones (order — safe core pehle, destructive last)

| M | Kya | Done when |
|---|---|---|
| **M1** | VoiceCommand + Parser + ConfirmGate (+ tests) — pure Kotlin | ✅ DONE — `com.example.voice` (4 files) + 2 test classes; 32/32 deterministic tests green |
| **M2** | VoiceAppController + VoiceNavigator + feedback + screen announce; global mic entry on main tabs | ✅ DONE (code) — pure core `VoiceLoop`/`VoiceEffect`/`VoiceTexts`/`VoiceNavigator`/`VoiceCapabilities` + Android glue `VoiceAppController`/`AndroidVoiceTexts`/`VoiceMicButton` + mic FAB on main tabs + 21 `vc_*` strings × 4 locales; 67/67 tests green. Device check pending: "leads dikhao" → tab badle + TTS announce |
| **M3** | Leads CRUD voice (reuse AI LEAD_* flow) | add/search/update/status/delete/bulk sab voice se, confirm cards voice se |
| **M4** | Backup/restore/local-delete voice + **triple cloud delete gate** | backup/restore 1×; local 2×; cloud 3× exact phrase; `nahi` cancel |
| **M5** | Settings voice: language / AI voice / Bolo | "bhasha hindi karo" → TTS hi-IN me hi jawab de |
| **M6** | i18n strings + TTS polish + full test pass + `AI_Status_Baseline_v3.md` update | sab tests green + doc update commit |

Rollout rationale: M1–M2 = safe foundation; M3 = max reuse (already ready); M4 = sabse dangerous last me + gate pehle se ready.

---

## 12. Risks / open questions

1. **"Sun raha hun" wake-word** — continuously listening = battery. Prakri: **in-app Bolo mode** me hoga; warna **mic button** prime entry. (thermal/battery yaad rakhna — spec constraints.)
2. **Cloud delete exact phrase** — third step ke liye "haan delete karo" + "yes delete" dono accept; baaki sab fail.
3. **Global mic entry** — kaunsa form (floating FAB har tab pe vs Bolo mode se)? Default: **floating mic FAB on all main tabs** (anpadh ke liye sabse aasaan).
4. **Settings destructive** — SettingsTab ke dialogs ko voice gate se sync karna (state single-source: ConfirmGate).

---

## 13. How to use this file (next step)

User bolega **"shuru karo"** → main is plan ke M1 se shuru karunga, har milestone pe
deterministic test + compile + commit (PR merge **nahi** — sirf exact "bhaiya ji pr merged karo" pe).

*Version: 1.0 — for LifeFresh QuickNote Pro (`com.lifefreshcrm.pro.inkgql`), branch `arena/01a0d87f-life-fresh`.*
