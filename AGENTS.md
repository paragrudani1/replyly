# Replyly: agent guide

Personal sideloaded Android app (Kotlin, Compose, single `:app` module). Product: [docs/SPEC.md](docs/SPEC.md).
Before touching code, read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): the data flow, the AppGraph bindings, storage and purge rules.

## Hard invariants

1. **Replyly never sends a message.** The only action on another app is `ReplyInserter` setting the text of the focused field. Clicks, Enter, gestures and global actions are off limits (`InvariantsTest` scans for them).
2. **Chat text, prompts and the API key stay out of logcat**, crash messages and exception text. Log event names, counts and class names only.
3. **The LLM call is the only network use**, and lives in `llm/` (`InvariantsTest` checks this).
4. **Only a user tap leads to an LLM call: the button, Regenerate, Retry or a quick-chip tap, each one call.** Screen events, typing and the keyboard's Done never do.

## Build and test

- Gate (exactly what CI runs): `scripts/check.sh` = `./gradlew spotlessCheck lintDebug testDebugUnitTest assembleDebug -Proborazzi.test.verify=true`. It must pass before you push. On the KSP init flake (`PluginEnabler` / `ExceptionInInitializerError`) it retries once by itself in a fresh JVM; do not add your own retry.
- Fix formatting: `./gradlew spotlessApply` (ktlint via Spotless; rules in `.editorconfig`).
- One test class: `./gradlew testDebugUnitTest --tests '*ChatParserRegistryTest'`.
- UI work: follow [docs/DESIGN.md](docs/DESIGN.md) (theme, tokens, motion, accessibility). Screenshot tests are verified by the gate; a changed screen needs `scripts/record-screenshots.sh` and the new PNGs committed ([docs/SCREENSHOTS.md](docs/SCREENSHOTS.md)).

## Toolchain (shared VM)

- JDK 21 comes from `mise.toml`; the `java` shim picks it up inside the repo.
- Android SDK: `/root/local-dev/android-sdk` (platform `android-37.2`, build-tools 37.0.0, no emulator). A fresh worktree has no `local.properties`: `scripts/check.sh` handles it, for plain `./gradlew` run `export ANDROID_HOME=/root/local-dev/android-sdk` first.
- The VM is shared with production services: follow `/root/local-dev/AGENTS.md`. Never run `./gradlew --stop` (it kills other agents' builds); idle daemons exit after 30 min.
- The first build of a worktree downloads Gradle and dependencies (a few minutes); Robolectric downloads its android-all jar on the first Robolectric test.

## Testing rules

- Pure logic (parsers, merge, prompt building, output parsing, cost math) goes in plain JVM tests under `app/src/test`.
- Android pieces (node mapping, Room/SQLCipher, DataStore, services) run with Robolectric (`@RunWith(RobolectricTestRunner::class)`), still under `app/src/test`. There are no instrumented tests.
- Parser tests read JSON fixtures: `Fixtures.snapshot("whatsapp/group.json")`. See `app/src/test/resources/fixtures/README.md`.
- HTTP tests use OkHttp `MockWebServer` (`mockwebserver3`); flows use Turbine; assertions use Truth.
- History merge: `HistoryMergePropertyTest` must stay green; extend its invariants for new cases. Logic with a large input space needs a seeded property test.

## Working in parallel

- Edit inside your ticket's packages. In `AppGraph`, change only the right-hand side of your own bindings and keep the comment line between bindings.
- Shared contracts change only additively (new field with a default, new method with a default body), and the PR description says so.
- Every dependency is already in `gradle/libs.versions.toml`; leave it unchanged unless your ticket cannot work without a new one.
- Anything only the real S23 can confirm (view ids, overlay placement, One UI behaviour) goes into `docs/DEVICE_CHECKLIST.md` with exact steps.
- The debug keystore `app/debug.keystore` is committed on purpose (stable signature, so updates install over the old app). Keep it.
