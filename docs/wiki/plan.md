# Plan and PR log

## Waves (planned 2026-11-21)
- Wave 0: skeleton (done 2026-11-21, branch `skeleton` at `7b291a1`, rebased onto the docs commits; not yet pushed because the GitHub repo did not exist). Contracts and ownership map: `docs/ARCHITECTURE.md` at that commit.
- Wave 1 (parallel, started 2026-11-21 on local branches off `7b291a1`): #2 parser and fixtures, #3 encrypted history, #4 LLM client and prompt, #6 settings, logs and cost guard. Common rules for ticket agents: `/root/.t3/cos/replyly/ticket-common.md`.
- Wave 2 (parallel): #1 overlay and button, #5 panel UI, #7 onboarding.
- Wave 3: wire-up check (e.g. #6 swaps `feedbackLog` to the encrypted DB once #3 lands), `docs/DEVICE_CHECKLIST.md`, code review.

## PR log
(none yet: blocked on the GitHub repo)

## Wave 3 follow-ups collected (2026-11-21)
- From #4 (`ticket-4-llm@6d81602`): Settings needs a "3 parallel calls" switch for `useParallelCalls` (#6 was briefed before that field existed). The manifest may need a `<queries>` entry so `PackageManager` can label generic apps. Consider OpenRouter's `reasoning` effort if `~openai/gpt-mini-latest` is slow. Remove `Stub*` LLM classes once #5 lands.
- From #6 (`ticket-6-settings@b1fa11a`): once #3 has merged, bind `feedbackLog = RoomFeedbackLog(FeedbackDatabase.open(app, databaseKeyProvider), clock, settingsRepository.settings.map { it.retentionDays })`. Add the `useParallelCalls` switch to the settings screen. No Compose ui-test dependency yet.
