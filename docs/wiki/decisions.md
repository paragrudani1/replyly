# Decisions

- 2026-11-21: Parag asked for the whole spec (docs/SPEC.md, 7 tickets) to be built in one pass, one branch and one PR per ticket, skeleton first. Subagents never run above `high` effort.
- 2026-11-21: Parag (via the product-owner Claude) handed end-to-end ownership to CoS: CoS reviews and merges PRs once CI is green, makes product and technical calls itself, and records them one line each in `docs/DECISIONS.md` (the canonical list; this page only adds context). Only S23-only checks stay with Parag, in `docs/DEVICE_CHECKLIST.md`. Real blockers are asked in the CoS thread, where the product-owner Claude answers.
- 2026-11-21: `main` acts as the integration branch, because the PR-per-ticket request overrides implement-spec's single integration branch.
- 2026-11-21: Parag pointed out the work runs on a cloud VM, so the skill's 4-concurrent-subagents cap does not apply. Don't queue independent work for capacity reasons; still mind the shared production services on this box (memory, disk). Polish P4 (settings) started at once, with a slot left for #18's profile UI instead of waiting for #18 to land.
- 2026-11-24: Bubble drag, settings and snooze (spec #53): Parag's answers and the CoS decisions are in `/root/.t3/cos/replyly/bubble-decisions.md`; each decision goes one line into `docs/DECISIONS.md` with its ticket. Drag snaps to the nearer left/right edge and supersedes the "x fixed at right edge" rule; the button still never goes below its default spot. #56 starts before #54 merges, building the drag-independent parts first, to save time.
- 2026-11-24: Tests must never read the wall clock (a date test broke `main`); an environment PR adds a guard.
- 2026-11-24: Parag reconfirmed there is no subagent limit on the VM (same as the 2026-11-21 entry).
- 2026-11-24: In #56's fix round (`99ffbcc`), shake ends a snooze only while the phone is unlocked (like Wispr Flow), and the TalkBack "Snooze…" action opens the snooze picker through the same path as a drop on the dismiss target.
- 2026-11-24: A fix round whose commits the CoS has read and found complete may merge without a second review round (applied to #57, `a605d4f`).
