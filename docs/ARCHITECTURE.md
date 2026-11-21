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
             │ on open, and on Regenerate only
             ├─► SuggestionRequestAssembler.assemble   merges the chat into history first, then
             │     reads history, contact note, settings, app label
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
| `chatParserRegistry` | `Parsers.registry()` | WhatsApp, Telegram, generic fallback. |
| `databaseKeyProvider` | `KeystoreDatabaseKeyProvider` | One SQLCipher passphrase, wrapped by a Keystore key. |
| `historyRepository`, `contactNoteStore` | `RoomHistoryRepository`, `RoomContactNoteStore` | Share `history.db`. |
| `suggestionRequestAssembler` | `HistorySuggestionRequestAssembler` | App labels via `PackageManager` (launcher apps are visible through `<queries>`). |
| `replyGenerator`, `llmReplyGenerator`, `connectionTester` | `LlmReplyGenerator` | Active provider profile, OpenAI or Anthropic format (see below); optional 3 parallel calls; "Test connection". |
| `replyPanel` | `ComposeReplyPanel` | |
| `settingsRepository`, `costGuard` | DataStore-backed (`settings`, `cost`) | |
| `profileRepository`, `profileSecretStore` | `DataStoreProfileRepository`, `EncryptedProfileSecretStore` | `profiles` (no secrets), `profile_secrets` (AES-GCM per profile id). Migrates the legacy config once. |
| `apiKeyStore` | `ActiveProfileApiKeyStore` | The active profile's key, behind the old interface (onboarding uses it). The legacy `api_key` store is only read by the migration. |
| `feedbackLog` | `RoomFeedbackLog` | `feedback.db`, opened on first use off the main thread. |
| `themeSettings` | `ThemeSettings` | The "Material You colours" flag as a hot `StateFlow`, so overlay windows theme their first frame. |

`InMemory…` classes next to the interfaces are test fakes. Add behaviour to the service as a new
`AccessibilityComponent`, never in the service itself.

## Storage

- **Two encrypted Room DBs, one key.** `history.db` (messages, contact notes) and `feedback.db`
  (feedback log) both open with SQLCipher and the passphrase from `DatabaseKeyProvider`. They open
  lazily, so no Keystore work runs on the main thread or at service connect.
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
- Only a tap (button, Regenerate, Retry) leads to an LLM call; passive capture never calls it.
- Logs carry event names, counts and class names only: no chat text, prompts or key.

## Provider profiles (`settings/ProviderProfiles.kt`, `llm/`, `ui/settings/profiles/`)

- A profile is name, `ApiFormat`, base URL, model, optional max tokens, temperature and extra
  header names. The key and header values sit in `ProfileSecretStore`, encrypted with the same
  Keystore key as the old API key; each blob's associated data names its profile id and kind.
- **Migration.** On the first read or write, if no profile list exists, the legacy
  `ReplylySettings.endpoint`/`model` and the legacy key become one active OpenAI-format profile
  ("OpenRouter", or "Custom" for another endpoint). It writes only if the list is still absent, so
  it runs once. The legacy fields and key are left in place (unused) for a downgrade.
- **URLs** (`llm/ProviderUrls`): the base URL includes the version path. OpenAI format appends
  `/chat/completions`; Anthropic format appends `/messages`, adding `/v1` first if the URL has no
  `vN` segment. A pasted full endpoint is not doubled.
- **OpenAI format** (`ChatCompletionsClient`): `Authorization: Bearer`, `response_format`
  json_object. Only an openrouter.ai host gets `reasoning: {effort: low}` and the attribution
  headers. Temperature only if the profile sets one.
- **Anthropic format** (`AnthropicMessagesClient`): native Messages API, `x-api-key`,
  `anthropic-version: 2026-10-21`, top-level `system`, `max_tokens` always set, no temperature.
  JSON via `output_config.format` (a JSON schema of the suggestions contract); `effort: low` only
  on api.anthropic.com. `stop_reason` `max_tokens` maps to the "cut off" failure, `refusal` to a
  fixed bad-output reason. Usage = input (+ cache) and output tokens.
- Both formats share `LlmHttp` (fixed error wording per status, 529 "overloaded") and the JSON-mode
  fallback: a 400/422 naming the JSON fields retries once without them, remembered per URL+model.
- Extra headers are applied after the format's own headers and may override e.g.
  `anthropic-version`, but never `Authorization`, `x-api-key`, `Content-Type`, `Content-Length`
  or `Host`.
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
