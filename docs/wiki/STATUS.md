# CoS status checkpoint

Overwritten at every checkpoint. The history lives in [plan](plan.md) and [DECISIONS](../DECISIONS.md).

## 2026-11-23: #30 PR 1 merged (#40); #30 PR 2 (X prompt mode) in progress

**Merged.** v1 (#8–#16), polish (#19, #21–#24), profiles (#20), docs (#25), #29 (signing-key rotation), **#27 (message dates, `624939c`)**, **#28 (prompt v2, `ecb0b65`)**, **#40 (#30 PR 1, X detection and parser, `bee7cd9`)**, and the wiki docs PRs #31–#39, #41, #42 (and this one). Issue #26 is closed.
- `latest-debug` is versionCode 85 (built from `bee7cd9`; X detection is in it, X replies still use the chat prompt until PR 2), signed with the new key (certificate SHA-256 `2a5421c6…2737d6`).
- Parag must uninstall the old app once first (DEVICE_CHECKLIST 2.0). `feedback.db` migrates to v2 with #28: no downgrade to an older APK after that.
- The final system prompt was posted verbatim in the thread (from `bc75cd1`).

**Repo.** Public since 2026-11-22. CI runs. Signing secrets `REPLYLY_SIGNING_KEYSTORE_B64` and `REPLYLY_SIGNING_PASSWORD`; the key is root-only at `/root/.config/replyly/signing/` (Parag should keep an offline backup).

**How #27 and #28 ended** (details in DECISIONS, the PR bodies and `/root/.t3/cos/replyly/reviews/`):
- #27: 3 review rounds; the CoS applied r3's two tested should-fixes itself. Gate 919 tests; 0 overwrites at fresh seeds 700000–709999.
- #28: rebased onto #27 and wired to the real date fields; round-2 review's R1 (1:1 targets oldest first) and R2 (approx day narrowed by bubble time) fixed by the CoS. Gate 1014 tests. DECISIONS has the #26 PR 2 entries.

**In flight: issue #30, X reply and quote posts.** Issue: https://github.com/paragrudani1/replyly/issues/30. Common rules: `/root/.t3/cos/replyly/x-common.md`.
- **PR 1: [#40](https://github.com/paragrudani1/replyly/pull/40), merged `bee7cd9`.** Detection (reply/quote composers and DMs only, gated on the focused field's view id and English hint), X parser in `parser/x/` (pinned `XContext`/`XPost` as `ParsedChat.x`), "My X handle", no history for X, dump/scramble, interim mapping to `ParsedChat.messages`. Review r1 (`reviews/pr40-r1.md`): S1–S3 and N1–N4 fixed by a fix delegate (`x-pr1-fix1-brief.md`); the CoS read the fix diff and skipped a round 2. Gate 1051 tests; 25,000 fresh property seeds clean. DECISIONS has 11 `#30 PR 1` entries; DEVICE_CHECKLIST 15.0–15.15 (incl. 15.4a).
- **PR 2 (`x-prompt`): X system-prompt section, 280/500 length (X weighting, trim at a sentence or word boundary, no regenerate: invariant 4), per-card counter, "My X voice" and "X post length", chips, screenshots, DEVICE_CHECKLIST 15 rest. `Closes #30`.**
  - **Open subagent: "#30 PR 2: X prompt mode, length, panel"**, fresh worktree. Brief `/root/.t3/cos/replyly/x-pr2-brief.md` (it folds in review N5).
    - taskId `node:delegated-task:command%3Amcp%3A0b3cb7ad-8b09-4a3b-81e2-44b90a1dab9f%3Adelegate-task%3Ac2410e69-d84c-4094-90fc-c29600686a6d`
    - If it has finished, read the PR body (`gh pr list -R paragrudani1/replyly --head x-prompt`).
  - **Next:** review (new delegate, brief like `x-pr1-review-brief.md`; focus: the X system prompt verbatim, length counting and trimming, invariant 4, untrusted input), fix, merge on green, post the final X system prompt verbatim to Parag, status post, close-out of #30.

**Follow-ups filed:** #38 (Nothing-to-reply subtitle wrong when I never wrote or their media is last).

**Open risks**
- Every date-separator format and every X view id is a guess until there are S23 dumps (DEVICE_CHECKLIST 13, 14, 15). 13.10 (chat open across midnight) decides whether the merge needs a rule for stale screens.
- The history merge's identical-short-message limits are documented in DECISIONS (about 0.05–0.25%).
- Parag's commit email is public in the git history; he accepted this by asking for the repo to go public.

**Cleanup.** Done on 2026-11-23: the stale worktrees, `/root/local-dev/tmp-replyly-base`, `/root/local-dev/replyly-audit` and the `/tmp` scratch files are removed. Remaining worktrees: the CoS's (`t3-a8f2345a…`), PR 1's (`t3-a25cf273…`, branch merged; remove after #30 closes) and PR 2's agent's.

**Briefs and reviews.** In `/root/.t3/cos/replyly/`: `x-*.md`, `v2-*.md`, `issue-*.md`, and `reviews/pr27-r1.md` to `-r3.md`, `pr28-r1.md`, `pr28-r2.md`, `pr40-r1.md`.

**How the CoS works.**
- **GitHub token:** `GH_TOKEN="$(cat /root/.config/replyly/gh_token)" gh … -R paragrudani1/replyly`. Never print it. The T3 PR and issue tools use another login and fail for this repo.
- **Agents:** `delegate_task` with `isolation: worktree`, Claude Opus 5.5, effort high (never higher). The common rules are in `ticket-common.md`, `x-common.md` (current), `v2-common.md` and `review-common.md` under `/root/.t3/cos/replyly/`. A delegate that must continue an existing branch works in that branch's worktree (named in its brief), since a branch can't be checked out twice.
- **Merging:** merge commits only, never `--delete-branch`, and only on green CI.
- **Reviews:** a fresh delegate per round (never a follow-up message). Small findings the CoS fixes itself in the branch's worktree; bigger ones go to a fix delegate.
- **Checkpoints:** after each merge or phase end, update this file (docs PR, merge on green) and post a short status in the thread. Don't stop at checkpoints; Claude compacts from outside while the CoS is idle. After a compaction, re-read this file and continue.
