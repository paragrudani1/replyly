# Plan and PR log

## Waves (planned 2026-11-21)
- Wave 0: skeleton (done 2026-11-21, branch `skeleton` at `7b291a1`, rebased onto the docs commits; not yet pushed because the GitHub repo did not exist). Contracts and ownership map: `docs/ARCHITECTURE.md` at that commit.
- Wave 1 (parallel, started 2026-11-21 on local branches off `7b291a1`): #2 parser and fixtures, #3 encrypted history, #4 LLM client and prompt, #6 settings, logs and cost guard. Common rules for ticket agents: `/root/.t3/cos/replyly/ticket-common.md`.
  - #4: review done, fixes at `ticket-4-llm@21fdca5` (72 tests). Ready to PR. #6: review done, fixes at `ticket-6-settings@c91b0c0` (79 tests). Ready to PR. #3: review blocked (false edits in merge); fixes running. Re-review merge after.
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
- From #2: reviewed 2026-11-21 (7 parser breaks on adversarial trees, scramble leaks); fixes running.

## GitHub (2026-11-21)
- Repo https://github.com/paragrudani1/replyly created; issues #1–#7 = tickets 1–7; PR #8 = skeleton.
- From #5 (`ticket-5-panel@3f2517b`, based on `ticket-4-llm@21fdca5`): #1's overlay must keep the panel window focusable (no FLAG_NOT_FOCUSABLE / FLAG_ALT_FOCUSABLE_IM), insert into the input node remembered at the button tap, NOT hide the panel when the chat input loses focus while the panel is open, and compose the panel once per open (recomposing re-calls the LLM). Check these in the #1 review. Height cap 340 dp and ADJUST_PAN are guesses for the device checklist.
- From #1 (`ticket-1-overlay@cc70910`): #3's `PassiveCaptureComponent` should call `host.captureActiveWindowChecked()` (FLAG_SECURE) instead of `captureActiveWindow()`; do it at integration. Remove unused stubs `MinimalSensitiveScreenPolicy`, `UnavailableReplyInserter`, `Stub*` LLM after all merge. After installing over an older build the service must be toggled once to gain `canTakeScreenshot` (device checklist).
- 2026-11-21: #3 merge blocked twice (first review: false edits; re-review: gap + scroll-up overwrites). Third round adds gap segments and a committed fuzzer. Lesson: for diff-like merges, demand a property test against a simulated truth from the start, not hand-picked scenarios. Retro candidate.
