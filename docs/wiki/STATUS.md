# CoS status checkpoint

Overwritten at every checkpoint. The history lives in [plan](plan.md) and [DECISIONS](../DECISIONS.md).

## 2026-11-24: Bubble work (spec #53) in flight. #55 merged; #54 in fixes; #56 started early.

**Request (2026-11-24).** Parag asked for a Wispr-Flow-style bubble: drag anywhere, bubble settings, snooze. His answers: snap to edge; settings for size, opacity, shrink when idle, show/reset; snooze with a choice of durations, shake to end and a home card; usual delivery flow. CoS decisions (2D drag with left/right edge snap, dismiss target opens a snooze picker with 10 min / 1 h / until 6 AM / until turned on, snooze survives restarts, shake only listens while snoozed, Settings "Bubble" section): `/root/.t3/cos/replyly/bubble-decisions.md`. Spec issue #53; tickets #54 (A, drag), #55 (B, settings), #56 (C, snooze; blocked by #54).

**Merged.** Everything listed in the 2026-11-23 checkpoint (see [plan](plan.md)), plus **PR #58 (#55, Bubble settings, `77af568`)**. Review `reviews/pr58-r1.md`; the CoS fixed S2 (no shadow below 100 % opacity) and N7 (device checks) in `0498c67`. Gate 1186 tests. #58 also fixed a test that read the wall-clock date and broke `main` on 2026-11-24.

**In flight.**
- PR #57 (#54, drag): review `reviews/pr57-r1.md` says merge after should-fixes S1 (back-gesture exclusion) and S2 (rebase); a fix delegate is rebasing and fixing it.
- #56 (snooze): started early on `origin/main`, building the parts that don't depend on the drag first.
- Environment PR (branch `env-no-wallclock-tests`): a guard so tests never read the wall clock.

**Still waiting on Parag.** S23 results for DEVICE_CHECKLIST 13–15 (dates, prompt v2, X) and now section 16 (Bubble #53).

**Follow-ups (not scheduled):** #51.

**Next for the CoS:** review and merge #57 after its fixes, then rebase #56 on it, review, merge; merge the wall-clock guard PR on green.

**Open risks**
- Date-separator formats and X view ids are guesses until S23 dumps (13–15).
- Snap feel, dismiss-target placement over Gboard on One UI, shake sensitivity and opacity over real chats are device-only (section 16).
- History merge's identical-short-message limits (DECISIONS, about 0.05–0.25 %).

**Briefs and reviews.** In `/root/.t3/cos/replyly/`: earlier briefs (`x-*.md`, `v2-*.md`, `issue-*.md`, `fu38-*.md`, `fu46-*.md`), bubble briefs `bubble-common.md` and `bubble-decisions.md`; reviews under `reviews/` up to `pr57-r1.md` and `pr58-r1.md`.

**How the CoS works.**
- **GitHub token:** `GH_TOKEN="$(cat /root/.config/replyly/gh_token)" gh … -R paragrudani1/replyly`. Never print it. The T3 PR and issue tools use another login and fail for this repo.
- **Agents:** `delegate_task` with `isolation: worktree`, Claude Opus 5.5, effort high (never higher). The common rules are in `ticket-common.md`, `x-common.md`, `v2-common.md` and `review-common.md` under `/root/.t3/cos/replyly/`. A delegate that must continue an existing branch works in that branch's worktree (named in its brief), since a branch can't be checked out twice.
- **Merging:** merge commits only, never `--delete-branch`, and only on green CI.
- **Reviews:** a fresh delegate per round (never a follow-up message). Small findings the CoS fixes itself in the branch's worktree; bigger ones go to a fix delegate.
- **Checkpoints:** after each merge or phase end, update this file (docs PR, merge on green) and post a short status in the thread. Don't stop at checkpoints; Claude compacts from outside while the CoS is idle. After a compaction, re-read this file and continue.
