# Replyly architecture

What the app does: [SPEC.md](SPEC.md). This file maps the code as it is now.
All code lives in the single `:app` module, package `com.paragrudani.replyly`.

## Data flow

```
                       ReplylyAccessibilityService  (service/: forwards callbacks only)
                         │ onConnected / onEvent to AppGraph.accessibilityComponents()
             ┌───────────┴─────────────────────────────┐
     OverlayComponent (overlay/)               PassiveCaptureComponent (history/capture/)
     input focused + keyboard open             WhatsApp/Telegram screen changed:
     → shows the button                        1 s quiet, at most 3 s into a busy screen
     user taps the button                              │
             │                                         │
             ▼                                         ▼
     host.captureWindowChecked(chat window)    host.captureActiveWindowChecked()
             └──────────► FLAG_SECURE probe first (fails closed): a secure window comes back
                          unread, with secureWindow = true
                          │
                  SensitiveScreenPolicy.evaluate ── Blocked ──► stop, read nothing more
                          │ Allowed
                  ChatParserRegistry.parse ──► ParsedChat      (parser/)
                          │                            │
                          │                    HistoryRepository merge (history/)
                          │                    FeedbackLog.attachFinalMessage
             ▼
     ReplyPanel.Content(PanelRequest(chat))   (panel/, in the overlay's modal window)
             │ on open, and on Regenerate, Retry, a quick-chip tap or "Suggest follow-ups anyway" only
             ├─► SuggestionRequestAssembler.assemble   merges the chat into history first, then
             │     reads history, contact note, settings, app label, the clock (Now, 12/24 h)
             ├─► ReplyTarget.decide (pure, not on X) ── NothingToReply ──► "Nothing to reply to", no call
             ├─► ReplyGenerator.generate ──► SuggestionResult
             │     active profile, API key, https endpoint, CostGuard.tryAcquire, call(s), recordUsage
             ├─► FeedbackLog.record
             │ user taps a card
             └─► ReplyInserter.insert(text) (ACTION_SET_TEXT on the remembered input) + markInserted
                     the user edits and sends the message themselves
```

The debug dump (`ui/debug/DumpCapturer`) reads the same way: checked capture, then the policy
(package, screen, then chat once parsed), then it stores the tree.

## Bindings (`di/AppGraph.kt`)

One lazy binding per interface, all real implementations:

| Binding | Implementation | Notes |
|---|---|---|
| `overlayComponent` | `OverlayComponent` | Button and panel windows, tap flow. |
| `captureComponent` | `PassiveCaptureComponent` | Also runs both purges on connect. |
| `replyInserter` | `AccessibilityReplyInserter` | Can only set text. |
| `sensitiveScreenPolicy` | `DefaultSensitiveScreenPolicy` | Order: secure window, blocklisted app, banking app, password field, blocklisted chat. Blocks everything until settings load. |
| `chatParserRegistry` | `Parsers.registry()` | WhatsApp, Telegram, X, generic fallback. X reads "My X handle" from a hot `StateFlow` of settings. |
| `databaseKeyProvider` | `KeystoreDatabaseKeyProvider` | One SQLCipher passphrase, wrapped by a Keystore key. |
| `historyRepository`, `contactNoteStore` | `RoomHistoryRepository`, `RoomContactNoteStore` | Share `history.db`. |
| `suggestionRequestAssembler` | `HistorySuggestionRequestAssembler` | App labels via `PackageManager` (launcher apps are visible through `<queries>`); `clock`, the current zone and `DateFormat.is24HourFormat` for the prompt's Now header. |
| `replyGenerator`, `llmReplyGenerator`, `connectionTester` | `LlmReplyGenerator` | Active provider profile, OpenAI or Anthropic format (see below); one call per tap; "Test connection". |
| `replyPanel` | `ComposeReplyPanel` | |
| `settingsRepository`, `costGuard` | DataStore-backed (`settings`, `cost`) | `settings` runs `SettingsMigrations.ALL` (drops `use_parallel_calls`). |
| `profileRepository`, `profileSecretStore` | `DataStoreProfileRepository`, `EncryptedProfileSecretStore` | `profiles` (no secrets), `profile_secrets` (AES-GCM per profile id). Migrates the legacy config once. |
| `apiKeyStore` | `ActiveProfileApiKeyStore` | The active profile's key, behind the old interface (onboarding uses it). The legacy `api_key` store is only read by the migration. |
| `feedbackLog` | `RoomFeedbackLog` | `feedback.db` (schema 3: `prompt_version`, `analysis`, `panel_id`, `cache_read_tokens`, `x_mode`), opened on first use off the main thread. |
| `themeSettings` | `ThemeSettings` | The "Material You colours" flag as a hot `StateFlow`, so overlay windows theme their first frame. |

`InMemory…` classes next to the interfaces are test fakes. Add behaviour to the service as a new
`AccessibilityComponent`, never in the service itself.

## Storage

- **Two encrypted Room DBs, one key.** `history.db` (messages, contact notes) and `feedback.db`
  (feedback log) both open with SQLCipher and the passphrase from `DatabaseKeyProvider`. They open
  lazily, so no Keystore work runs on the main thread or at service connect.
- `history.db` v2 stores a date per message (an epoch day): exact when a date separator showed
  it, otherwise approx (the local day the row was first seen). `HistoryMerge` explains how it is used.
- If the passphrase is lost for good, both DBs are wiped and a new one is created (no crash loop).
  A transient Keystore failure throws `DatabaseKeyUnavailableException` and wipes nothing; the
  panel shows it as `Failure.StorageLocked` ("encrypted storage is locked, try again").
- Settings, provider profiles, their encrypted secrets, the legacy encrypted API key and the cost
  counters are Preferences DataStores. No backups (`allowBackup="false"`).

## Purges

`retentionDays` from settings, no WorkManager:

| Data | When |
|---|---|
| History | On service connect, then at most once a day after a passive capture. |
| Feedback log | On service connect (`FeedbackLog.purgeExpired`), at most once a day on `record`, and when Settings opens. |

## Safety rules in code

- Only `ReplyInserter` acts on another app, and only by setting text (`InvariantsTest`).
- The only network use is in `llm/`, and the generator refuses a non-https endpoint
  (`Failure.InsecureEndpoint`) before the cost check, for both API formats.
- API keys and extra header values are secrets: encrypted at rest, never shown after saving, never
  logged. Header names may be logged. `toString` of profile configs, drafts and requests hides them.
- Only a tap leads to an LLM call: the button, Regenerate, Retry or a quick-chip tap, each one call
  (none while a call or an insert runs, or after an insert). Passive capture never calls it.
- Logs carry event names, counts and class names only: no chat text, prompts or key.

## Prompt v2 (`llm/PromptBuilder`, `llm/ReplyTarget`, issue #26)

- **System prompt**: one static constant, `PromptBuilder.SYSTEM_PROMPT` (rules plus 3 worked
  examples), so it can be cached. Everything that changes is in the user message.
- **User message**, in order: `Now:` header (phone clock and zone, 12/24-hour as the phone, a
  time-of-day word: late night 00-05, morning 05-12, afternoon 12-17, evening 17-21, night 21-24);
  app; About me, contact note, style examples; the conversation in `<conversation>`: first the
  chat line (`Chat: <name> (1:1 with <contact>)` or `(group)`; the name is set by other people, so
  it is inside the untrusted block), then the messages grouped by day (`— Yesterday · Sat 21 Nov —`,
  `(approx)` for guessed dates), lines `[6:42 pm] Name: text` with the contact's name in 1:1 chats
  and "me", `[media]` for a message without text; the reply target; my instruction; the task.
  Chat-derived text cannot open or close `<conversation>` or `<reply_to>`.
- **Messages** reach the prompt as `PromptMessage` (message plus `date`, `dateIsApprox`,
  `firstSeenAt`; `repliesToMe` is read from the message). A history message gets its stored date
  and `dateIsApprox` from `history.db`; an on-screen-only one (generic apps, or a chat with no
  history yet) gets `ChatMessage.date`, exact, or no date.
- **Reply target** (`ReplyTarget.decide`, pure): `Reply` with every text message from others after
  my last one (in groups, mentions of me — a whole-word match of "Names people call me" — and
  replies to me first). Media (blank text) count as turns but are never targets: their photo after
  my message means `NothingToReply`. `FollowUp` when my message is the very last one and over 2
  hours old or a question; otherwise `NothingToReply`. Send time (`ReplyTarget.sentAt`, never later
  than the real one): exact date + bubble time; else the latest instant at or before `firstSeenAt`
  with the bubble's local time; else `firstSeenAt`. A question: a "?" in the last sentence, a
  question word first or a marker last ("… kya", "… na"); not when it ends in "!" without "?", nor
  exclamations like "kyun nahi" or "what a …". The panel checks it after assembling and before any
  call: `NothingToReply` shows "Nothing to reply to" with no call; "Suggest follow-ups anyway",
  Regenerate, or a chip tap that leaves an instruction makes one (each a user tap; later
  generations in that panel stay follow-ups).
- **Output**: `{"analysis": "...", "suggestions": [{"intent","text"} x3]}`. A missing analysis is
  read as empty. The analysis goes only to the feedback log, never to the panel or logcat.
- **Feedback log**: each entry stores `prompt_version` (1 for entries from before v2), `analysis`,
  a `panel_id` (one per panel open) and `cache_read_tokens` (Anthropic's prompt-cache read count, a
  number only, shown in the entry details). The feedback screen shows, per prompt version, the insert
  rate (panels with an inserted card / panels with suggestions) and the average edit amount
  (normalized edit distance between the inserted suggestion and the message sent, where history saw
  it; `feedback/FeedbackStats`). v1 entries have no panel id: entries of one chat within 2 minutes
  of the previous one count as one panel, and the row is labelled "(estimated)". The stats are
  computed on `Dispatchers.Default`.

## X replies and quotes (`parser/x/`, issue #30)

- **Detection** (`XFields`): `ButtonVisibility` asks an app-specific field gate after its usual
  checks; on X only the reply composer, the quote composer and a DM pass, told apart by the focused
  field's view id and hint (`FocusedInput.hint`). The timeline, a post without a focused composer,
  search and the new-post composer show no button. No tree walk.
- **Parser** (`XParser`, `XPostReader`): posts start at a header (name, @handle, time; the focal
  post stacks name over handle and shows an absolute time line) and run to the next one; a header
  indented past the main column or inside a quote card id starts the embedded quoted post. View ids
  (`XIds`, all guesses until DEVICE_CHECKLIST 15) first, text and geometry for each piece without
  one. The result is an `XContext` on `ParsedChat.x`: mode, target, parents oldest first, the
  composer's "Replying to" handles and my handle ("My X handle", else the composer's account).
- **No history**: `historyApplies = false`, X is not in `TargetApps.FULL_PARSER_PACKAGES`, so
  passive capture never reads it and the assembler's merge does nothing. Only the feedback entry is
  stored. DMs go through `GenericChatParser` (no `XContext`).
- **X prompt** (`llm/XPromptBuilder`, PR 2): `PromptBuilder.build` hands a request with
  `SuggestionRequest.x` to it. Own static `SYSTEM_PROMPT` (the v2 base, X voice, intents, rules,
  3 worked examples); the user message has Now, mode, "Post length: 280 (aim for at most 250)",
  About me, My X voice, my own posts on screen in `<my_posts>`, the parents in `<thread>` and the
  target in `<target>` (with the composer's "Replying to"), a "guess" line for a nearest-post
  target, my instruction and the task (reply, continue my thread, or quote). Post times get an
  approximate age (`XPostAge`). No `ReplyTarget`: the panel never shows "Nothing to reply to" on X.
  `ParsedChat.messages` still carries PR 1's interim thread for the debug dump.
- **Length** (`llm/XLength`): X's weighted count (twitter-text v3 weights, emoji clusters 2, URLs
  23). `SuggestionOutputParser.parse(…, lengthLimit)` trims a card over "X post length" at a
  sentence or word boundary (`Suggestion.trimmed`) or drops it; `Success.lengthLimit` and
  `droppedOverLimit` reach the panel, which shows a counter on each card and a line for dropped
  ones. One call per tap, never a retry.
- **Settings**: "On X" section: My X handle, My X voice (`my_x_voice`), "Longer X posts"
  (`x_post_length` 280/500). The feedback entry stores `x_mode` (`feedback.db` v3).

## Provider profiles (`settings/ProviderProfiles.kt`, `llm/`, `ui/settings/profiles/`)

- A profile is name, `ApiFormat`, base URL, model, optional max tokens, temperature and extra
  header names. The key and header values sit in `ProfileSecretStore`, encrypted with the same
  Keystore key as the old API key; each blob's associated data names its profile id and kind.
- **Migration.** On the first read or write, if no profile list exists, the legacy
  `ReplylySettings.endpoint`/`model` and the legacy key become one active OpenAI-format profile
  ("OpenRouter", or "Custom" for another endpoint). It writes only if the list is still absent, so
  it runs once. The legacy fields and key are left in place (unused) for a downgrade. If encrypting
  the copied key fails, nothing is written and the next read tries again; the Settings collector
  catches storage errors and shows fixed text.
- **URLs** (`llm/ProviderUrls`): the base URL includes the version path. OpenAI format appends
  `/chat/completions`; Anthropic format appends `/messages`, adding `/v1` first unless the LAST
  path segment is a version (`^v\d+[a-z0-9]*$`, any case). A pasted full endpoint is not doubled.
- **Editing a profile**: changing the host or format with a saved key and no new key typed is a
  validation error on the key (the key never follows a host change); Save clears the profile's
  last test result; "Remove key" is staged until Save.
- **OpenAI format** (`ChatCompletionsClient`): `Authorization: Bearer`, `response_format`
  json_object. Only an openrouter.ai host gets `reasoning: {effort: low}` and the attribution
  headers. `api.openai.com` gets `max_completion_tokens` and `reasoning_effort: low`; other hosts
  `max_tokens`. A 400 naming either token field retries once with the other, remembered per
  URL+model (in the client). Temperature only if the profile sets one.
- **Anthropic format** (`AnthropicMessagesClient`): native Messages API, `x-api-key`,
  `anthropic-version: 2026-10-21`, top-level `system`, `max_tokens` always set, no temperature.
  JSON via `output_config.format` (a JSON schema of `{analysis, suggestions}`); `effort: low` and
  prompt caching (`system` as one text block with `cache_control: {type: ephemeral}`, no beta
  header) only on api.anthropic.com. `stop_reason` `max_tokens` or `model_context_window_exceeded` maps to the "cut off" failure, `refusal` to a
  fixed bad-output reason. Usage = input (+ cache) and output tokens.
- Both formats share `LlmHttp` (fixed error wording per status, 529 "overloaded") and the JSON-mode
  fallback: a 400/422 naming the JSON fields retries once without them, remembered per URL+model.
- Extra headers are applied after the format's own headers and may override e.g.
  `anthropic-version`, but never `Authorization`, `x-api-key`, `Content-Type`, `Content-Length`,
  `Host` or `Accept-Encoding`.
- **Test connection** (`ConnectionTester`) uses the same checks, one tiny prompt with
  `max_tokens` 16 and no JSON mode; it counts 1 request and records its tokens. The UI shows
  "OK · N ms" or the panel's `toPanelError` wording.

## Other decisions

- The generator owns the cost check: it returns `NoApiKey` / `LimitReached` itself, so the panel
  only renders results. Panel errors are fixed wording (`panel/PanelErrors.kt`).
- The panel is a full-screen focusable modal over the chat (dimmed backdrop, tap or Back to close),
  `ADJUST_RESIZE` with IME padding. See the KDoc of `ComposeReplyPanel`.
- The panel's "Open settings" starts `MainActivity.settingsIntent`, which opens the Settings route.
- Deleting one chat's history is a panel action. The hidden debug screen opens with a long-press on
  the settings title.
- Accessibility config (`res/xml/accessibility_service_config.xml`): all packages, view ids,
  interactive windows, not-important views, `canTakeScreenshot` (for the FLAG_SECURE probe).
  Components filter by package.
- UI: every surface uses `ui/theme/ReplylyTheme` (also the overlay windows); the floating button
  is a View coloured by `replylyColorScheme`. Rules in [DESIGN.md](DESIGN.md); screenshot tests in
  [SCREENSHOTS.md](SCREENSHOTS.md).
- Device checks that only the S23 can confirm: [DEVICE_CHECKLIST.md](DEVICE_CHECKLIST.md).
