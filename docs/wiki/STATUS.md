# CoS status checkpoint

Overwritten at every checkpoint. The history lives in [plan](plan.md) and [DECISIONS](../DECISIONS.md).

## 2026-11-24 (later): Bubble work (spec #53) done. Waiting on Parag's device results.

**Merged.** Everything in the earlier 2026-11-24 checkpoint (see [plan](plan.md)), plus:
- **PR #57 (#54, drag, `a605d4f`)** after one fix round (`reviews/pr57-r1.md`: S1 back-gesture exclusion, N1–N4; gate 1237 tests). CoS read the fix commits and skipped round 2.
- **PR #60 (wall-clock guard, `bed8f9b`)**: `NoWallClockInTestsTest` and a CLAUDE.md testing rule. CoS read the diff and ran the guard against `main` before merging.
- **PR #61 (#56, snooze, `99ffbcc`)** after one fix round (`reviews/pr61-r1.md`: S1 sleep-delayed expiry → 60 s chunked waits; S2 one shake counted as two → 250 ms stillness and 1.2 s end-to-start window; S3 zone read each time; N1–N8; gate 1362 tests). CoS decisions: shake ends a snooze only while unlocked; TalkBack "Snooze…" opens the picker via the drop path.
- Spec #53 closed. `latest-debug` is versionCode 118 from `99ffbcc`.

**In flight.** Nothing.

**Waiting on Parag.** S23 results for DEVICE_CHECKLIST section 16 (16.A1–A9, 16.B1–B7, 16.C1–C17) plus the earlier 13–15 (dates, prompt v2, X).

**Follow-ups (not scheduled):** #51.

**Next for the CoS:** act on device results when they arrive.

**Open risks (as of 2026-11-24)**
- One UI may not honour gesture exclusion for an accessibility overlay (16.A9).
- Background accelerometer delivery and shake thresholds (16.C6, 16.C9); slow shakes along gravity may count twice.
- TalkBack focus moving into the snooze picker (16.C13).
- Bubble visibility at 40 % opacity over real chats (16.B2).
- Date-separator formats and X view ids are guesses until S23 dumps (13–15).
- History merge's identical-short-message limits (DECISIONS, about 0.05–0.25 %).

**Briefs and reviews.** In `/root/.t3/cos/replyly/`: earlier briefs (`x-*.md`, `v2-*.md`, `issue-*.md`, `fu38-*.md`, `fu46-*.md`), bubble briefs `bubble-common.md` and `bubble-decisions.md`; reviews under `reviews/` up to `pr57-r1.md`, `pr58-r1.md` and `pr61-r1.md`.

**How the CoS works.**
- **GitHub token:** `GH_TOKEN="$(cat /root/.config/replyly/gh_token)" gh … -R paragrudani1/replyly`. Never print it. The T3 PR and issue tools use another login and fail for this repo.
- **Agents:** `delegate_task` with `isolation: worktree`, Claude Opus 5.5, effort high (never higher). The common rules are in `ticket-common.md`, `x-common.md`, `v2-common.md` and `review-common.md` under `/root/.t3/cos/replyly/`. A delegate that must continue an existing branch works in that branch's worktree (named in its brief), since a branch can't be checked out twice.
- **Merging:** merge commits only, never `--delete-branch`, and only on green CI.
- **Reviews:** a fresh delegate per round (never a follow-up message). Small findings the CoS fixes itself in the branch's worktree; bigger ones go to a fix delegate.
- **Checkpoints:** after each merge or phase end, update this file (docs PR, merge on green) and post a short status in the thread. Don't stop at checkpoints; Claude compacts from outside while the CoS is idle. After a compaction, re-read this file and continue.
