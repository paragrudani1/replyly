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
             │ on open, and on Regenerate, Retry or a quick-chip tap only
             ├─► SuggestionRequestAssembler.assemble   merges the chat into history first, then
             │     reads history, contact note, settings, app label
             ├─► ReplyGenerator.generate ──► SuggestionResult
             │     API key, https endpoint, CostGuard.tryAcquire, call(s), recordUsage
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
| `replyGenerator` | `OpenAiReplyGenerator` | OpenAI-compatible chat completions; optional 3 parallel calls. |
| `replyPanel` | `ComposeReplyPanel` | |
| `settingsRepository`, `apiKeyStore`, `costGuard` | DataStore-backed (`settings`, `api_key` AES-GCM, `cost`) | |
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
- Settings, the encrypted API key and the cost counters are Preferences DataStores. No backups
  (`allowBackup="false"`).

## Purges

`retentionDays` from settings, no WorkManager:

| Data | When |
|---|---|
| History | On service connect, then at most once a day after a passive capture. |
| Feedback log | On service connect (`FeedbackLog.purgeExpired`), at most once a day on `record`, and when Settings opens. |

## Safety rules in code

- Only `ReplyInserter` acts on another app, and only by setting text (`InvariantsTest`).
- The only network use is in `llm/`, and the generator refuses a non-https endpoint
  (`Failure.InsecureEndpoint`) before the cost check.
- Only a tap leads to an LLM call: the button, Regenerate, Retry or a quick-chip tap, each one call
  (none while a call or an insert runs, or after an insert). Passive capture never calls it.
- Logs carry event names, counts and class names only: no chat text, prompts or key.

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
