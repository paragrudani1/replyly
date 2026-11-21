# Device checklist: ticket #6 (settings, API key, cost guard, feedback log)

Phone: Samsung S23, One UI. Install the debug APK from the `latest-debug` release unless a step says otherwise.
On a failure, send back the step number, a screenshot, and what you saw instead. Never paste your API key into a report.

## Settings screen look (S23)

1. Open Replyly and finish or skip onboarding until you reach **Replyly settings**.
   Expected: a "Usage" card at the top (requests today `0 / 200`, tokens this month `0`), then the sections LLM, Prompt, Limits and privacy, Blocked apps, Blocked chats, and the buttons "Feedback log" and "Setup steps".
   Send back: a screenshot of the top of the screen and one scrolled to the bottom.
2. Check the model chips under the Model field.
   Expected: the three chips `anthropic/claude-haiku-5.5`, `google/gemini-3.8-flash` and `~openai/gpt-mini-latest` wrap onto extra lines and are not cut off. The chip for the current model is highlighted.
3. Tap the About me field and type a few lines.
   Expected: the keyboard does not cover the field you are typing in, and the counter under it goes up. Report any field that ends up hidden behind Gboard.
4. Change any field (for example, Daily request limit to `50`).
   Expected: a bar with "Unsaved changes", "Discard" and "Save" appears at the bottom, above the gesture bar or nav buttons and not under them. Tap Save: the bar disappears and the Usage card shows `0 / 50`.
5. Enter an invalid value (Retention days `0`, or Endpoint `http://x`) and tap Save.
   Expected: the field turns red with a message, and the bar says "Fix the marked fields". Nothing is saved: close and reopen the app, and the old value is still there.
6. Switch the phone to dark mode (Settings > Display > Dark) and reopen Replyly.
   Expected: all text is readable. Send a screenshot if anything is low-contrast.
7. Long-press the title "Replyly settings".
   Expected: the debug screen opens. Back returns to settings.

## API key

8. In the API key field, paste your OpenRouter key and tap **Save key**.
   Expected: the field becomes "API key: saved ••••••••" with "Replace" and "Remove". The key is never shown again, not even partly.
9. Tap **Replace**, type `bad key`, and tap Save key.
   Expected: an error under the field and the field stays open. Tap Cancel: it shows "saved" again.
10. Force-stop Replyly (App info > Force stop) and reopen it.
    Expected: it still shows "API key: saved".

## Key survives an app update (most important)

11. With the key saved (step 8), note the version under App info (for example `0.1.41`).
12. Install a newer `replyly-debug.apk` from the `latest-debug` release **over** the current one, without uninstalling first.
13. Open Replyly. Expected: settings still shows "API key: saved", and your other settings are unchanged. Then ask for suggestions in WhatsApp once ticket #4 has landed.
    Expected: suggestions arrive with no "No API key" error.
    If it shows the key field empty instead: send back both version numbers and whether Android warned about the signature.
14. Uninstall Replyly, then install the APK again.
    Expected (by design): the key and the counters are gone and the key field is empty. Uninstalling deletes the Keystore key.

## Cost guard

15. Once ticket #4 has landed, set Daily request limit to `2` and save. Ask for suggestions 3 times.
    Expected: the first two work. The third shows "limit reached", and the Usage card shows `2 / 2` in red with "Daily limit reached. It resets at midnight."
16. Leave Replyly's settings screen open across midnight (or check it the next morning).
    Expected: the count is back to `0 / 2`. The token count only resets on the 1st of the month.

## Feedback log

17. Once tickets #3 and #4 have landed (the log is in-memory until #3 merges): ask for suggestions, insert one, edit it, and send it yourself. Then open settings > **Feedback log**.
    Expected: an entry with the date, the chat name, the three suggestions with a ✓ on the one you inserted, and "Sent: <your final message>". "Show prompt" expands the prompt.
18. Tap **Clear log** and confirm. Expected: the list is empty.
19. (Encryption check, debug build only.) With a USB cable and adb, run:
    `adb shell run-as com.paragrudani.replyly head -c 16 databases/feedback.db | xxd`
    Expected: random-looking bytes, **not** the text `SQLite format 3`. Send back the output line.
