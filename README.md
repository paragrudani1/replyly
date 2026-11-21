<img src="assets/icon.svg" width="64" height="64" alt="">

# Replyly

Personal Android app (sideloaded, Samsung S23): a floating button above the keyboard in chat apps suggests 3 replies in your own style using an LLM, and inserts the one you tap. It never sends anything itself.

- Spec: [docs/SPEC.md](docs/SPEC.md)
- Architecture: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- Build: `scripts/check.sh` (what CI runs). Debug APK of `main`: the `latest-debug` release.

## Using other providers

Replyly talks to any service that speaks the **OpenAI Chat Completions** API or the **Anthropic
Messages** API. In Settings, under "Provider profiles", add one profile per service and tap a row
to make it the active one. Each profile has a name, an API format, a base URL, an API key, a model
id and, optionally, max tokens, a temperature (OpenAI format only) and extra headers. Keys and
header values are stored encrypted on the phone and never shown again after saving.

**Base URL.** Enter it *including the version path*. Replyly appends `/chat/completions` for the
OpenAI format and `/messages` for the Anthropic format. For the Anthropic format, a URL without a
version segment gets `/v1` added (`https://api.anthropic.com` becomes
`https://api.anthropic.com/v1/messages`). Only `https://` URLs are accepted. The editor shows the
final URL under the field.

Tap **Test connection** on a profile to send one tiny request. It shows `OK · 840 ms` or the same
error wording as the reply panel, and it counts as 1 request against the daily limit.

| Profile | API format | Base URL | Model (example) | Key |
|---|---|---|---|---|
| OpenRouter (default) | OpenAI-compatible | `https://openrouter.ai/api/v1` | `anthropic/claude-haiku-5.5` | `sk-or-…` |
| OpenAI | OpenAI-compatible | `https://api.openai.com/v1` | any chat model id from your OpenAI account | `sk-…` |
| Anthropic | Anthropic | `https://api.anthropic.com/v1` | `claude-haiku-5-5` | `sk-ant-…` |
| Compatible server or proxy | OpenAI-compatible | `https://llm.example.com/v1` | whatever the server serves | the server's key |

Notes:

- **OpenRouter** profiles also send `reasoning: {effort: "low"}` and OpenRouter's attribution
  headers. Nothing OpenRouter-specific goes to any other host.
- **Anthropic** requests use `x-api-key`, `anthropic-version: 2026-10-21`, a top-level `system`
  prompt and a JSON schema for the reply (structured outputs). On `api.anthropic.com` they also
  ask for low effort to keep replies fast. A vendor that rejects the schema gets one retry without
  it. Temperature is never sent in this format.
- **A local or self-hosted server** must be reachable over https from the phone (for example
  behind a reverse proxy with a real certificate, or a tunnel). Plain `http://` is refused, so the
  key never travels unencrypted.
- **Extra headers** are for things like an organisation id or a proxy token. They cannot replace
  `Authorization`, `x-api-key`, `Content-Type`, `Content-Length` or `Host`. An `anthropic-version`
  header overrides the default, for Anthropic-compatible vendors that need an older version.

Updating from a version without profiles moves your existing endpoint, key and model into a
profile named "OpenRouter" (or "Custom" for another endpoint), so nothing needs to be entered again.
