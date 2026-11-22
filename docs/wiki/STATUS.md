# CoS status checkpoint

Overwritten at every checkpoint. The history lives in [plan](plan.md) and [DECISIONS](../DECISIONS.md).

## 2026-11-22: after #29 (signing key), before #27 merges

**Merged.** v1 (#8–#16), polish (#19, #21–#24), profiles (#20), docs (#25), and #29 (signing-key rotation).
- `main` is at `1254a71`.
- `latest-debug` is versionCode 55, with certificate SHA-256 `2a5421c6…2737d6` (the new key).
- Parag must uninstall the old app once first (DEVICE_CHECKLIST 2.0).

**Repo.** Public since 2026-11-22. The pre-public audit was clean; details are in DECISIONS.
- CI runs again.
- Signing secrets: `REPLYLY_SIGNING_KEYSTORE_B64` and `REPLYLY_SIGNING_PASSWORD`.
- The key is stored root-only at `/root/.config/replyly/signing/`. Parag should keep an offline backup.

**In flight: issue #26, prompt v2 (2 PRs).**
- **#27 (`v2-dates`): message dates in the parsers and history.**
  - Review r1 (`/root/.t3/cos/replyly/reviews/pr27-r1.md`) blocked it on B1: the merge wrote an exact date onto the wrong twin row.
  - The CoS also put S1 (plausibility check), S2 (unreadable separator) and S3 (cross-day edit overwrite) in this round.
  - The fix agent died in the API outage. A resume agent continues its uncommitted work in worktree `t3-bdc5acab-c3bf153cc79d182f`; brief `/root/.t3/cos/replyly/v2-pr1-fix1-resume.md`.
  - **Next:** fresh re-review, then merge on green CI.
- **#28 (`v2-prompt`, head `abb5c50`, CI green): prompt v2.**
  - The r1 fixes are done.
  - **Next, after #27 merges:**
    1. Rebase onto `main`.
    2. Wire the assembler to the real date fields and keep one `SchemaAssets`.
    3. Run a combined re-review.
    4. Post `PromptBuilder.SYSTEM_PROMPT` verbatim in the thread.
    5. Merge on green CI.

**Queued: issue #30 (X reply and quote posts).** Starts after #28 merges. Plan: parser and detection first; then prompt mode, settings and counter; 1–2 PRs.

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
