# CoS status checkpoint

Overwritten at every checkpoint. The history lives in [plan](plan.md) and [DECISIONS](../DECISIONS.md).

## 2026-11-22: #27 merged; #28 under combined review

**Merged.** v1 (#8–#16), polish (#19, #21–#24), profiles (#20), docs (#25), #29 (signing-key rotation), **#27 (message dates, `624939c`)**, and the wiki docs PRs #31–#37.
- `latest-debug` is versionCode 69 (built from `624939c`), signed with the new key (certificate SHA-256 `2a5421c6…2737d6`).
- Parag must uninstall the old app once first (DEVICE_CHECKLIST 2.0).

**Repo.** Public since 2026-11-22. The pre-public audit was clean; details are in DECISIONS.
- CI runs again.
- Signing secrets: `REPLYLY_SIGNING_KEYSTORE_B64` and `REPLYLY_SIGNING_PASSWORD`.
- The key is stored root-only at `/root/.config/replyly/signing/`. Parag should keep an offline backup.

**In flight: issue #26, prompt v2.** Issue: https://github.com/paragrudani1/replyly/issues/26
- **#27 is done.** 3 review rounds (`/root/.t3/cos/replyly/reviews/pr27-r1.md`, `-r2.md`, `-r3.md`). The CoS applied r3's two should-fixes itself (the reviewer had tested both), plus pinned seeds 500331, 516434 and 617554. Gate 919 tests; 0 invariant-1 failures at fresh seeds 700000–709999. DECISIONS has the PR 1 entries and accepted limits.
- **#28 (`v2-prompt`): prompt v2.**
  - **Rebase done:** head `ca48e1a`, on `main` with #27; CI run 45078197175 green; local gate 1013 tests.
    - Only docs conflicted (README, DEVICE_CHECKLIST 13 then 14, ARCHITECTURE); all kept both sides.
    - Wiring: history `date`/`dateIsApprox` from the stored row; on-screen-only `message.date`, never approx; `PromptMessage.repliesToMe` derived from `ChatMessage`. The `firstSeenAt` stand-in is gone; the bubble-time fallback for approx dates stays.
    - One `SchemaAssets` (`app/src/test/.../testing/SchemaAssets.kt`). No shared-contract change.
  - **Open subagent: "Review PR #28 round 2"** (combined: r1 fixes + wiring + the final system prompt).
    - Brief: `/root/.t3/cos/replyly/v2-pr2-review2-brief.md`. Writes `/root/.t3/cos/replyly/reviews/pr28-r2.md`.
    - taskId `node:delegated-task:command%3Amcp%3A3b3b2c71-2b5d-47d7-bcb5-344045843096%3Adelegate-task%3Afb4a12a1-61ea-4bc4-b9ac-88c678fc9777`
    - thread `thread:delegated-task:command%3Amcp%3A3b3b2c71-2b5d-47d7-bcb5-344045843096%3Adelegate-task%3Afb4a12a1-61ea-4bc4-b9ac-88c678fc9777`
    - If it has finished, read its result with `task_status`.
  - **Next:**
    1. Fix the findings: small ones yourself in worktree `t3-f24e6775-b855e9cd9242b6bb`, bigger ones by a fix delegate.
    2. Post `PromptBuilder.SYSTEM_PROMPT` verbatim in the thread (after any prompt fixes).
    3. Merge on green CI, update this file, post a short status. Then #30.

**Queued: issue #30** (X reply and quote posts). Starts after #28 merges. Plan: parser and detection first; then prompt mode, settings and counter; 1–2 PRs.

**Briefs and reviews.** In `/root/.t3/cos/replyly/`: `v2-*.md`, `issue-*.md`, and `reviews/pr27-r1.md` to `pr27-r3.md`, `pr28-r1.md` and `pr28-r2.md`.

**Open risks**
- Every date-separator format is a guess until there are S23 dumps (DEVICE_CHECKLIST 13). 13.10 (chat open across midnight) decides whether the merge needs a rule for stale screens.
- The history merge's identical-short-message limit is documented: about 0.05–0.25% on `main`.
- Parag's commit email is public in the git history; he accepted this by asking for the repo to go public.

**Decisions not yet in DECISIONS.md** (record them with the #26 PRs):
- Prompt v2: "Names people call me" setting; caching only on api.anthropic.com; static system prompt.
- PR 2: its 14 decisions are in the #28 PR body.
- Rule: no merge without green CI. The local-gate fallback is withdrawn, per Parag.

**Cleanup after #28 merges.** Remove these stale worktrees:
- `t3-632194cb…`, `t3-66c2f1ae…`, `t3-b1162e5a…`, `t3-4e030bc2…` (its apparent changes are only the branch having moved under it);
- `/root/local-dev/tmp-replyly-base`;
- `/root/local-dev/replyly-audit` (scan output only);
- `t3-bdc5acab…` and `t3-632194cb…` (both have the merged `v2-dates` checked out); scratch files `/tmp/replyly-fuzz`, `/tmp/replyly-s1-fuzz`, `/tmp/replyly-r3-*`, `/tmp/pr27-body.md`.

**How the CoS works.**
- **GitHub token:** `GH_TOKEN="$(cat /root/.config/replyly/gh_token)" gh … -R paragrudani1/replyly`. Never print it. The T3 PR and issue tools use another login and fail for this repo.
- **Agents:** `delegate_task` with `isolation: worktree`, Claude Opus 5.5, effort high (never higher). The common rules are in `ticket-common.md`, `v2-common.md` and `review-common.md` under `/root/.t3/cos/replyly/`.
- **Merging:** merge commits only, never `--delete-branch`, and only on green CI.
- **Checkpoints:** after each merge or phase end, update this file (docs PR, merge on green) and post a short status in the thread. Don't stop at checkpoints; Claude compacts from outside while the CoS is idle. After a compaction, re-read this file and continue.
