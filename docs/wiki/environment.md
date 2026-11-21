# Environment

- 2026-11-21: The VM (shared Hetzner box, see `/root/local-dev/AGENTS.md`) had no Android SDK. `java` is a mise shim, and only `temurin-25` was installed (no global default). `/dev/kvm` exists, so an emulator is possible. `/` was 93% full (31 GB free), so keep SDK and emulator images lean.
- 2026-11-21: The `gh` token is a fine-grained PAT for selected repos. `gh repo create` failed with "does not have the correct permissions to execute CreateRepository". Parag was asked to create `paragrudani1/replyly` and add it to the PAT (Contents, Issues, PRs, Actions, Workflows: write).
