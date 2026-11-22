# CoS status checkpoint

Overwritten at every checkpoint. The history lives in [plan](plan.md) and [DECISIONS](../DECISIONS.md).

## 2026-11-22: #27 under round-2 review

**Merged.** v1 (#8–#16), polish (#19, #21–#24), profiles (#20), docs (#25), and #29 (signing-key rotation).
- `main` is at `1254a71` plus the wiki docs PRs #31–#33.
- `latest-debug` is versionCode 55, with certificate SHA-256 `2a5421c6…2737d6` (the new key).
- Parag must uninstall the old app once first (DEVICE_CHECKLIST 2.0).

**Repo.** Public since 2026-11-22. The pre-public audit was clean; details are in DECISIONS.
- CI runs again.
- Signing secrets: `REPLYLY_SIGNING_KEYSTORE_B64` and `REPLYLY_SIGNING_PASSWORD`.
- The key is stored root-only at `/root/.config/replyly/signing/`. Parag should keep an offline backup.

**In flight: issue #26, prompt v2 (2 PRs).** Issue: https://github.com/paragrudani1/replyly/issues/26
- **#27 (`v2-dates`): message dates in the parsers and history.**
  - Review r1 (`/root/.t3/cos/replyly/reviews/pr27-r1.md`) blocked it on B1: the merge wrote an exact date onto the wrong twin row.
  - The CoS also put S1 (plausibility check), S2 (unreadable separator) and S3 (cross-day edit overwrite) in this round.
  - **Fix round 1 is done:** head `2522486`, CI run 45052430977 green, local gate 905 tests. The resume agent's report, in brief:
    - B1, S1 (`ChatAssembler.checkDates`), S2, S3 (`StoredRow.date`) and the nits are fixed.
    - 0 invariant-1 failures at 20,000 seeds in every dated config.
    - Additive contract changes: `StoredRow.date`/`dateIsApprox`, `HistoryMerge.plan(today=)`, `ChatAssembler.dateNote(dropped=)`.
    - Not merged with `main`, because there are no conflicts and PR CI builds the merge anyway.
    - Decisions to record: an edit of a message from another day is stored as new; fixtures are captured at 23:45 IST.
  - **Open subagent: "Review PR #27 round 2"** (fresh reviewer).
    - Brief: `/root/.t3/cos/replyly/v2-pr1-review2-brief.md`.
    - Writes `/root/.t3/cos/replyly/reviews/pr27-r2.md`.
    - taskId `node:delegated-task:command%3Amcp%3A22202a9d-777c-4d01-90e1-4d7877bf4760%3Adelegate-task%3Af1e44d43-e8b4-4f23-8bd7-297d5378c29b`
    - thread `thread:delegated-task:command%3Amcp%3A22202a9d-777c-4d01-90e1-4d7877bf4760%3Adelegate-task%3Af1e44d43-e8b4-4f23-8bd7-297d5378c29b`
    - If it has finished, read its result with `task_status`.
  - **Next:**
    - If r2 has no blockers or should-fixes, merge #27 once CI is green.
    - Otherwise, write a fix brief (round 2) for an agent working in worktree `t3-bdc5acab-c3bf153cc79d182f`.
- **#28 (`v2-prompt`, head `abb5c50`, CI green): prompt v2.**
  - The r1 fixes are done.
  - **Next, after #27 merges:**
    1. Rebase onto `main`.
    2. Wire the assembler to the real date fields and keep one `SchemaAssets`.
    3. Run a combined re-review.
    4. Post `PromptBuilder.SYSTEM_PROMPT` verbatim in the thread.
    5. Merge on green CI.

**Queued: issue #30** (X reply and quote posts). Starts after #28 merges. Plan: parser and detection first; then prompt mode, settings and counter; 1–2 PRs.

**Briefs and reviews.** In `/root/.t3/cos/replyly/`: `v2-*.md`, `issue-*.md`, and `reviews/pr27-r1.md` and `pr28-r1.md`.

**Open risks**
- Every date-separator format is a guess until there are S23 dumps (DEVICE_CHECKLIST 13).
- The history merge's identical-short-message limit is documented: about 0.05–0.25% on `main`.
- Parag's commit email is public in the git history; he accepted this by asking for the repo to go public.

**Decisions not yet in DECISIONS.md** (record them with the #26 PRs):
- Prompt v2: "Names people call me" setting; caching only on api.anthropic.com; static system prompt.
- PR 1: dates are stored as epoch days.
- PR 2: its 14 decisions are in the #28 PR body.
- Rule: no merge without green CI. The local-gate fallback is withdrawn, per Parag.

**Cleanup after #28 merges.** Remove these stale worktrees:
- `t3-632194cb…`, `t3-66c2f1ae…`, `t3-b1162e5a…`, `t3-4e030bc2…` (its apparent changes are only the branch having moved under it);
- `/root/local-dev/tmp-replyly-base`;
- `/root/local-dev/replyly-audit` (scan output only).

**How the CoS works.**
- **GitHub token:** `GH_TOKEN="$(cat /root/.config/replyly/gh_token)" gh … -R paragrudani1/replyly`. Never print it. The T3 PR and issue tools use another login and fail for this repo.
- **Agents:** `delegate_task` with `isolation: worktree`, Claude Opus 5.5, effort high (never higher). The common rules are in `ticket-common.md`, `v2-common.md` and `review-common.md` under `/root/.t3/cos/replyly/`.
- **Merging:** merge commits only, never `--delete-branch`, and only on green CI.
- **Checkpoints:** after each merge, update this file, post a short status in the thread, and end the turn with `CHECKPOINT READY: <phase>`.
