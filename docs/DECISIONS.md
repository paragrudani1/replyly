# Decisions

One line per decision, with the date and the reason. Newest at the bottom. Longer context lives in `docs/wiki/`.

- 2026-11-21: App renamed Replyly (repo `paragrudani1/replyly`, package `com.paragrudani.replyly`). Reason: Parag's request.
- 2026-11-21: One branch and one PR per ticket against `main`, after a skeleton/shared-contracts PR. Reason: parallel tickets need stable interfaces to avoid conflicts.
- 2026-11-21: Chief of staff reviews and merges PRs once CI is green; Parag does not review. Reason: Parag handed end-to-end ownership to CoS plus the product-owner Claude.
- 2026-11-21: Anything that needs a physical S23 goes into `docs/DEVICE_CHECKLIST.md` with exact steps. Reason: no silent guesses about WhatsApp view IDs, overlay placement or One UI behaviour.
- 2026-11-21: A debug keystore is committed to the repo and used for every build. Reason: a stable signature lets Parag install an update over the old APK without losing the encrypted history.
- 2026-11-21: The APK is published as the rolling GitHub release `latest-debug` (asset `replyly-debug.apk`) on every push to main. Reason: one stable download link for sideloading.
- 2026-11-21: Manual DI (`AppGraph`), no Hilt. Reason: single-user app; fewer build dependencies; each ticket swaps one binding.
- 2026-11-21: Default model `anthropic/claude-haiku-5.5`; quick picks `google/gemini-3.8-flash` and `~openai/gpt-mini-latest` (all three listed on OpenRouter's /models that day). Reason: spec shortlist; no Lite/nano.
- 2026-11-21: PRs merge with merge commits, not squash. Reason: ticket branches stack on the skeleton until it lands; merge commits keep their ancestry intact.
- 2026-11-21: Ticket agents write S23-only checks to `docs/device-checklist/ticket-<N>.md`; CoS merges them into `docs/DEVICE_CHECKLIST.md`. Reason: avoid merge conflicts between parallel tickets.
- 2026-11-21: Toolchain JDK temurin-21 (mise.toml), AGP 9.5.2, Kotlin 2.4.21, Gradle 9.9.2, compile/target SDK 37, minSdk 34. Reason: latest stable that resolved on 2026-11-21 (skeleton agent).
- 2026-11-21: Two SQLCipher DBs (history owned by #3, feedback owned by #6) with one Keystore-protected key. Reason: keeps parallel tickets out of each other's Room schema.
- 2026-11-21 (#4): Parallel mode (3 calls, intents fixed agree/decline/ask) counts as 1 daily request, sums tokens, and fails as a whole if one call fails. Reason: one tap = one request; the contract needs exactly 3 suggestions.
- 2026-11-21 (#4): LLM error details use fixed wording per HTTP status and exception type only, never provider text or exception messages. Reason: those can echo the prompt.
- 2026-11-21 (#4): Requests send only model, messages, max_tokens (1024; 512 per parallel call) and response_format (retried without it once if rejected, remembered per model); no temperature. Reason: some reasoning models reject temperature.
- 2026-11-21 (#4): Prompt caps: 12k chars of history (oldest dropped), 600 per message, 25 style examples of 200 chars (last 20 from this chat, 15 from other chats, deduped), About me 1,500, note 1,000, instruction 300. Reason: bound cost and latency.
- 2026-11-21 (#6): Endpoint must be https. Reason: the API key never travels over plain HTTP.
- 2026-11-21 (#6): Settings bounds: N 1–200, retention 1–365 days, daily limit 1–5000, About me ≤1000 chars. Reason: catch typos.
- 2026-11-21 (#6): Blocklist edits save immediately (not on Save). Reason: blocking is a privacy action.
- 2026-11-21 (#6): An undecryptable API key (e.g. after reinstall) is deleted and reads as "no key". Reason: ask again instead of failing every request.
- 2026-11-21 (#6): Cost counters never roll backwards. Reason: a clock change must not grant a fresh daily allowance.
