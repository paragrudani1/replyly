# Replyly — Android App Spec (2026-11-21)

## 1. Summary
A personal Android app for a Samsung S23. A floating button shows above the keyboard in chat apps. When you touch it, the app reads the chat, sends the context to a cheap but capable LLM, and shows 3 reply suggestions in your own style. When you touch a suggestion, the app inserts it into the input field. You edit it and send it yourself.

## 2. Scope
- Users: only the owner. Install by sideload. No Google Play release in v1.
- Device: Samsung S23 (One UI). minSdk 34.
- Apps with a full parser: WhatsApp, WhatsApp Business, Telegram.
- Generic fallback: other apps (Slack, LinkedIn, Instagram and more) send the visible text only. They have no history.
- Languages: English and Hinglish/Gujlish (Latin script). The reply uses the same language and script as the last messages.
- Build plan: build all components in one pass (no milestones).

## 3. Architecture
- Stack: native Kotlin, single module. Views or Compose for the overlay, Compose for settings.
- AccessibilityService: listens for window events in target apps. Reads the screen tree, finds the input field, inserts text with ACTION_SET_TEXT.
- Overlay: a TYPE_ACCESSIBILITY_OVERLAY floating button. Shows only when the input field of a target app has focus and the keyboard is open.
- Parser layer: one parser per supported app, plus a generic parser.
- History store: Room on SQLCipher. DB key in Android Keystore.
- LLM client: OpenAI-compatible HTTP client (OpenRouter by default).
- Settings: API endpoint, key, model, N, About me, blocklist, limits, retention period.

## 4. Chat capture and parser
- Passive capture: in a target app, when the screen content changes, wait ~1 s after the last change, then read the screen.
- Do not read: blocklisted chats, password fields, banking apps, any screen with FLAG_SECURE.
- Parser method: view IDs first (e.g. com.whatsapp:id/message_text). If IDs do not match, geometry rules: right-side bubble = me; left-side bubble = other person; in a group, a name label above a bubble gives the sender.
- Parser output: chat key, chat title, list of messages with sender, text, time shown in bubble, isMe.
- Debug screen: hidden screen that dumps the current screen tree as JSON. These dumps are test fixtures.

## 5. Local history
- Chat key: app package + chat title. Renamed contact = new history (acceptable).
- Dedup: hash of (sender, text, time shown), then merge the new screen read with the last saved messages by overlap (diff-like). Keeps correct order and keeps repeated messages like "ok", "ok".
- Encryption: SQLCipher, key in Keystore.
- Retention: delete messages older than 60 days (setting). "Delete this chat's history" action.
- Generic apps: no history.

## 6. Prompt and LLM
- Endpoint: OpenRouter-compatible; model is a setting.
- Model choice: cheap but capable, not lowest tier (no Lite/nano). Shortlist to test on 20 real chats: Claude Haiku 5.5, latest Gemini Flash, latest GPT mini.
- Prompt content: chat name and app; last N messages from history (N = 40, setting); About me (global text); contact note for this chat (optional); style examples = user's messages in this chat + 10–15 of user's recent messages from other chats in the same app; user's optional instruction (e.g. "say I'll call at 6").
- Output: one call returning JSON {"suggestions":[{"intent":"...","text":"..."}]} with 3 items, each a different intent (e.g. agree, decline, ask a question).
- System prompt rules: same language and script as the chat; copy user's length, tone, emoji use; do not invent facts.
- Latency target ~1–2 s. If slower, switch to 3 parallel calls.

## 7. UX
- Floating button above Gboard in target apps.
- Panel after touching the button: 3 suggestion cards (touch to insert); instruction field + Regenerate; "Contact note" button opening the note for this chat in the panel.
- Insert only. The app never sends a message.
- Onboarding steps for S23: 1) allow "Restricted settings" in App info (sideloaded apps); 2) enable the accessibility service; 3) Battery = "Unrestricted"; 4) remove from "Sleeping apps"; 5) enter API key and select model.

## 8. Quality, cost, security
- Feedback log (local): prompt, 3 suggestions, inserted suggestion, final sent message (from next history read). Same 60-day retention.
- Cost guard: daily request limit (default 200) and monthly token counter in settings. Only a button touch calls the LLM; screen changes never do.
- API key: Keystore + encrypted preferences.
- Privacy: no backend. Phone calls the LLM API directly. Chat data stays on the phone except the per-request prompt.

## 9. Testing and delivery
- Code in GitHub repo; Parag reviews PRs.
- CI: GitHub Actions builds debug APK and attaches it to a release.
- Parser tests: unit tests on saved screen-tree JSON fixtures — 1:1 chat, group chat, same text sent twice, Hinglish. New fixture whenever an app update breaks the parser.
- Model test: run the shortlist on 20 real chats; compare with the feedback log.

## 10. Tickets (all in one pass)
1. Overlay and button — AccessibilityService setup, input-field focus detection, floating button above keyboard, ACTION_SET_TEXT insert, blocklist and sensitive-screen checks.
2. Parser and fixtures — WhatsApp, WhatsApp Business, Telegram parsers (view IDs, then geometry), generic parser, debug tree-dump screen, fixture-based unit tests.
3. Encrypted history — Room + SQLCipher with Keystore key, passive capture with 1 s wait, hash + overlap merge, 60-day retention, delete one chat.
4. LLM client and prompt — OpenAI-compatible client, model setting, prompt builder (history, About me, contact note, style examples, instruction), JSON output parse + validation, error/timeout handling.
5. Panel UI — 3 cards, instruction field, Regenerate, contact note editor, loading and error states.
6. Settings, logs and cost guard — settings screen, encrypted API key, feedback log, daily request limit, monthly token counter.
7. Onboarding — S23 setup steps (Restricted settings, accessibility, battery, sleeping apps, API key) with checks that show which step is not done.
