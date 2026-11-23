# CoS status checkpoint

Overwritten at every checkpoint. The history lives in [plan](plan.md) and [DECISIONS](../DECISIONS.md).

## 2026-11-23: the planned work is done (#29, #27, #28, #30). Nothing in flight.

**Merged.** v1 (#8–#16), polish (#19, #21–#24), profiles (#20), docs (#25), #29 (signing-key rotation), **#27 (message dates, `624939c`)**, **#28 (prompt v2, `ecb0b65`)**, **#40 (#30 PR 1, X detection and parser, `bee7cd9`)**, **#44 (#30 PR 2, X prompt mode, length, panel, `4b04bed`)**, and the wiki docs PRs #31–#39, #41–#43, #45 (and this one). Issues #26 and #30 are closed.
- `latest-debug` is versionCode 93 (built from `4b04bed`), signed with the new key (certificate SHA-256 `2a5421c6…2737d6`).
- Parag must uninstall the old app once first (DEVICE_CHECKLIST 2.0). `feedback.db` migrates to v2 (#28) and v3 (#44, nullable `x_mode`): no downgrade to an older APK after that.
- The final chat system prompt (from `bc75cd1`) and the final X system prompt (from `4b04bed`) were posted verbatim in the thread.

**Repo.** Public since 2026-11-22. CI runs. Signing secrets `REPLYLY_SIGNING_KEYSTORE_B64` and `REPLYLY_SIGNING_PASSWORD`; the key is root-only at `/root/.config/replyly/signing/` (Parag should keep an offline backup).

**How the work ended** (details in DECISIONS, the PR bodies and `/root/.t3/cos/replyly/reviews/`):
- #27: 3 review rounds; the CoS applied r3's two tested should-fixes itself. Gate 919 tests; 0 overwrites at fresh seeds 700000–709999.
- #28: rebased onto #27 and wired to the real date fields; round-2 review's R1 (1:1 targets oldest first) and R2 (approx day narrowed by bubble time) fixed by the CoS. Gate 1014 tests.
- #40 (X parser): review r1 S1–S3, N1–N4 fixed by a fix delegate; the CoS read the diff and skipped round 2. Gate 1051 tests; 25,000 fresh property seeds clean. Every X view id and hint is a guess (English UI required) until DEVICE_CHECKLIST 15 dumps come back.
- #44 (X prompt): review r1 S1–S5 and N1, N3, N5–N7, N9 fixed by the CoS (`dbeacbd`); no round 2. Gate 1120 tests; trim property test 30,000 fresh seeds clean. No automatic regenerate on over-length (invariant 4): cut at a sentence that keeps half the limit, else the longest word cut, else drop the card. Chat `SYSTEM_PROMPT` pinned by SHA-256.

**Waiting on Parag.** S23 results for DEVICE_CHECKLIST 13 (dates), 14 (prompt v2) and 15 (X, 15.1–15.24 with 15.4a). They decide the view ids, the date-separator formats, the hint gate and whether 13.10 needs a stale-screen rule.

**Follow-ups filed (not scheduled):** #38 (Nothing-to-reply subtitle wrong when I never wrote or their media is last); #46 (X: visible "trimmed" label, counting edge cases, X instruction placeholder, hashtag/mention stripping).

**Next for the CoS:** nothing scheduled. Wait for Parag's or Claude's next instruction or device results; then brief fixes like the earlier tickets.

**Open risks**
- Every date-separator format and every X view id is a guess until there are S23 dumps (DEVICE_CHECKLIST 13, 14, 15). 13.10 (chat open across midnight) decides whether the merge needs a rule for stale screens.
- The history merge's identical-short-message limits are documented in DECISIONS (about 0.05–0.25%).
- Parag's commit email is public in the git history; he accepted this by asking for the repo to go public.

**Cleanup.** Done on 2026-11-23: all ticket and review worktrees are removed (each head verified on `origin/main` first). Remaining worktree: the CoS's (`t3-a8f2345a…`, detached at `main`). Branches are kept (never deleted).

**Briefs and reviews.** In `/root/.t3/cos/replyly/`: `x-*.md`, `v2-*.md`, `issue-*.md`, and `reviews/pr27-r1.md` to `-r3.md`, `pr28-r1.md`, `pr28-r2.md`, `pr40-r1.md`, `pr44-r1.md`.

**How the CoS works.**
- **GitHub token:** `GH_TOKEN="$(cat /root/.config/replyly/gh_token)" gh … -R paragrudani1/replyly`. Never print it. The T3 PR and issue tools use another login and fail for this repo.
- **Agents:** `delegate_task` with `isolation: worktree`, Claude Opus 5.5, effort high (never higher). The common rules are in `ticket-common.md`, `x-common.md`, `v2-common.md` and `review-common.md` under `/root/.t3/cos/replyly/`. A delegate that must continue an existing branch works in that branch's worktree (named in its brief), since a branch can't be checked out twice.
- **Merging:** merge commits only, never `--delete-branch`, and only on green CI.
- **Reviews:** a fresh delegate per round (never a follow-up message). Small findings the CoS fixes itself in the branch's worktree; bigger ones go to a fix delegate.
- **Checkpoints:** after each merge or phase end, update this file (docs PR, merge on green) and post a short status in the thread. Don't stop at checkpoints; Claude compacts from outside while the CoS is idle. After a compaction, re-read this file and continue.
