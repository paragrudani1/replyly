# Ticket #4: LLM client and prompt, device checks

Needs: the app installed on the S23, the accessibility service on, an OpenRouter key with some credit, and
tickets #1, #5 and #6 merged (button, panel, settings screen). Use a real WhatsApp chat with at least
10 recent messages, ideally one in Hinglish or Gujlish.

For every step, note the model, the latency the panel/debug info shows (or a stopwatch from tap to cards),
and send back the three suggestion texts only if you are fine sharing them. Never send the API key.

## A. Real-key smoke test, once per shortlist model

Repeat steps 1–5 for each model id:

- `anthropic/claude-haiku-5.5`
- `google/gemini-3.8-flash`
- `~openai/gpt-mini-latest`

1. Settings → API key: paste your OpenRouter key. Settings → Model: enter the model id above. Leave the
   endpoint at `https://openrouter.ai/api/v1`.
2. Open a WhatsApp 1:1 chat, tap the input field, tap the Replyly button.
   You should see 3 cards within about 2 s, each with a different intent (e.g. agree / decline / ask).
3. Check the cards:
   - same language and script as you write in this chat (Hinglish stays in Latin letters, no Devanagari,
     even if the other side writes in Devanagari);
   - length, tone and emoji close to how you write;
   - no invented times, places or promises (if one would be needed, the card asks instead).
4. Type an instruction ("say I'll call at 6"), tap Regenerate. All 3 cards should follow it.
5. Repeat step 2 in a WhatsApp group chat and in a Telegram chat.

Send back per model: latency of steps 2 and 4 (seconds), pass/fail for each check in step 3, and the
error card text if any step failed.

## B. Error messages

6. Settings → API key: change one character of the key. Tap the button.
   Expected: an error card saying the API key was rejected (HTTP 401). Restore the key.
7. Settings → Model: `does-not-exist/model`. Tap the button.
   Expected: "Request rejected. Check the model id in Settings." (HTTP 400) or "Model or endpoint not
   found" (404). Restore the model.
8. Turn on airplane mode. Tap the button.
   Expected: a network error ("No internet connection…"), within ~10 s. Turn airplane mode off.

Send back: the exact error text for any step that showed something else, or took longer than 60 s.
(Worst case is 60 s: one call can take up to 30 s, and a model that rejects JSON mode gets one retry
without it, once per model.)

9. If any card shows "reply cut off at the token limit", the model spent its token budget (1024 for 3
   suggestions, 512 per call in parallel mode) before finishing, usually on reasoning. On OpenRouter the
   app already asks for low reasoning effort.
   Send back: the model id, and whether it happens every time or only sometimes.

## C. When to turn on parallel mode

The default makes 1 call for 3 suggestions. `useParallelCalls` makes 3 concurrent calls, one per intent
(agree / decline / ask), still counted as 1 request in the daily limit, but about 3× the prompt tokens.

10. If, in section A, the chosen model usually takes more than ~2 s from tap to cards, turn on parallel
    mode (Settings, once #6 exposes it; until then, tell the CoS and it will flip the default) and repeat
    steps 2 and 4 with that model.
11. Keep parallel mode on only if it is clearly faster (≥ 0.5 s) and the cards still sound like you.
    Note: the intents are then fixed to agree / decline / ask, which fits questions and plans better than
    greetings or news.

Send back: latency for that model with parallel mode off and on, and which one you prefer.

## D. Usage counter

12. After a few requests, open Settings → usage. The request count today should go up by 1 per tap or
    Regenerate (also with parallel mode on), and the monthly token count should grow.

Send back: the counts before and after 3 taps.
