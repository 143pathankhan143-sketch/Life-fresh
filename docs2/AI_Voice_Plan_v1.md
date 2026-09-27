# AI Voice Set — Simplification Plan (v1)

**Date:** 2026-09-27 (rev 2)
**Status:** PLAN ONLY — koi code change nahi. Approval ke baad implement karenge.

## 0. User decisions (2026-09-27) — LOCKED

| Option | Decision |
|---|---|
| **A** — Android ki "best installed / network" voice | ✅ **KARNA HAI** (isi kaam me) |
| **B** — Groq Orpheus TTS (Groq key wale users ke liye) | ✅ **ADD KARNA HAI** (pehle free-tier + Hindi/Tamil verify) |
| **C** — Offline neural TTS (Piper) | ✅ **DOWNLOADABLE karenge** — APK me installed nahi, user chahe to download kare |
| **D** — Unofficial / scraper TTS endpoints | ❌ **KABHI NAHI** |
**User directive:** "Saari awaazen hata do — sirf aur sirf Android ki built-in voice aur
Gemini ki 2 voice (1 male + 1 female)."

---

## 1. Aaj kya hai vs kal kya hoga

| | Aaj (current) | Plan ke baad |
|---|---|---|
| Cloud (Gemini) voices | **30** (Auto + 29 named) | **2** — 1 female + 1 male |
| Offline voice | Phone ki built-in TTS, 4 language (hi/ta/ur/en) | Wahi 4 — koi change nahi, sirf "best installed voice" chunne ka improvement |
| Voice picker UI | 30 rows ki lambi list (illiterate user ke liye confuse) | 2 badi rows: "Mahila" / "Purush" + dono ke saath **Test** button |
| TTS models | 3 listed, sirf 2 try hote hain, ek dead | 2 — dono **live** aur **free tier par free** |
| Voice command se voice badalna | Toota hua (`knownVoices` khaali jaata hai → sirf picker khulta hai) | "male/female awaz lagao" seedha voice set kare (4 bhasha me) |

Kyun ye theek hai: 28 voices hataane se **koi feature nahi tootta** — inhe sirf Settings
picker use karta hai. Baaki poora voice system (Bolo mode, barge-in, TTS fallback)
waise hi chalta rahega.

---

## 2. Final voice set (jo user dekhega) — total 6 choices

**A. Gemini natural voice (Internet + Gemini key ho to) — 2**

| UI naam | Voice id | Kyun chuni |
|---|---|---|
| **Mahila** (female) | `Kore` | App ka aaj ka stable default; "Firm" clear tone; Hindi/Tamil/Urdu/English sab me saaf uchchaaran. Purani saved setting se bhi match karti hai. |
| **Purush** (male) | `Orus` | Kore ka male counterpart ("Firm"); Google ke official examples me use hota hai; heavy/serious agent tone — lead ka data padhne ke liye sahi. **Bonus:** ye voice aaj app ki list me hi missing hai. |

*Backup alternates (agar aap sun ke pasand na karein):* Mahila → `Sulafat` (Warm),
`Callirrhoe` (Easy-going) · Purush → `Charon` (Informative), `Puck` (Upbeat).
Settings me Test button se dono sun kar ek line badal ke swap kar sakte hain.

> Gender confirm karne ka tareeka (implementation step): `GET /v1beta/voices?gender=female|male`
> se verify karenge, aur aap device par Test sun kar final karenge.

**B. Android built-in voice (offline, key ki zaroorat nahi) — 4**
App ki bhasha ke hisaab se apne aap: **Hindi (hi-IN) · Tamil (ta-IN) · Urdu (ur-PK) · English (en-IN)**.
Ye fallback tab chalti hai jab Gemini key na ho, internet na ho, ya cloud fail ho.

**Total: 2 cloud + 4 offline. Bas. Aur kuchh nahi.**

---

## 3. TTS models bhi theek karenge (voice se alag cheez hai)

Aaj: `gemini-3.8-flash-tts` → `gemini-3.1-flash-tts-preview` → `gemini-2.5-flash-preview-tts`
(code `.take(2)` karta hai, teesra kabhi try nahi hota; 2.5 family bhi naye projects ke liye band hai).

Plan: `gemini-3.8-flash-lite-tts` → `gemini-3.8-flash-tts`
- Dono **free tier par free** (pricing page se verified 2026-09-27: 3.8 Flash TTS $9/1M audio,
  Lite $6/1M, 3.1 preview $20/1M — free tier "Free of charge").
- Lite pehle = kam latency (voice agent ke liye zaroori), bhaari model backup.
- `.take(2)` hack hat jayega, kyunki ab waqai 2 hi models hain.
- **Verify karna hoga:** hamara client `:generateContent` endpoint use karta hai; 3.8 TTS
  dono API (Interactions + generateContent) support karta hai per docs — pehle device/curl
  test se confirm, warna wahi 3.1-preview fallback me rakhenge.

---

## 4. Aapka sawaal: "Gemini key na ho to koi dusri natural voice ho sakti hai?"

Haan, 3 raaste hain. Meri sifarish: **Option A abhi, baaki baad me.**

**Option A — Android ki "network voice" (sifarish: yahi karo) ⭐**
Wahi `TextToSpeech` API, par default kharab voice ke bajaye hum device me available
**best voice** chunte hain: `tts.voices` me se apni bhasha ka wo voice jo `quality = HIGHEST`
aur network-based ho (Google "Speech Services" ki network voices Hindi/Tamil me kaafi natural
lagti hain). Key nahi chahiye, extra paisa nahi, offline fallback bhi rehta hai.
Kaam: ~40 line. Nuksan: device par network voice hona zaroori (kuch phones me sirf local) —
isliye local voice pe automatic fallback rahega.

**Option B — Groq Orpheus TTS (jo user Groq key rakhta hai, uske liye)**
Groq par `orpheus` TTS preview me listed hai. Groq key wale user ko Gemini ke bina bhi
natural voice milegi. **Pehle verify karna hoga:** free tier me available hai ya nahi,
aur Hindi/Tamil/Urdu support hai ya nahi. Agar haan → M6 ke baad add kar sakte hain.

**Option C — Offline neural TTS, DOWNLOADABLE (decision: yahi karenge) ⭐**
APK me model nahi rahega (app size same). Settings me ek screen: "Offline voice download".
User apni bhasha ka pack (Piper/HF-ONNX) khud download karega → phone ke files dir me
rakhega → uske baad **bina internet bilkul natural awaaz**.
- Size: ~30–60 MB per bhasha (hi/ta/ur/en), sirf jo download kare wahi.
- Download tab hi dikhega jab user "Natural voice (offline)" chune; warna Android voice chalegi.
- Rules: free + open model only (Piper/Mimic jaise), resumable download, wifi-only warning,
  checksum verify, delete option, aur **Gemini/Groq key + internet ka fallback hamesha
  pehle** rakhenge (offline pack sirf ek extra option, default nahi).
- Kaam bada hai (naya offline TTS engine ~600–900 line + model management)
  → **M6 ke baad, alag milestone**.

**Option D — Unofficial endpoints (translate_tts type) — ❌ KABHI NAHI** (user decision)
ToS/blocked hone ka risk, kabhi bhi band ho sakta hai. App me aa hi nahi sakta.

---

## 5. Kya-kya badlega (file-by-file)

| File | Change |
|---|---|
| `ai/chat/voice/GeminiTtsClient.kt` | `VOICES` = `["Kore","Orus"]`; "auto" hatao; har voice ka label ("Mahila"/"Purush"); `stableVoice()` me **migration guard**: purani saved voice (Charon etc.) ho to Kore par fallback — warna purane user ki setting crash/khaali awaaz degi. |
| `ai/chat/config/AIConfig.kt` | `GEMINI_TTS_MODELS` = `["gemini-3.8-flash-lite-tts","gemini-3.8-flash-tts"]` (naya KDoc: 2.5/3.1 preview band, 2.5 naye projects ke liye band). |
| `ai/chat/voice/GeminiTtsClient.kt` | `.take(2)` hack clean. |
| `data/security/AIQuotaManager.kt` | `getTtsVoiceName` default "Kore" hi rahega; comment update ("auto" gaya). |
| `ui/screens/settings/SettingsApiKeysScreen.kt` | 30-row list → 2 badi rows (radio + **Test** button per row, `testSpeak` already maujood). |
| `voice/VoiceCommandParser.kt` | Male/female keywords har bhasha me: `male, purush, ladka, mard, aadmi, आदमी, पुरुष, लड़का, ஆண், مرد` → `Orus`; `female, mahila, ladki, aurat, औरत, महिला, लड़की, பெண், عورت` → `Kore`; voice-name words (`kore`, `orus`) bhi chalein. |
| `MainActivity.kt` | **Bug fix:** `knownVoices = { GeminiTtsClient.VOICES.toSet() }` wire karna (aaj khaali jaata hai → `SetVoice` kabhi specific voice set nahi karta). |
| `data/AppStrings.kt` + `values{,-hi,-ta,-ur}/strings.xml` | Naye strings: `ai_voice_female`, `ai_voice_male`, `ai_voice_pick_sub`, purana `ai_voice_auto` hatao. **R8 guard test** (`everyVoiceKeyIsListedInTheAppStringsMap`) update — static entries add karna zaroori. |
| Tests | `VoiceCommandParserTest`: purani 7-voice set → 2 voice; naye cases (male/female × 4 bhasha, purani voice name ka behaviour). Naya `GeminiTtsConfigTest`: `VOICES.size == 2`, labels maujood, TTS model list me koi 2.x / preview-only id nahi. |
| Docs | `AI_Configuration_Engine.md` (voice section), test plan me Section I-3, `work.md` milestone note. |

**Purani voice commands ka behaviour (back-compat):** "Charon awaz lagao" bole to ab
picker khulega (voice mili nahi) — crash nahi. Ye jaan-boojh kar: 28 naam yaad rakhne ka
koi faayda nahi, aur galat voice set karne se bura hai picker kholna.

---

## 6. Steps (chhote-chhote, har step testable)

1. **AIConfig TTS models** + `.take(2)` cleanup → sandbox test.
2. **VOICES → 2 + labels + migration guard** → test (`VOICES.size == 2`, purani value → Kore).
3. **Settings picker** 2 rows + Test button (hi/ta/ur/en strings).
4. **Voice command**: `knownVoices` wiring + male/female keywords + strings + R8 map + tests.
5. **Option A (Android best installed voice)** — chhota bonus: `setLanguage` ke baad best
   voice select, warna local fallback.
6. **Device checklist** (aapko chahiye): Settings me dono voice Test → suno · "male awaz
   lagao" → Purush ho jaye + confirm bole · "female awaz lagao" → Mahila · Gemini key hata ke
   dekho → Android voice chale, koi error nahi · Bolo mode me jawab nayi voice me aaye.

Har step ke baad: sandbox tests green → commit. Aakhir me ek hi clean commit series.

---

## 7. Risk + jo hum NAHI karenge

- **Risk:** Android ke network voice kuch device par nahi hote → automatic local fallback
  (aaj jaisa hi). Urdu me Android voice quality kamzor ho sakti hai → wahan Gemini voice
  hi asli option hai (isliye cloud fallback kabhi band nahi karenge).
- **Nahi karenge:** unofficial TTS endpoints, paid TTS, APK me 200MB voice models,
  cross-language voice (Hindi text Tamil voice me padhana).

## 8. Aapke liye 3 open decisions

1. **Male voice:** `Orus` final rakhun? (ya `Charon`/`Puck` sun ke badalna hai — Test button se sun sakte hain)
2. **Female voice:** `Kore` theek hai? (ya `Sulafat` — zyada "warm/garam" awaaz)
3. **Option A (Android best/network voice)** isi kaam me karna hai ya M6 ke baad? (meri raay: isi me, 40 line ka kaam hai)

Jawab do → phir code shuru.

---

## 9. 🐞 P0 BUG — "Android voice sirf pehle 2 jawab bolta hai, uske baad sirf text"

**User report (2026-09-27, device test, Groq key only + Gemini key removed):**
"har chat ke andar do hi sawal ke jawab bolkar aate hain, baki sab ka sirf text reply aata hai."
Yaani: pehle 1–2 jawab sunai dete hain, uske baad awaaz aana **hamesha ke liye band** — jabki
AI chat normal chalti rehti hai (text aata rehta hai).

### 9.1 Kyun hota hai — code me jo cheezein PAKKI galat hain

1. **`AiTts.speakNow()` `TextToSpeech.speak()` ka return value dekhta hi nahi hai**
   (`app/src/main/java/com/example/ai/chat/voice/AiTts.kt` line ~139).
   Android is call se `ERROR` (-1) lauta sakta hai jab engine soya hua / busy / bad-state ho.
   Return `ERROR` hone par **koi callback nahi aata** — na `onStart`, na `onDone`, na `onError`.
   Hamara safety-timer chup-chaap `fireDone()` kar deta hai, app samajhti hai "bol diya",
   aur user ko kuchh sunai nahi deta. **Ye exactly "awaaz band ho gayi, app chalti rehti hai"
   wala symptom hai.**

2. **Engine ke marne par koi recovery nahi** — `engine != null` ek baar set hone ke baad
   `ensure()` dobara kabhi engine nahi banata; `ready` kabhi `false` nahi hota;
   `onServiceDisconnected` / re-init / retry kahin nahi. Ek baar engine chup → **poori
   app-session ke liye chup** (app restart par hi theek). `AiTts` me `shutdown()/release()`
   bhi nahi hai, yaani engine dobara banane ka koi rasta hi nahi.

3. **Har utterance se pehle `stop()` + `setLanguage()`** — `AiVoicePlayer.beginSpeak()` har naye
   speech par `AiTts.stop()` (yaani `engine.stop()`) call karta hai, aur `applyVoicePrefs()`
   har utterance se pehle `setLanguage()`. Kai engines (Google/Samsung TTS) me
   **`stop()` ke turant baad `speak()` ka utterance drop** ho jaata hai — classic
   "pehle 1–2 baar bolta hai, phir chup" behaviour. Note: Gemini path isse bach jaata hai
   kyunki wo `MediaPlayer` use karta hai, TextToSpeech nahi — isiliye user ko farq dikha:
   "jab Android ki voice aati hai tab hi problem".

4. **Poora reply ek hi `speak()` me** (device path `splitForTts()` nahi karta — wo sirf cloud
   path me lagta hai). Engine ki max input limit (`getMaxSpeechInputLength()`) se bada text
   bhi bina error-check ke chala jaata hai; lamba jawab aa jaye to wahin se silence.

5. **`onStart` track hi nahi hota** (`override fun onStart(...) = Unit`) — isliye hum ye bhi
   nahi jaan sakte ki "shuru bhi hua tha ya nahi". Half-broken engine ko detect karne ka
   koi zariya nahi.

### 9.2 Dusra (alag) reason jo same dikhta hai — Bolo mode band + speaker off

`AIScreen` me auto-speak sirf tab chalta hai jab `voiceReplyOn == true` aur `boloModeOn == false`.
Bolo loop 3 baar kuchh na sunne par **khud ko band kar deta hai** (`micFailures >= 3` →
`boloModeOn = false`) aur sirf ek Toast dikhata hai (anpadh user ke liye kuchh nahi).
Uske baad `voiceReplyOn` default **false** hota hai → **har aage ka jawab chup-chaap text**
ban jaata hai. Ye "do jawab ke baad chup" ka dusra pura reason hai.

### 9.3 Fix plan (P0 — voice-set kaam se pehle)

1. `speak()` ka return code check karo: `if (tts.speak(...) == TextToSpeech.ERROR)` →
   engine ko teardown + naya engine + wahi text dobara bol (1 retry).
2. `AiTts` me **watchdog**: `speak()` ke 2.5s ke andar `onStart` na aaye → engine dead maano →
   re-init + retry. `onDone/onError` na aaye (estimate + margin) → wahi treatment.
3. `AiVoicePlayer.beginSpeak()` me `AiTts.stop()` sirf tab karo jab waqai kuchh bol raha ho
   (`engine.isSpeaking == true` ya apna `speaking` flag); normal turn par bharosa
   `QUEUE_FLUSH` par. Real barge-in par stop ke baad ~80–120ms ka gap.
4. Device path ko bhi `splitForTts()` + `getMaxSpeechInputLength()` se chunk karo, aur har
   utterance ka **unique utteranceId** do.
5. `setOnServiceDisconnectedListener` (API 30+, minSdk 24 hai to version guard) + engine
   list check → disconnect par re-init.
6. Har failure par **Logcat me saaf log** (`AiTts: speak() ERROR -1 → reinit`) — agli device
   test me pakka pata chal jayega ki kaunsi wajah thi.
7. Bolo/voice-reply trap fix: bolo loop band hone par **pehle "main sun nahi paya, mic dobara
   dabao" bolo** (TTS se, Toast ke saath), aur auto-speak ko `boloModeOn` ke bajaye
   "loop waqai chal raha hai" state se gate karo — taaki loop rukne par reading chalti rahe.
8. Aakhir me (M6) — Option A/B/C ke saath ek `VoiceHealth` check: Settings → Test button har
   baar engine ki sehat bhi test kare.

### 9.4 30-second device test (user ke liye — abhi, code change se pehle)

1. Bug dobara lao (2–3 jawab ke baad chup ho jaye).
2. Usi waqt **Settings → AI voice → Test** dabao:
   - **Test bhi chup** → device TTS engine mar chuka hai (point 9.1) → fix 1–6.
   - **Test bolta hai** → app ne bolna band kiya (point 9.2) → fix 7.
3. App band karke dobara kholo: pehle 2 jawab phir bolne lagte hain? → haan = engine-state
   wala bug (9.1) confirm.
4. (Optional) Logcat: `adb logcat -s AiTts AiVoicePlayer VoiceAppController` — agli build me
   failure ka asli reason wahan likha milega.
