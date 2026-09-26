# AI COMPLETE TEST PLAN — LifeFresh QuickNote Pro

**Branch:** `arena/01a0d87f-life-fresh` · **Date:** 26 September 2026
**Purpose:** "AI complete" maan-ne se pehle har AI feature ka ek-ek test.
Har test me: **Kaise karna hai → Kya hona chahiye**.
**P0** = iske bina AI complete nahi · **P1** = zaroori · **P2** = achha ho agar pass ho
**⚙️ AUTO** = ye already automated test me cover hai (Neeche section N dekho)

> Test karne ka tarika: ek-ek item karo, pass/fail likho. Fail mile to item number batana —
> main wahi fix karunga. Sections A–L manual hain (phone pe), section N automated hain.

---

## SECTION A — Setup & API Keys (bina iske kuch test nahi hoga)

**1. Free tier pe chat chalti hai** [P0]
Bina koi key daale AI tab kholo → sawaal poocho → jawab aana chahiye (built-in free quota).

**2. Settings → AI API Keys screen khulti hai** [P0]
Settings → AI API Keys → Gemini key field, Groq key field, OpenRouter key field, Tavily key field, "Daily quota" row — sab dikhne chahiye.

**3. Own Gemini key daalna (BYOK)** [P0]
Gemini key paste karke Save → "Saved" message → key masked dikhe (poori key screen pe na dikhe).

**4. Key Test (connection check)** [P0]
Gemini key field ke paas Test button → sahi key = "Connected/OK" jaisa message; galat key = error message (crash nahi).

**5. Galat key se chat na toote** [P0]
Jaan-boojh kar galat Gemini key save karo → chat me sawaal → friendly error + Retry button aana chahiye, app crash nahi.

**6. Key hataana (Remove/Clear)** [P1]
Key delete karke save → free tier wapas chalu ho jaye (chat phir bhi chale).

**7. Daily quota dikhna chahiye** [P0]
Settings → Daily quota row me aaj ka usage count dikhe (jaise "5/20").

**8. Custom key = unlimited** [P0]
Apni Gemini key daalne ke baad quota limit lagi hui na dikhe (unlimited ho jaye).

**9. AI master flag** [P2]
AI tab bottom nav me dikhta hai aur kholte hi chat load hoti hai (flag on hai).

**10. Agent mode switch** [P2]
Settings me "Agent mode" switch dikhe; ise chalu/band karne se app crash na ho aur chat normally chale.

---

## SECTION B — Chat Core (asli chatbot)

**11. Message bhejna** [P0]
Type karke send → aapka message turant dikhe → "Thinking…" → AI ka jawab aaye.

**12. Streaming (jawab dheere-dheere aata hai)** [P0]
Lambe jawab me text ek saath nahi, chalta hua (stream) aana chahiye — blank screen nahi.

**13. Enter/Send button behaviour** [P1]
Khaali message pe send disabled ho; sirf spaces bhejne pe kuch na ho.

**14. Reply ke baad input clear** [P1]
Jawab aane ke baad textbox khaali ho jaye aur keyboard band ho jaye.

**15. Retry button** [P0]
Jawab fail ho to "Retry" dikhe → tap karo → wahi sawaal dobara chale aur jawab aaye.

**16. Naya sawaal jab purana chal raha ho** [P1]
Jab AI soch raha ho, naya message bhejo → app na atke, purana generate cancel ho, naya jawab aaye.

**17. Suggestion chips (pehli screen)** [P1]
Khaali chat pe suggestions dikhein (jaise "Show me pending client reminders") → tap → wahi sawaal chale.

**18. New Chat** [P0]
Header → New Chat → chat khaali ho jaye, naya session bane; purani chat history me bachi rahe (delete na ho).

**19. Clear Chat** [P1]
Clear Chat → current chat ke messages hatein, crash na ho.

**20. Chat rotate (screen ghumana)** [P0]
Jawab aane ke beech phone ghumaao → chat aur jawab waise hi rahein (na dubara bheje, na gayab).

**21. Chat se bahar jaake wapas aana** [P0]
Doosre tab pe jaake wapas AI tab → poori chat wahi dikhe.

**22. App band karke kholna (process death)** [P0]
App ko recent se hata do → dobara kholo → AI tab → chat + jawab wahi mile (Room se restore).

**23. Logout/login pe chat alag** [P0]
Alag account me login → apni purani chat na dikhe (per-user isolation).

**24. Guest mode me chat** [P1]
Bina login (guest) chat chalti ho; later login karne pe guest chat na leak kare.

**25. Message copy** [P2]
AI jawab ko long-press/copy karo → text clipboard me aaye.

**26. Emoji + long text** [P2]
Emoji aur 1000+ letters ka message bhejo → na toote, na crash.

---

## SECTION C — Chat History (list/rename/pin/archive/delete)

**27. History panel khule** [P0]
AI header → History icon → side panel khule purani chats ki list ke saath.

**28. Purani chat kholna** [P0]
List me kisi chat pe tap → wahi conversation poore messages ke saath khul jaye.

**29. Pin / Unpin** [P1]
Chat pe ⋮ → Pin → wo chat list me upar aa jaye; Unpin se wapas normal.

**30. Rename** [P1]
⋮ → Rename → naya naam save → list me naya naam dikhe.

**31. Archive / Unarchive** [P1]
⋮ → Archive → wo chat "ARCHIVED" section me chali jaye; Unarchive se wapas.

**32. Delete chat** [P0]
⋮ → Delete → confirm dialog ("Delete this chat?") → Delete → chat list se hat jaye (permanently).

**33. Delete cancel** [P1]
Confirm dialog me Cancel → chat na hate.

**34. Khaali history** [P1]
Naye account pe history kholo → "No past chats yet…" message aaye.

**35. Date grouping** [P1]
Chats Today / Yesterday / purani — sahi group me dikhein.

**36. Sessions order** [P1]
Sabse nayi baat-cheet sabse upar ho; pinned uske bhi upar.

**37. History se chat kholne pe wahi model context** [P2]
Purani chat kholke naya sawaal poocho → AI ko pichle messages ka context ho.

**38. History panel band karna** [P2]
Panel ke bahar tap / close → panel band ho, chat waisi rahe.

---

## SECTION D — Lead Actions (AI se lead ka kaam) — har ek alag test

**39. Naya lead banana (poori detail)** [P0]
"Do baat: naam Ramesh, mobile 98765xxxxx, diabetes, call kal 10 baje" jaisa bolo → AI sab details dohraye → **Save Lead** card aaye.

**40. Save Lead card → Save** [P0]
Card pe Save Lead → lead asli Leads list me dikhe, sahi naam/number/reminder ke saath.

**41. Save Lead card → "Draft me rakho"** [P1]
Draft me rakho → lead Drafts section me jaye, active list me na aaye.

**42. Card dismiss** [P1]
Card pe Dismiss/✕ → lead save NA ho.

**43. Adhoori detail pe draft** [P0]
Sirf "Ramesh ka number 98…" jaisa aadha bolo → AI pooche ya draft banaye; galat full lead save na ho.

**44. Duplicate mobile rok** [P0]
Wahi mobile number dobara add karne ki koshish → app rok de / warn kare (duplicate lead na bane).

**45. Status change: Pending → Complete** [P0]
"Ramesh complete karo" → confirm card (naya status: Complete) → tap → lead ka status badal jaye.

**46. Status change: Complete → Pending** [P0]
"Ramesh pending karo" → card → tap → wapas Pending ho jaye.

**47. Status card cancel** [P1]
Card pe cancel → status na badle.

**48. Lead update (details badalna)** [P0]
"Ramesh ka number 99999xxxxx kar do" → card me "Naya number: …" dikhe → confirm → lead me naya number save ho.

**49. Update: reminder badalna** [P0]
"Ramesh ka reminder kal 5 baje kar do" → card me "Naya reminder: …" → confirm → reminder update ho.

**50. Update: note add** [P1]
"Ramesh ko note lagao — delivery kal" → card "Note add: …" → confirm → note lead me dikhe.

**51. Update: reminder hataana** [P1]
"Ramesh ka reminder hata do" → card "Reminder: hata dena" → confirm → reminder clear ho.

**52. Archive (soft delete)** [P0]
"Ramesh archive karo" → card ke bina direct ho jaye → lead active list se hatte, Archived filter me mile (data delete na ho).

**53. Delete (permanent)** [P0]
Archived lead pe "Ramesh delete karo" → light confirm card ("Yeh lead hamesha ke liye delete ho jayega…") → confirm → lead permanently delete.

**54. Delete card cancel** [P0]
Delete card cancel → lead bacha rahe.

**55. Non-archived lead delete pe safety** [P0]
Active (non-archived) lead ko seedha delete karne pe app pehle archive/rok de — bina confirm data na jaye.

**56. WhatsApp open** [P1]
"Ramesh ko WhatsApp karo" → WhatsApp app us lead ke number ke saath khule (chat me card na aaye).

**57. Bulk — pending sab** [P0]
"Saare pending leads complete karo" → card khule jo bataye kitne leads change honge → Apply → reply me bataye kaun-kaun badla.

**58. Bulk limits + cancel** [P0]
Bulk me max 25 leads (hint dikhe); card cancel karne pe kuch na badle.

**59. Galat naam pe AI poochhe** [P0]
"Sharma ji complete karo" jab aisa lead na ho → AI saaf bole ki lead nahi mila; galat lead na chune.

**60. Lead action ke bina normal sawaal** [P1]
"Kitne pending hain?" (action nahi, sawaal) → card na aaye, seedha jawab aaye.

---

## SECTION E — AI Voice Reply (jawab bol kar sunana)

**61. Voice reply toggle ON** [P0]
AI header me "Speak replies aloud" 🔈 ON → sawaal poocho → AI jawab bol kar bhi sune.

**62. Voice reply OFF** [P0]
Toggle OFF → jawab sirf text me, koi awaaz nahi.

**63. Awaaz ki bhasha sahi ho** [P0]
App language Hindi → jawab Hindi me bole; Tamil → Tamil; Urdu → Urdu (English accent me na pade).

**64. TTS pack na hone pe message** [P1]
Jis phone me us bhasha ki voice data nahi, wahan "voice data install karo" jaisa saaf message aaye (gibberish na bole).

**65. Natural voice (Gemini TTS) ON** [P0]
Settings → AI voice ON + "Choose voice" se ek voice (jaise Kore) → jawab natural awaaz me aaye.

**66. Voice picker** [P1]
"Choose voice" → list (Auto, Kore, Charon, Puck, …) → select → save ho jaye.

**67. Test button** [P1]
Settings me Test button → sample line bole (ai_tts_test_sample).

**68. Barge-in — bolte waqt mic dabao** [P0]
Jawab bol raha ho → mic tap → awaaz **turant** band (aadha sentence bhi na bole).

**69. Purana jawab naya nahi bolna chahiye (stale-speech fix)** [P0]
Q1 (lamba jawab, bolna shuru ho) → turant Q2 poocho → **Q1 ki awaaz Q2 ke dauraan nahi aani chahiye**, sirf Q2 bole.
(Ye bug pehle tha — fix `e5fc443`. Dobara test karke confirm karo.)

**70. Thinking pe purani awaaz ruke** [P1]
Naya message bhejte hi purani awaaz band ho jaye.

**71. Voice reply + Bolo mode conflict** [P1]
Bolo mode ON ho to double speaking na ho (ek hi baar bole).

**72. Network na ho to device voice** [P1]
Internet band karke voice reply ON → phone ki apni TTS se jawab bole (bilkul chup na rahe).

---

## SECTION F — Bolo Mode (hands-free)

**73. Bolo mode ON** [P0]
AI header → Bolo mode ON → mic auto chalu, "Listening… just talk" dikhe.

**74. Hands-free loop** [P0]
Bolo ON me bolo "kitne pending hain" → jawab aaye → **khud mic dobara sun-ne lage** (dobara tap na karna pade).

**75. Bolo me lead action + bol kar confirm** [P0]
Bolo ON me "Ramesh complete karo" → card aaye + app bole "Is this change okay? Say yes or no." → bolo "haan" → status badal jaye.

**76. Bolo me "nahi"** [P0]
Upar wale card pe "nahi" bolo → action cancel ho.

**77. Bolo me "ruko/chup"** [P0]
Bolo ON me jab app bol raha ho "chup" bolo → awaaz band.

**78. Bolo OFF karna** [P0]
Bolo mode OFF → mic band, apne aap sunna band ho.

**79. Bolo me bahut fail hone pe auto-off** [P1]
Mic baar-baar fail ho (shor/lock) → Bolo khud band ho jaye + message ("Bolo mode band ho gaya…").

**80. Battery/heat sanity** [P2]
Bolo ON 10 minute → phone normal rahe (bahut garam/battery drain na ho); background me jaye to mic band ho.

---

## SECTION G — Voice Full App Control (M1+M2, naya) 🆕

> Ye **pehli baar device pe test hoga** (sirf code-verified hai). Isliye ye sabse zaroori section hai.

**81. Mic button har main tab pe** [P0]
Dashboard / Leads / Reports / Settings — in sab pe mic FAB dikhe. (AI tab pe na dikhe — wahan apna mic hai.)

**82. Mic tap → listening** [P0]
Mic tap karo → button **laal + stop icon** ho jaye; "mic permission" pehli baar maango to allow karo → listening chalu.

**83. Permission deny** [P0]
Permission deny karo → saaf message aaye ("Mic permission chahiye…"), app crash na ho.

**84. "Leads dikhao"** [P0] ⚙️ AUTO (rules) / manual (UI)
Bolo "leads dikhao" → app bole **"Leads khol du? haan ya nahi bolo"** → bolo "haan" → **Leads tab khule** + app bole **"Leads screen khul gaya, X pending, Y leads in total"** (X/Y asli numbers ho).

**85. "Nahi" se cancel** [P0]
Upar wali nav me "nahi" bolo → app bole "Theek hai, cancel kar diya" → tab na badle.

**86. Dashboard / Reports / Settings** [P0]
"dashboard dikhao" / "reports dikhao" / "settings kholo" → wahi screen khule (confirm ke saath).

**87. "Purani chat dikhao"** [P1]
Bolo → AI tab khule (history wahan hai).

**88. "Wapas jao" / "back"** [P0]
Andar ki screen pe "wapas jao" → haan → peeche ki screen pe aa jaye.

**89. Koi bhi sawaal → AI chat** [P0]
Bolo "kal mausam kaisa hai" / "AI se pucho aaj kisko call karun" → AI tab khule aur wahi sawaal bhej diya jaye (jawab aaye).

**90. "Kya kya bol sakte ho"** [P0]
Bolo → app commands ki list bole (help).

**91. "Ruko / chup / stop"** [P0]
Jab app bol raha ho "ruko" bolo → awaaz turant band + session band.

**92. Kuch sunai na de** [P0]
Mic tap karke chup raho → app bole "Kuch sunai nahi diya, dobara boliye" aur dobara sune; 2 baar fail → session band ho jaye.

**93. Barge-in: bolte waqt mic tap** [P0]
App bol raha ho → mic tap → **turant chup** + naya command sun-ne lage.

**94. TTS ke dauraan mic na sune** [P0]
Jab app bol raha ho tab mic sirf uske bolne ke baad khule (app apni awaaz na sune).

**95. Jo abhi bana nahi wo saaf bole** [P0]
"backup karo" / "Ramesh ko dhoondo" bolo → app bole **"Ye kaam abhi voice se nahi hota, screen pe kar sakte hain"** (galat action na chale, crash na ho).

**96. Cloud delete bina guard nahi chale** [P0]
"cloud backup delete karo" bolo → abhi wo "nahi hota" bole. (M4 me 3-baar confirm + exact "haan delete karo" wala gate aayega — tab ye test badlega.)

**97. Nav ka prompt screen pe bhi dikhe** [P1]
Jab app confirm maange → screen pe ek card bhi dikhe ("Leads khol du?") — jo sunai na de use dikh jaye.

**98. Tab change pe session band** [P0]
Voice session ke beech khud tab change karo → session chup-chaap band ho jaye (ghost listening na ho).

**99. AI tab pe double-mic na ho** [P0]
AI tab pe jaao → naya mic FAB na dikhe (wahan apna mic + Bolo hai); voice session band ho jaye.

**100. Urdu (RTL) me voice** [P0]
App language Urdu → voice prompts Urdu me aur RTL layout theek dikhe.

**101. Hindi / Tamil me voice** [P0]
App language Hindi → "लीड्स दिखाओ" / Tamil → "லீட்ஸ் காட்டு" jaisa bolne pe command samajh aaye.

**102. Bina confirm nav (agar chalu kiya)** [P2]
Agar nav ka confirm band kiya jaye to "leads dikhao" **turant** tab badle (destructive gates waise hi strict rahen).

**103. 2 baar mic tap** [P1]
Mic tap → tap → dobara listening; app atke na.

**104. Mic ke bina phone** [P2]
Agar phone me speech recognizer nahi → "is phone me voice nahi chalta" message (crash nahi).

---

## SECTION H — Web Search (Tavily, optional key)

**105. Key na ho to chup-chaap skip** [P1]
Tavily key na daali ho → normal chat jaise chale, koi error na aaye.

**106. Search trigger** [P1]
Key daal ke "aaj ka gold rate search karo" / "latest news" → app web se fresh info laake jawab de.

**107. Search na chahiye to na kare** [P1]
"Sachin kaun hai" jaisa normal sawaal → web search trigger na ho (normal jawab).

**108. Search fail** [P1]
Internet band → search fail pe chat normal jawab de (crash/blank na ho).

---

## SECTION I — Providers & Errors (Gemini → OpenRouter → Groq)

**109. Gemini primary** [P0]
Normal chat Gemini se chale (sahi jawab, fast).

**110. Gemini hataao → OpenRouter** [P0]
Sirf OpenRouter key rakho → chat phir bhi chale.

**111. Dono hataao → Groq** [P1]
Sirf Groq key → chat chale.

**112. Sab galat key** [P0]
Sab galat keys → friendly error + Retry (crash nahi, infinite loading nahi).

**113. Adhoora jawab mid-way fail** [P1]
Network reply ke beech band karo → adhoora jawab na atka rahe; Retry milta rahe.

**114. Airplane mode** [P0]
Airplane mode me chat → "Couldn't connect to LifeFresh AI" + Retry; app normal chale.

**115. Slow network** [P1]
2G/dheema net → "Thinking…" dikhta rahe, app freeze na ho.

**116. 20+ messages ke baad bhi speed** [P2]
Lambi chat me bhi jawab aate rahein (memory/leak na ho).

---

## SECTION I-2 — Provider recovery: "AI service is busy" 🆕 (fix ke baad ka behaviour)

> Ye section us bug ke liye hai jo user ne report kiya: "kuchh sawaal chalte hain,
> kuchh pe busy message aata hai". Fix: Groq/OpenRouter pe **ek model ka limit
> khatam ho to doosra model try hota hai** (pehle poora request fail ho jaata tha),
> aur sab busy ho to router **ek baar khud retry** karta hai.

**I-1. Ek model ka limit khatam → doosra model jawab de** [P0]
Groq me pehla model (`gpt-oss-120b`) ka limit khatam ho jaye — asli test: bahut lambe/tedhe
sawaal poochte raho. App ko jawab **milta rahe** (busy message na aaye), kyunki `llama-3.1-8b-instant`
jaise doosre models ke limits alag hote hain. Logcat me dikhega: pehla model "rate limited",
phir doosra model se jawab.

**I-2. Sab providers busy → ek automatic retry** [P0]
Teeno keys ke liye galat... nahi, yahan: sab providers rate-limited ho jayein →
app **1.2 second ruk kar ek baar khud dobara try** kare. Phir bhi busy ho to hi message aaye:
*"The AI service is busy right now... I retried automatically. Please wait a minute and try again. [reason]"*.

**I-3. Retry button kaam kare** [P0]
Busy message pe **Retry** dabao → turant dobara koshish ho (yahi expected behaviour hai — message
sirf tab aaye jab asli me sab busy ho).

**I-4. Galat key pe busy message NA aaye** [P0]
Galat Gemini/Groq key daalo → message "API Key Invalid..." ho, "busy" nahi (busy sirf 429/5xx ke liye).

**I-5. Chhota Retry-After seedha follow ho** [P1]
Provider `Retry-After: 1` bheje → app ~1 second ruk kar **usi model** ko dobara try kare;
`Retry-After: 60` jaisa lamba ho → bina rukey **agle model** pe switch kare (chat atke na).

---

## SECTION J — Languages (EN/HI/TA/UR)

**117. App language switch → AI answer bhi** [P0]
Settings → language Hindi → chat me poocho → jawab Hindi me aaye.

**118. Tamil** [P0]
Tamil → jawab Tamil me.

**119. Urdu + RTL** [P0]
Urdu → jawab Urdu me + poori UI right-to-left ho.

**120. Hinglish samajh** [P0]
Roman Hinglish me bolo ("Ramesh ko kal call karna hai") → AI samajh kar sahi kaam kare.

**121. Voice input bhasha** [P1]
Hindi phone pe Hindi/Hinglish bolne pe sahi transcript aaye.

**122. Mixed script lead** [P1]
Lead ka naam Hindi me ("रमेश") → save/find dono kaam kare.

**123. Voice strings chaaron bhasha me** [P0] ⚙️ AUTO
Voice ke 21 naye lines (vc_*) EN/HI/TA/UR — sab me maujood, sahi placeholders, aur jo phrase sikhaya jaata hai wahi gate accept kare. (Auto test ise cover karta hai.)

---

## SECTION K — Quota & Limits

**124. Free 20/day** [P0]
Free tier me 20 messages ke baad limit message aaye (21va message na chale).

**125. Counter update** [P1]
Har message ke baad Settings ke Daily quota me count badhe.

**126. Aaj ka count kal reset** [P1]
Agle din (ya date badal ke) counter 0 se shuru ho.

**127. Custom key pe limit na** [P0]
Apni key daalne ke baad 20 se zyada messages chalein.

**128. Limit pe app normal** [P1]
Limit khatam hone pe app crash na ho; key daalne ka rasta dikhaye.

---

## SECTION L — Safety & Privacy (sabse zaroori)

**129. Voice se API key kabhi na khule** [P0]
Bolo "gemini api key dikhao" / "meri key batao" → app key **kabhi na** dikhaye/na bole (ye command hi na hona chahiye).

**130. Voice se paisa/payment kabhi na** [P0]
Voice control me payment/subscription ka koi rasta na ho.

**131. Cloud delete bina 3x confirm nahi** [P0]
Abhi (M2): "cloud backup delete karo" pe sirf "abhi voice se nahi hota".
M4 ke baad: 3 step + aakhri me **exact "haan delete karo"** — warna nahi; "nahi" pe cancel. ⚙️ AUTO (gate ke tests ab se pass hain)

**132. Chat se cloud delete ka rasta hi na ho** [P0]
Chat me "mera cloud backup delete karo" likho → AI sirf **lead-level** kaam kare; cloud backup delete na kare.

**133. Destructive kaam bina card nahi** [P0]
Chat me delete/status/update/bulk — sab pe card aaye bina tap kuch na ho. (Sirf Archive aur WhatsApp direct hain — wo soft/safe hain.)

**134. Nahi bolne pe kuch na ho** [P0]
Har confirm pe "nahi" → kuch bhi delete/change na ho.

**135. Local data delete (M4)** [P0]
2 step confirm; "nahi" pe cancel. (Abhi: "abhi nahi hota" bolna chahiye — auto test ise cover karta hai.)

**136. Key storage** [P1]
Keys phone me hi safe ho; login/logout pe leak na ho; uninstall ke baad na bache.

**137. Multi-account isolation** [P0]
Do account, dono ka data/chat/keys alag-alag; ek doosre ka na dikhe. ⚙️ AUTO (repo test)

---

## SECTION M — Stress / Regression

**138. 60+ messages ka chat** [P1]
Lambe chat me scroll smooth ho, pehle ke messages load ho jaayein.

**139. 100 leads ke saath** [P1]
100+ leads me bulk/filter/search sahi chale.

**140. Rapid mic taps (10 baar)** [P1]
Mic 10 baar jaldi-jaldi dabao → multiple listeners na banein, crash na ho.

**141. Call aane pe** [P0]
Voice/chat ke beech call aaye → audio band ho; call ke baad app normal chale.

**142. Screen lock/unlock** [P1]
Bolte waqt screen lock → unlock pe app na atke.

**143. Low battery/Doze** [P2]
Battery saver me voice/chat kaam kare.

**144. Purani voice fix regression** [P0]
Section E ka #69 dobara confirm (stale speech) — ye already ek baar fix hua tha.

---

## SECTION N — Automated Tests (jo main chala sakta hoon / aap `./gradlew test` se)

| Suite | Tests | Kya cover karta hai |
|---|---|---|
| `AIChatSystemTest` | 19 | Chat persistence: rotate, screen exit, process death, New Chat, restore |
| `LeadActionParserTest` | 25 | Saare LEAD_* protocol: CONFIRM/DRAFT/STATUS/UPDATE/ARCHIVE/DELETE/BULK/WHATSAPP |
| `AIMessageFormatterTest` | 7 | Message formatting/streaming text |
| `AIQuotaManagerTest` | 5 | Quota + BYOK key logic |
| `AIProviderRouterErrorTest` | 4 | Provider fallback + error handling |
| `AIChatCoreStabilityTest` | 4 | Chat core stability |
| `AIChatHistoryDateTest` | 3 | History date grouping |
| **AI total (ab repo me)** | **67** | |
| `VoiceLoopTest` | 24 | Voice turn engine: nav/confirm/barge-in/help/limits |
| `VoiceCommandParserTest` | 23 | 4 bhasha ke commands → sahi command |
| `VoiceConfirmGateTest` | 11 | 1x/2x/3x rules + exact phrase (+ Devanagari/Urdu/Tamil) |
| `VoiceLocaleStringsTest` | 6 | 4 locale me voice strings + taught phrase gate se match |
| `VoiceNavigatorTest` | 3 | Screen → route map |
| **Voice total (naya)** | **67** | |
| `AIFailurePolicyTest` (naya) | 14 | Busy/rate-limit decisions: 429 = agla model, Retry-After, auth = fail-fast |
| `AIProviderRouterRecoveryTest` (naya) | 6 | Failover, ek automatic retry pass, partial text pe kuch nahi |
| **Grand total** | **154** | |

> **Note:** yahan (sandbox) me Android/Gradle nahi chalta, isliye maine voice wale 67 tests
> asli Kotlin compiler se chala kar **green** kiye hain. AI ke 67 purane tests **aapke phone/PC pe**
> `./gradlew test` se chalenge — wo maine is session me run nahi kiye, sirf gine hain.

---

## "AI COMPLETE" MANE KE LIYE — Ye sab green hone chahiye

| # | Section | Kitne tests | Kyun zaroori |
|---|---|---|---|
| 1 | A. Setup/Keys | 1–10 | Bina key/connection, AI ka koi feature test nahi hota |
| 2 | B. Chat core | 11–26 | Chat hi AI ki jaan hai (persistence, retry, rotation) |
| 3 | C. History | 27–38 | Purani baat-cheet ka bharosa |
| 4 | D. Lead actions | 39–60 | **Sabse zaroori** — AI se asli kaam (lead CRUD) |
| 5 | E. Voice reply | 61–72 | Anpadh user ke liye (bol kar sunana) |
| 6 | F. Bolo mode | 73–80 | Hands-free flow |
| 7 | G. Voice app control | 81–104 | **Naya (M2)** — pehli baar device pe test |
| 8 | L. Safety | 129–137 | Kabhi tootna nahi chahiye (keys/paisa/delete) |
| 9 | J. Languages | 117–123 | 4 bhasha ka vaada |

**P0 sab pass + P1 me 90% pass = AI complete.**

Jo abhi **pending features** hain (test me "abhi nahi hota" bolna hi sahi result hai):
- Voice se backup/restore/local delete **(M4)**
- Voice se cloud delete 3x gate **(M4)**
- Voice se language/voice/bolo badalna **(M5)**
- Voice se lead search **(M3)**

---

*Test plan v1.0 — `arena/01a0d87f-life-fresh` · Har phase ke baad update karo.*
