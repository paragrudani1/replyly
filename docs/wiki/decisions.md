# Decisions

- 2026-11-21: Parag asked for the whole spec (docs/SPEC.md, 7 tickets) to be built in one pass, one branch and one PR per ticket, skeleton first. Subagents never run above `high` effort.
- 2026-11-21: Parag (via the product-owner Claude) handed end-to-end ownership to CoS: CoS reviews and merges PRs once CI is green, makes product and technical calls itself, and records them one line each in `docs/DECISIONS.md` (the canonical list; this page only adds context). Only S23-only checks stay with Parag, in `docs/DEVICE_CHECKLIST.md`. Real blockers are asked in the CoS thread, where the product-owner Claude answers.
- 2026-11-21: `main` acts as the integration branch, because the PR-per-ticket request overrides implement-spec's single integration branch.
