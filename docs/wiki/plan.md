# Plan and PR log

## Waves (planned 2026-11-21)
- Wave 0: skeleton (done 2026-11-21, branch `skeleton` at `7b291a1`, rebased onto the docs commits; not yet pushed because the GitHub repo did not exist). Contracts and ownership map: `docs/ARCHITECTURE.md` at that commit.
- Wave 1 (parallel, started 2026-11-21 on local branches off `7b291a1`): #2 parser and fixtures, #3 encrypted history, #4 LLM client and prompt, #6 settings, logs and cost guard. Common rules for ticket agents: `/root/.t3/cos/replyly/ticket-common.md`.
  - #4: review done, fixes at `ticket-4-llm@21fdca5` (72 tests). Ready to PR. #6: review done, fixes at `ticket-6-settings@c91b0c0` (79 tests). Ready to PR. #3: review blocked (false edits in merge); fixes running. Re-review merge after.
  - #5 fixes `ticket-5-panel@1c3b883` (101 tests), #7 fixes `ticket-7-onboarding@9f458f9` (46 tests): ready. Both add test deps to the catalog (expect a trivial conflict).
  - #1 fixes `ticket-1-overlay@f9a53d5` (114 tests): ready. S23 must confirm the chat window stays in getWindows() under the full-screen panel (checklist 10.6), else the panel closes at once.
- Wave 2 (parallel): #1 overlay and button, #5 panel UI, #7 onboarding.
- Wave 3: wire-up check (e.g. #6 swaps `feedbackLog` to the encrypted DB once #3 lands), `docs/DEVICE_CHECKLIST.md`, code review.

## PR log
(none yet: blocked on the GitHub repo)

## Wave 3 follow-ups collected (2026-11-21)
- From #4 (`ticket-4-llm@6d81602`): Settings needs a "3 parallel calls" switch for `useParallelCalls` (#6 was briefed before that field existed). The manifest may need a `<queries>` entry so `PackageManager` can label generic apps. Consider OpenRouter's `reasoning` effort if `~openai/gpt-mini-latest` is slow. Remove `Stub*` LLM classes once #5 lands.
- From #6 (`ticket-6-settings@b1fa11a`): once #3 has merged, bind `feedbackLog = RoomFeedbackLog(FeedbackDatabase.open(app, databaseKeyProvider), clock, settingsRepository.settings.map { it.retentionDays })`. Add the `useParallelCalls` switch to the settings screen. No Compose ui-test dependency yet.
- From #4 review: ApiKeyStore (#6) should trim the key on `set`.
- From #6 review: the LLM client (#4) should itself refuse a non-https endpoint.
- From #3 (`ticket-3-history@ef09e1c`): the #4 assembler should catch `DatabaseKeyUnavailableException` (fixed-wording failure). FLAG_SECURE detection belongs in the host (#1); capture should then pass it to the policy. Capture stores nothing until #2's parsers merge.
- From #7: reviewed 2026-11-21; start-destination and back-stack fixes running (see DECISIONS #7 review).
- From #2: review fixes done at `ticket-2-parser@600c506` (98 tests, 17 adversarial trees committed). Ready. `secureWindow` not set on the dump path until integration.

## GitHub (2026-11-21)
- Repo https://github.com/paragrudani1/replyly created; issues #1–#7 = tickets 1–7; PR #8 = skeleton.
- From #5 (`ticket-5-panel@3f2517b`, based on `ticket-4-llm@21fdca5`): #1's overlay must keep the panel window focusable (no FLAG_NOT_FOCUSABLE / FLAG_ALT_FOCUSABLE_IM), insert into the input node remembered at the button tap, NOT hide the panel when the chat input loses focus while the panel is open, and compose the panel once per open (recomposing re-calls the LLM). Check these in the #1 review. Height cap 340 dp and ADJUST_PAN are guesses for the device checklist.
- From #1 (`ticket-1-overlay@cc70910`): #3's `PassiveCaptureComponent` should call `host.captureActiveWindowChecked()` (FLAG_SECURE) instead of `captureActiveWindow()`; do it at integration. Remove unused stubs `MinimalSensitiveScreenPolicy`, `UnavailableReplyInserter`, `Stub*` LLM after all merge. After installing over an older build the service must be toggled once to gain `canTakeScreenshot` (device checklist).
- 2026-11-21: #3 merge blocked twice (first review: false edits; re-review: gap + scroll-up overwrites). Third round adds gap segments and a committed fuzzer. Lesson: for diff-like merges, demand a property test against a simulated truth from the start, not hand-picked scenarios. Retro candidate.
- From #1 review: at integration switch #3 `PassiveCaptureComponent` and #2 `DumpCapturer` to `captureActiveWindowChecked()` (passive capture may use a longer `maxAgeMillis`); update #5's `ComposeReplyPanel` KDoc and checklist to the modal full-screen host.

## PR log (2026-11-21)
- #8 skeleton (base main). Ticket PRs are stacked on `skeleton` so their diffs stay clean, and GitHub retargets them when `skeleton` merges and is deleted: #9 = ticket 4, #10 = ticket 6, #11 = ticket 7, #12 = ticket 2, #13 = ticket 5 (stacked on `ticket-4-llm`), #14 = ticket 1. Ticket 3 still in its third fix round.
- Merge order planned: #8, #9, #10, #11, #12, #13, #14, ticket 3, then a wiring PR (follow-ups listed above) and a `docs/DEVICE_CHECKLIST.md` consolidation.
- Integration dry-run (local `integration-dryrun`, rerere enabled) started to pre-resolve conflicts.
- Blocked: GitHub Actions created 0 runs for any PR; waiting on Parag to enable Actions.
- 2026-11-21: Integration dry-run (`integration-dryrun@5c80d34`, worktree t3-d82fbab1…) merged tickets 4, 6, 7, 2, 5, 1 onto skeleton: only 2 small conflicts (`app/build.gradle.kts` test deps; AppGraph imports), rerere recorded, gate 386 tests green. Extra wiring items found: `useParallelCalls` not persisted or shown in Settings; `DumpCapturer` must use the checked capture and drop its duplicate blocklist checks; delete stubs nobody binds; feedback purge also on service connect; panel "Open settings" should open the Settings route directly; `<queries>` for app labels.
- 2026-11-21 retro on ticket 3 (thread "Retro: Replyly #3 history sessions"): adopted (a) property tests required for large-input-space logic in `ticket-common.md`, (b) reviewers run extra seeds plus one unmodelled generator (`review-common.md`), (c) agents never stop Gradle daemons (a #6 agent's stop likely killed #3's build), (d) review reports saved under `/root/.t3/cos/replyly/reviews/` and passed to fixers, (e) `scripts/check.sh` retries once in a fresh JVM only on the KSP `PluginEnabler` init error (to land after the wiring PR), (f) AGENTS.md line about `HistoryMergePropertyTest`. Not adopted: editing the global `/root/local-dev/AGENTS.md` (Parag's file, other projects); suggested to him instead.
- 2026-11-21: Wiring branch `wiring@4c93483` (worktree t3-d82fbab1…): tickets + #3 (round-3 tip) + wiring fixes a–j + `docs/DEVICE_CHECKLIST.md` (per-ticket files deleted); 523 tests green; debug APK 46 MB. New additive failures `StorageLocked`, `InsecureEndpoint`; `FeedbackLog.purgeExpired()`. It also fixed the DataStore test helper differently from the #6 fix (`c8701a3`): take #6's version when merging. Still open for the wiring PR: settings/feedback screens can crash on a transient Keystore error; `EncryptedDatabases` should use `FeedbackDatabase.NAME`; retro items (check.sh KSP retry, AGENTS.md property-test line).
- 2026-11-21: Merger landed #9 (t4), #11 (t7), #12 (t2), #13 (t5, merge commit `3721a5a` via rerere), #14 (t1, `e2d12d2` via rerere), #10 (t6) on green CI. `main` at `e1aa2a7`; push run 44917671459 green; rolling release https://github.com/paragrudani1/replyly/releases/tag/latest-debug has `replyly-debug.apk` (47.5 MB) built from `e1aa2a7`. That APK lacks ticket 3 (in-memory history) and the wiring fixes. Remaining: #15 (t3 round 4), then the wiring PR.
