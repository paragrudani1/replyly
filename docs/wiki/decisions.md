# Decisions

- 2026-11-21: Parag asked for the whole spec (docs/SPEC.md, 7 tickets) to be built in one pass. One branch and one PR per ticket against `main`, with a skeleton/shared-interfaces PR first. CoS may merge PRs once CI is green and CoS has reviewed them, leaving a short summary per PR. Subagents never run above `high` effort.
- 2026-11-21: Parag renamed the app from "Reply Suggester" to **Replyly**: repo `paragrudani1/replyly` (private), applicationId/package `com.paragrudani.replyly`, app label and accessibility service label "Replyly". The local folder stays `/root/.t3/projects/reply-suggester`. The spec keeps its content; only the title changed.
- 2026-11-21: Anything only a real S23 can check (WhatsApp view IDs, overlay placement, One UI behaviour) goes into `docs/DEVICE_CHECKLIST.md` with exact steps, never a silent guess. The debug tree-dump screen must produce fixture-ready JSON so Parag can send dumps back.
- 2026-11-21: `main` acts as the integration branch (Parag's PR-per-ticket request overrides implement-spec's single integration branch).
