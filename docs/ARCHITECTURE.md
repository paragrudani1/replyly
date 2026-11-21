# Replyly architecture

What the app does: [SPEC.md](SPEC.md). This file is the map of the code and who owns which part of it.
All code lives in the single `:app` module, package `com.paragrudani.replyly`.

## Data flow

```
                       ReplylyAccessibilityService  (service/, skeleton: forwards events only)
                         │ onEvent(...) to each AccessibilityComponent from AppGraph
             ┌───────────┴─────────────────────────────┐
     overlayComponent (#1)                     captureComponent (#3)
     input focused + keyboard open             screen changed, wait ~1 s
             │                                         │
             └──► host.captureActiveWindow(): ScreenSnapshot   (screen/)
                          │
                  SensitiveScreenPolicy.evaluate  ── Blocked ──► stop, read nothing more
                          │ Allowed
                  ChatParserRegistry.parse ──► ParsedChat      (parser/, #2)
                          │                            │
                          │                    HistoryRepository.merge (history/, #3)
                          │                    FeedbackLog.attachFinalMessage (#3 calls it)
     user taps the button │
             ▼
     ReplyPanel.Content(PanelRequest(chat))   (panel/, #5; hosted in #1's overlay window)
             │ on open, and on Regenerate only
             ├─► SuggestionRequestAssembler.assemble(chat, instruction)   (llm/, #4)
             │     reads HistoryRepository, ContactNoteStore, SettingsRepository
             ├─► ReplyGenerator.generate(request) ──► SuggestionResult    (llm/, #4)
             │     checks ApiKeyStore, CostGuard.tryAcquire, then recordUsage (cost/, #6)
             ├─► FeedbackLog.record(...)                                 (feedback/, #6)
             │ user taps a card
             └─► ReplyInserter.insert(text) (#1, ACTION_SET_TEXT) + FeedbackLog.markInserted
                     the user edits and sends the message themselves
```

## Contracts (skeleton, shared by everyone)

| Package | Contract | Notes |
|---|---|---|
| `screen/` | `ScreenSnapshot`, `ScreenNode`, `Bounds`, `ScreenSnapshotJson`, `AccessibilityNodeMapper` | Debug-dump format = fixture format. Change it only by adding fields with defaults. |
| `parser/` | `ChatKey`, `ChatMessage`, `ParsedChat`, `ChatParser`, `ChatParserRegistry`, `TargetApps` | `ChatKey.encoded` = `"<package>:<title>"`, used in blocklists and DB rows. |
| `history/` | `HistoryRepository`, `StoredMessage`, `ContactNoteStore` | Lists are oldest first. |
| `settings/` | `ReplylySettings` (spec defaults), `SettingsRepository`, `ApiKeyStore` | The key is never part of `ReplylySettings`. |
| `llm/` | `SuggestionRequest`, `Suggestion`, `TokenUsage`, `SuggestionResult` (+ typed `Failure`s), `ReplyGenerator`, `SuggestionRequestAssembler` | |
| `cost/` | `CostGuard`, `CostStatus` | |
| `feedback/` | `FeedbackLog`, `FeedbackEntry` | |
| `policy/` | `SensitiveScreenPolicy`, `ScreenContext`, `SensitivityVerdict`, `BlockReason` | Both #1 and #3 ask it before reading. |
| `security/` | `DatabaseKeyProvider` | One SQLCipher passphrase for every encrypted DB. |
| `service/` | `ReplylyAccessibilityService`, `AccessibilityComponent`, `AccessibilityHost`, `AccessibilityHostHolder` | Add behaviour in a component, never in the service. |
| `insert/` | `ReplyInserter`, `InsertResult` | Can only set text. Nothing in the app can click or send. |
| `panel/` | `ReplyPanel`, `PanelRequest` | `@Composable Content(request, onClose)`. |
| `di/` | `AppGraph` | One lazy binding per interface; each points at a stub until its ticket lands. |

Every stub is named for what it is (`InMemory…`, `Stub…`, `Placeholder…`, `Unimplemented…`, `Minimal…`) and lives next to its interface. A ticket may delete its own stubs once AppGraph no longer uses them.

## Ownership map

A ticket edits its own files freely. Outside them it makes only the listed one-line edits.

| Ticket | Owns (create anything inside) | One-line edits elsewhere |
|---|---|---|
| #1 Overlay and button | `overlay/`, `insert/` impls, `policy/` impl | AppGraph: `overlayComponent`, `replyInserter`, `sensitiveScreenPolicy` |
| #2 Parser and fixtures | `parser/whatsapp/`, `parser/telegram/`, `parser/generic/`, `parser/Parsers.kt`, `ui/debug/`, `src/test/resources/fixtures/<app>/`, `src/test/.../parser/` | none (AppGraph already calls `Parsers.registry()`) |
| #3 Encrypted history | `history/db/`, `history/capture/`, `history/` impls, `security/` impl, `app/schemas/` | AppGraph: `captureComponent`, `databaseKeyProvider`, `historyRepository`, `contactNoteStore` |
| #4 LLM client and prompt | `llm/` impls (client, prompt builder, output parser, assembler) | AppGraph: `replyGenerator`, `suggestionRequestAssembler` |
| #5 Panel UI | `panel/` impls | AppGraph: `replyPanel` |
| #6 Settings, logs, cost guard | `settings/` impls, `cost/` impls, `feedback/` impls, `ui/settings/` | AppGraph: `settingsRepository`, `apiKeyStore`, `costGuard`, `feedbackLog` (the last one only after #3 has merged) |
| #7 Onboarding | `onboarding/` (checks), `ui/onboarding/` | `ReplylyNavHost.kt`: `startDestination` only |

Shared files with no owner: `gradle/libs.versions.toml`, `app/build.gradle.kts`, `AndroidManifest.xml`, the contract files in the table above, `ReplylyNavHost.kt`, `InvariantsTest.kt`. Every dependency the tickets should need is already declared. If a ticket truly needs to change a shared file, keep the change additive (a new field with a default, a new method with a default body, a new catalog entry) and call it out in the PR description.

## Decisions baked into the contracts

- **Two encrypted DB files, one key.** #3 owns `history` (messages + contact notes); #6 owns `feedback` (feedback log). Both open with SQLCipher using `DatabaseKeyProvider` (#3). This keeps #3 and #6 out of each other's Room schema. Until #3 merges, the key provider throws, so #6 keeps AppGraph on `InMemoryFeedbackLog` and tests its DB with a fake key provider.
- **The generator owns the cost check.** `ReplyGenerator` returns `Failure.NoApiKey` / `Failure.LimitReached` itself, so the panel only renders results.
- **The assembler merges first.** `SuggestionRequestAssembler.assemble` merges the freshly parsed chat into history before reading it, so the prompt includes what is on screen even if passive capture has not run yet.
- **Purges.** #3 purges history and #6 purges the feedback log, each with `retentionDays` from settings, on service connect and at most once a day (no WorkManager).
- **Delete one chat's history** is a panel action (#5), calling `HistoryRepository.deleteChat`.
- **Hidden debug screen:** long-press the title on the settings screen (#6 keeps that gesture when building the real screen).
- **Accessibility config** (`res/xml/accessibility_service_config.xml`): all packages, view ids, interactive windows, not-important views included, events: window state/content/windows changed, view focused, view text changed. Components filter by package.
