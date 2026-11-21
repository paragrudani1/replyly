# Parser fixtures: from a phone screen to a test

The parsers (`parser/whatsapp/`, `parser/telegram/`, `parser/generic/`) are tested on saved screen trees.
Until real dumps arrive, the fixtures are **synthetic** (`synthetic-*.json`), written from public knowledge of the apps.
Every guess behind them is listed in [device-checklist/ticket-2.md](device-checklist/ticket-2.md).
This page explains how to replace them with real dumps, and what to do when an app update breaks a parser.

## 1. Capture a dump on the phone (Parag)

1. Open Replyly. On the settings screen, **long-press the title** "Replyly settings". The debug screen opens.
2. The top card must say "Accessibility service is running". If it says **NOT enabled**, tap "Open accessibility settings", turn Replyly on, and come back.
3. Open the chat you want in WhatsApp, WhatsApp Business or Telegram once, so it is in recent apps. Prefer a chat with a test contact.
4. Back in Replyly, tap **Dump in 5 s**. Within 5 seconds, switch to the chat (recent apps button, or swipe along the bottom bar) and leave it on screen. Do not scroll.
5. A toast says "Replyly: dump saved (com.whatsapp)". If it says "Replyly was still on top", you were too slow: try again.
6. Return to Replyly. The new dump is at the top of "Saved dumps", with what the parser read from it:
   - `Parser:` the route it took, for example `whatsapp:view-ids` (good) or `whatsapp:geometry` (the view ids were not found).
   - `Title:`, `Chat key:`, `Group:` and the number of messages.
   - one line per message: `←` someone else (with the sender), `→ me`, the time in brackets, then the text.
   Compare it with the screen. That alone tells whether the parser works on this chat.

Screens the sensitive-screen policy blocks (password fields, blocklisted chats, banking apps) are never saved.

## 2. Send it

On the dump's card:

- **Scramble text when sharing** (switch above the list): turn it on to send the structure without private content.
  Letters become random letters of the same case and length; digits, emoji, punctuation, view ids, bounds, times, date separators and status words ("online", "Edited", "Sent ... Read") stay readable, so the parser still reads a scrambled dump the same way.
  Digits are not scrambled: phone numbers stay. Check for them, or use a test chat.
- **Share** sends the JSON as text (WhatsApp to yourself, Gmail, Drive...). **Copy** puts it on the clipboard.
- Big dumps (over about 250,000 characters) cannot go through share or copy: use **Save**, which writes `Downloads/Replyly/<name>.json`, and send that file.
- **Delete** removes the dump from the phone.

Send it to the CoS with one line: which app, which case (1:1, group, same text twice, Hinglish, broken after an update...), and what the summary got wrong, if anything.

## 3. Turn a dump into a fixture and a test (developer)

1. Put the file in `app/src/test/resources/fixtures/<app>/`, where `<app>` is `whatsapp`, `whatsapp_business`, `telegram` or `generic`.
   Name it after the case: `one_to_one.json`, `group.json`, `same_text_twice.json`, `hinglish.json`, `reply_quote.json`...
   Real dumps never start with `synthetic-`.
2. Only commit scrambled dumps or dumps of test chats: fixtures are public in the repo.
3. Look at the tree. Each node shows `viewId`, `text`, `contentDescription` and `bounds`. A quick listing:
   ```sh
   jq -r '.. | objects | select(.text? or .contentDescription?) | "\(.bounds)\t\(.viewId // "-")\t\(.text // .contentDescription)"' \
     app/src/test/resources/fixtures/whatsapp/group.json
   ```
4. Write the test next to the synthetic one (`app/src/test/kotlin/.../parser/<app>/`): load the dump with `Fixtures.snapshot("whatsapp/group.json")` and assert the full message list (`sender`, `text`, `timeShown`, `isMe`), in order. For a scrambled dump, the expected texts are the scrambled strings (copy them from the debug screen summary or the JSON).
5. When a real dump covers the same case as a synthetic one, delete the synthetic fixture and point its test at the real one. Keep the assertions about the tricky parts (the comment in each synthetic test says what it checks).
6. Run `./gradlew testDebugUnitTest --tests '*WhatsAppParserTest'`, then `scripts/check.sh`.

## 4. When an app update breaks the parser

Capture a dump of the broken chat (section 1) and read its summary line `Parser:`.

| Symptom | Likely cause | Where to fix |
|---|---|---|
| WhatsApp shows `whatsapp:geometry` instead of `whatsapp:view-ids` | `message_text` (or `caption`) was renamed | Find the new id of the message text in the dump; update `WhatsAppIds` in `parser/whatsapp/WhatsAppParser.kt` |
| WhatsApp title wrong or missing | `conversation_contact_name` renamed | `WhatsAppIds.TITLE` |
| Group senders missing (`← ?` everywhere) | `name_in_group_tv` renamed | `WhatsAppIds.SENDER_NAME` |
| Times missing | `date` renamed, or a new time format | `WhatsAppIds.TIME`; time pattern in `parser/generic/ChatText.kt` |
| Telegram shows `telegram:geometry` or no messages | The cell description lost its last line `Sent/Received ... <time>` (new wording or language) | `CellDescription` in `parser/telegram/TelegramParser.kt` |
| Mine and theirs swapped, or a wide bubble on the wrong side | Bubble boxes changed (new margins, avatar column) | Look at the `bounds` of bubbles; rules in `Sides.resolve` (`parser/generic/ChatLayout.kt`) |
| A banner, date or notice shows up as a message | New chrome text | `ChatText` (`isDateSeparator`, `isAlwaysChrome`, `isBubbleLabel`) |
| A real message is missing | Chrome rule too broad, or a new id for that message type | `ChatText`, or add the id to `WhatsAppIds.MESSAGE_TEXTS` |
| `Not recognised as a chat.` on a chat | No input field found in the lower 70% of the screen, and no ids | `ChatLayout.detect` |

Then: add the new dump as a **new** fixture (keep the old one; name it with the month, e.g. `group-2027-01.json`), write its test, make it pass, and run `scripts/check.sh`. Old fixtures must keep passing unless the old layout is truly gone.
