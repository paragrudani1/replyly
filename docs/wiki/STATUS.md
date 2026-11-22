# CoS status checkpoint

Overwritten at every checkpoint. The history lives in [plan](plan.md) and [DECISIONS](../DECISIONS.md).

## 2026-11-23: prompt v2 done (#27, #28); #30 PR 1 (X parser) in progress

**Merged.** v1 (#8–#16), polish (#19, #21–#24), profiles (#20), docs (#25), #29 (signing-key rotation), **#27 (message dates, `624939c`)**, **#28 (prompt v2, `ecb0b65`)**, and the wiki docs PRs #31–#39. Issue #26 is closed.
- `latest-debug` is versionCode 76 (built from `ecb0b65`), signed with the new key (certificate SHA-256 `2a5421c6…2737d6`).
- Parag must uninstall the old app once first (DEVICE_CHECKLIST 2.0). `feedback.db` migrates to v2 with #28: no downgrade to an older APK after that.
- The final system prompt was posted verbatim in the thread (from `bc75cd1`).

**Repo.** Public since 2026-11-22. CI runs. Signing secrets `REPLYLY_SIGNING_KEYSTORE_B64` and `REPLYLY_SIGNING_PASSWORD`; the key is root-only at `/root/.config/replyly/signing/` (Parag should keep an offline backup).

**How #27 and #28 ended** (details in DECISIONS, the PR bodies and `/root/.t3/cos/replyly/reviews/`):
- #27: 3 review rounds; the CoS applied r3's two tested should-fixes itself. Gate 919 tests; 0 overwrites at fresh seeds 700000–709999.
- #28: rebased onto #27 and wired to the real date fields; round-2 review's R1 (1:1 targets oldest first) and R2 (approx day narrowed by bubble time) fixed by the CoS. Gate 1014 tests. DECISIONS has the #26 PR 2 entries.

**In flight: issue #30, X reply and quote posts.** Issue: https://github.com/paragrudani1/replyly/issues/30. Common rules: `/root/.t3/cos/replyly/x-common.md`.
- **PR 1 (`x-parser`): detection, X parser, "My X handle", no history, dump/scramble, interim mapping to `ParsedChat.messages`.**
  - **Open subagent: "#30 PR 1: X detection and parser"**, worktree `t3-a25cf273-05fbfdfd38949307`.
    - Brief: `/root/.t3/cos/replyly/x-pr1-brief.md`.
    - taskId `node:delegated-task:command%3Amcp%3A3b3b2c71-2b5d-47d7-bcb5-344045843096%3Adelegate-task%3A8735893f-c601-4496-a9c9-8c22f0eff83b`
    - thread `thread:delegated-task:command%3Amcp%3A3b3b2c71-2b5d-47d7-bcb5-344045843096%3Adelegate-task%3A8735893f-c601-4496-a9c9-8c22f0eff83b`
    - If it has finished, read its result with `task_status`.
  - **Next:** review (new delegate, brief like `v2-pr1-review-brief.md`), fix, merge on green, status post.
- **PR 2 (after PR 1 merges):** the X system-prompt section, 280/500 length enforcement (URLs 23, X's emoji/CJK weights; trim at a sentence boundary or regenerate), per-card counter, "My X voice" and "X post length" settings, X chip labels/colours, screenshots, DEVICE_CHECKLIST 15 rest. Body says `Closes #30`. Brief to write: `x-pr2-brief.md`, built on PR 1's pinned model.

**Follow-ups filed:** #38 (Nothing-to-reply subtitle wrong when I never wrote or their media is last).

**Open risks**
- Every date-separator format and every X view id is a guess until there are S23 dumps (DEVICE_CHECKLIST 13, 14, 15). 13.10 (chat open across midnight) decides whether the merge needs a rule for stale screens.
- The history merge's identical-short-message limits are documented in DECISIONS (about 0.05–0.25%).
- Parag's commit email is public in the git history; he accepted this by asking for the repo to go public.

**Cleanup.** Done on 2026-11-23: the stale worktrees, `/root/local-dev/tmp-replyly-base`, `/root/local-dev/replyly-audit` and the `/tmp` scratch files are removed. Remaining worktrees: the CoS's (`t3-a8f2345a…`) and PR 1's agent's (`t3-a25cf273…`).

**Briefs and reviews.** In `/root/.t3/cos/replyly/`: `x-*.md`, `v2-*.md`, `issue-*.md`, and `reviews/pr27-r1.md` to `-r3.md`, `pr28-r1.md`, `pr28-r2.md`.

**How the CoS works.**
- **GitHub token:** `GH_TOKEN="$(cat /root/.config/replyly/gh_token)" gh … -R paragrudani1/replyly`. Never print it. The T3 PR and issue tools use another login and fail for this repo.
- **Agents:** `delegate_task` with `isolation: worktree`, Claude Opus 5.5, effort high (never higher). The common rules are in `ticket-common.md`, `x-common.md` (current), `v2-common.md` and `review-common.md` under `/root/.t3/cos/replyly/`. A delegate that must continue an existing branch works in that branch's worktree (named in its brief), since a branch can't be checked out twice.
- **Merging:** merge commits only, never `--delete-branch`, and only on green CI.
- **Reviews:** a fresh delegate per round (never a follow-up message). Small findings the CoS fixes itself in the branch's worktree; bigger ones go to a fix delegate.
- **Checkpoints:** after each merge or phase end, update this file (docs PR, merge on green) and post a short status in the thread. Don't stop at checkpoints; Claude compacts from outside while the CoS is idle. After a compaction, re-read this file and continue.
