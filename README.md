<img src="assets/icon.svg" width="64" height="64" alt="">

# Replyly

Personal Android app (sideloaded, Samsung S23): a floating button above the keyboard in chat apps suggests 3 replies in your own style using an LLM, and inserts the one you tap. It never sends anything itself.

- Spec: [docs/SPEC.md](docs/SPEC.md)
- Architecture: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- Build: `scripts/check.sh` (what CI runs). Debug APK of `main`: the `latest-debug` release.

This is a personal project, built for one person and one phone. The code is public to read, but
there is no license: all rights reserved. Issues and pull requests from others are not expected.

## How suggestions are made

One tap, one LLM call (prompt v2, issue #26). The prompt tells the model the phone's current date
and time ("Now: Sunday, 22 Nov 2026, 11:08 am (morning), Asia/Kolkata", 12 or 24-hour like the
phone), shows the chat grouped by day with each sender's name, and marks the messages to answer:
everything others wrote after your last message, with group messages that mention you (Settings →
"Names people call me") or reply to you first. The model returns a short `analysis` (kept only in
the local feedback log) and 3 replies with clearly different intents.

If your own message is the last one, Replyly asks for follow-ups when it is over 2 hours old or an
unanswered question. Otherwise the panel shows "Nothing to reply to" without calling the model;
"Suggest follow-ups anyway", Regenerate or a quick-chip tap there makes the one call. Settings →
Feedback log shows the insert rate and how much you edit inserted replies, per prompt version.

**Upgrading from prompt v1:** the analysis comes out of the same output budget as the replies. The
default is now 1,536 tokens. If a profile has an explicit max tokens of 1,024 or less (set under v1),
raise it or clear the field, or replies get cut off more often, mostly on models that think first.

## Using other providers

Replyly talks to any service that speaks the **OpenAI Chat Completions** API or the **Anthropic
Messages** API. In Settings, under "Provider profiles", add one profile per service and tap a row
to make it the active one. Each profile has a name, an API format, a base URL, an API key, a model
id and, optionally, max tokens, a temperature (OpenAI format only) and extra headers. Keys and
header values are stored encrypted on the phone and never shown again after saving.

**Base URL.** Enter it *including the version path*. Replyly appends `/chat/completions` for the
OpenAI format and `/messages` for the Anthropic format. For the Anthropic format, a URL whose last
path segment is not a version (`v1`, `V2`, `v1beta`) gets `/v1` added (`https://api.anthropic.com`
becomes `https://api.anthropic.com/v1/messages`; a gateway URL like
`https://gateway.ai.cloudflare.com/v1/<acct>/<gw>/anthropic` becomes `…/anthropic/v1/messages`).
Only `https://` URLs are accepted, without `user:password@`. The editor shows the final URL under
the field.

**Changing the host or format** of a profile that has a saved key asks for the key again: the old
key is never sent to a new provider. "Remove" on a saved key takes effect when you tap Save.

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
- **OpenAI** (`api.openai.com`) gets `max_completion_tokens` and `reasoning_effort: "low"`; other
  OpenAI-format hosts get `max_tokens`. If a server rejects the token field it uses, Replyly retries
  once with the other one and remembers that for the URL and model.
- **Anthropic** requests use `x-api-key`, `anthropic-version: 2026-10-21`, a top-level `system`
  prompt and a JSON schema for the reply (structured outputs). On `api.anthropic.com` they also
  ask for low effort to keep replies fast, and mark the (fixed) system prompt with `cache_control`
  so repeated taps read it from Anthropic's prompt cache. A vendor that rejects the schema gets one retry without
  it. Temperature is never sent in this format.
- **A local or self-hosted server** must be reachable over https from the phone (for example
  behind a reverse proxy with a real certificate, or a tunnel). Plain `http://` is refused, so the
  key never travels unencrypted.
- **Extra headers** are for things like an organisation id or a proxy token. They cannot replace
  `Authorization`, `x-api-key`, `Content-Type`, `Content-Length`, `Host` or `Accept-Encoding`. An
  `anthropic-version` header overrides the default, for Anthropic-compatible vendors that need an
  older version.
- **Anthropic-compatible proxies that only accept `Authorization: Bearer`** are not supported: the
  Anthropic format always sends the key as `x-api-key`, and `Authorization` cannot be set as an
  extra header. Use the proxy's OpenAI-compatible endpoint instead, if it has one.

Updating from a version without profiles moves your existing endpoint, key and model into a
profile named "OpenRouter" (or "Custom" for another endpoint), so nothing needs to be entered again.
