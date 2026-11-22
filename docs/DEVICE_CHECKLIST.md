# Replyly device checklist (Samsung S23, One UI)

Everything here needs the real phone: overlay windows, Gboard, FLAG_SECURE, the Keystore,
SQLCipher and the real WhatsApp/Telegram view trees. None of it ran on the VM.

## How to report

Each step says what to **do**, what you should **see**, and what to **send** if it fails. Always
add the step number. Three kinds of evidence:

- **Dump**: a debug tree dump of the screen ([PARSER_FIXTURES.md](PARSER_FIXTURES.md), sections 1
  and 2). Turn on "Scramble text when sharing" unless it is a test chat.
- **Log**: Replyly's log lines, filtered to its own tags (event names and counts only, never chat
  text). With USB or wireless debugging on:
  `adb logcat -v time -s ReplylyA11y ReplylyOverlay ReplylyPolicy ReplylyCapture ReplylyDbKey ReplylyHistoryDb ReplylyOnboarding`
  For a crash, also `adb logcat -b crash -d`.
- **Screenshot**: blur or crop private chats.

Never send your API key. Send suggestion texts only if you are fine sharing them.

## Top 10 first

These decide whether the app works at all. Do them first, in this order, and send the results
together; the rest can wait.

1. Onboarding ends with all 5 cards done and the accessibility service running (1.3–1.7).
2. The button appears above Gboard in a WhatsApp chat (3.1).
3. A WhatsApp 1:1 dump parses correctly: `whatsapp:view-ids`, right title, sides and times (6.7).
4. Tapping the button opens the panel, and its instruction field gets the keyboard (4.1, 4.4).
5. A real-key suggestion returns: 3 cards within about 2 s with `anthropic/claude-haiku-5.5` (8.1–8.3).
6. Tapping a card puts the text in WhatsApp's message box, and nothing is sent (5.1).
7. FLAG_SECURE blocks: Chrome Incognito gives "Replyly is off here", a banking app shows no button (10.1–10.2).
8. Passive capture stores history, and `history.db` is encrypted (7.1, 7.2).
9. The API key and history survive installing an update over the app (2.2–2.4).
10. A Telegram 1:1 dump parses correctly (6.17).

## 0. Before you start

0.1 **Do:** get `replyly-debug.apk` (the `latest-debug` GitHub release, or the file the CoS sends).
For adb steps, turn on Developer options > USB debugging (or Wireless debugging) and check
`adb devices` lists the phone. **See:** the phone listed as `device`. **Send:** the `adb devices`
output if it says `unauthorized` or nothing.

0.2 **Do:** if a build of ticket #3 from before 2026-11-21 evening is installed, clear Replyly's
storage first (Settings > Apps > Replyly > Storage > Clear storage). The history schema changed
without a version bump, so the old `history.db` would fail to open. Updates from later builds
(2.2–2.4) keep history. **See:** nothing to check. **Send:** nothing.

## 1. Install and first run

Start from a fresh install (uninstall Replyly first, or clear its storage). After each step, come
back to Replyly (Back or Recents): the card updates by itself, without a restart.

Assumed One UI paths (unverified; report any that differ):

| Card | Path |
|---|---|
| 1 | Settings → Apps → Replyly → ⋮ (top right) → "Allow restricted settings" → PIN/fingerprint |
| 2 | Settings → Accessibility → Installed apps → Replyly → switch on |
| 3 | Settings → Apps → Replyly → Battery → "Unrestricted" |
| 4 | Settings → Device care → Battery → Background usage limits → Sleeping / Deep sleeping / Never sleeping apps |
| 5 | Replyly's own settings (API key and model) |

1.1 **Do:** sideload the APK from My Files (tap it, allow "Install unknown apps" for My Files if
asked). **See:** Replyly installs and opens on **Set up Replyly** with 5 cards, all "Not done" or
"Not confirmed". **Send:** screenshot of any install error. (Installing with `adb install` skips
the "restricted settings" block; use My Files once so 1.2–1.3 are tested.)

1.2 **Do:** card 2 "Open Accessibility" → Installed apps → Replyly. **See:** the switch is greyed
out, or tapping it shows a "Restricted setting" dialog. Back in Replyly, card 1 still says
"Not done". **Send:** screenshot of what the switch did.

1.3 **Do:** card 1 "Open App info" → ⋮ → "Allow restricted settings" → confirm. **See:** the item
exists (it may only appear after 1.2). Card 1 stays "Not done" until card 2 is done (Replyly
cannot read this setting). **Send:** screenshot of the ⋮ menu if the item is missing or named
differently.

1.4 **Do:** card 2 "Open Accessibility" → Installed apps → Replyly → on → Allow. **See:** back in
Replyly, cards 1 and 2 both "✓ Done". If card 2 briefly says "Turned on but not running", it turns
"✓ Done" within a second or two. Switch the service off: both go back to "Not done"; switch it on
again. **Send:** screenshot and Log if card 2 stays on "Turned on but not running".

1.5 **Do:** card 3 "Allow". **See:** only a system dialog asking to let Replyly always run in the
background (App info must not open on top or after it). Tap Allow: card 3 "✓ Done", and App info >
Battery shows "Unrestricted". Set it to "Optimized": card 3 goes back to "Not done"; set it back.
**Send:** screenshot if App info appears, or if App info and the card disagree.

1.6 **Do:** card 4 "Open battery settings". **See:** Device care → Battery, the general battery
usage screen, or App info (say which). Make sure Replyly is not in Sleeping or Deep sleeping apps;
add it to Never sleeping apps. Back in Replyly tap "I've done this": "✓ Done (you confirmed it)",
still there after force-stop and reopen; "Undo" clears it. **Send:** which screen opened, or the
"Could not open that screen" toast.

1.7 **Do:** card 5 "Open settings", paste your OpenRouter key, Save key, press Back. **See:**
"Set up Replyly" with card 5 "✓ Done", and the bottom button "Done: go to settings". **Send:**
screenshot.

1.8 **Do:** force-stop and reopen Replyly. **See:** it opens straight on **Replyly settings**.
Turn the service off and reopen: it opens on **Set up Replyly**. Turn it back on. **Send:** which
screen opened.

1.9 **Do:** open each system screen from its card (App info, Accessibility, battery settings) and
press Back once from each. Also: card 2, switch to Replyly through Recents, card 1, Back. **See:**
every Back lands on "Set up Replyly". **Send:** which screen Back showed instead.

1.10 **Do:** settings → "Setup steps" → "Skip for now" (or "Done: go to settings"), then Back once.
**See:** settings, then the home screen (no second settings screen). **Send:** screenshot.

## 2. After every update

2.0 **Once, for the first build after 2026-11-22 (new signing key).** The repo went public, so the
old committed debug key was replaced by a private one. Android refuses to install the new build over
the old one ("App not installed" / "package conflicts"). **Do:** uninstall Replyly once, then install
the new `replyly-debug.apk` and redo onboarding (section 1). This resets the encrypted history, the
feedback log, the provider profiles and the API key (enter the key again). Later builds install over
this one as before. **Send:** the install error text if the fresh install itself fails.

2.1 **Do:** after installing a new build over an older one, turn the Replyly accessibility service
off and on once (Settings > Accessibility > Installed apps > Replyly). **See:** the button works in
3.1. If you skip this, every tap may show "Replyly is off here" with
`secure check state=UNKNOWN code=2` (or `code=-1`) in the Log: the FLAG_SECURE check fails closed.
**Send:** Log if taps still say "off here" after the toggle.

2.2 **Do:** with the key saved and some history captured (7.2), note the version in App info, then
install a newer APK over it (no uninstall). **See:** Android installs without a signature warning.
**Send:** both version numbers and the warning text, if any.

2.3 **Do:** open Replyly. **See:** settings still says "API key: saved" and your other settings
(including blocklists and the parallel-calls switch) are unchanged; a suggestion in WhatsApp works
with no "No API key yet" card. **Send:** both versions and a screenshot of settings.

2.4 **Do:** open a chat you captured before and wait 2 s. **See:** in the Log, no
`new database passphrase` line, and `capture merged` shows only new messages (or no line). The
feedback log still lists earlier entries. **Send:** Log, especially any `new database passphrase
reason=...` line.

## 3. Overlay button

Log lines you will see: `button shown`, `button hidden reason=<REASON>`,
`tap outcome=open|no_chat|blocked reason=<REASON>`, `screen off`. To compare positions:
`adb shell dumpsys window windows | grep -E "Window #|mFrame|InputMethod"` ("window dump" below).

3.1 **Do:** open a WhatsApp chat, tap the message field. **See:** within a moment a round purple
button (speech bubble) at the right, just above the message bar: not above Gboard's toolbar row,
not over WhatsApp's send/mic button, not under the status or navigation bar. **Send:** screenshot,
Log, window dump.

3.2 **Do:** type 4 lines so the bar grows. **See:** the button moves up with the bar. **Send:**
screenshot.

3.3 **Do:** rotate left, then right, in the same chat. **See:** button at the right above the bar,
not under the camera cut-out. **Send:** screenshot per side, window dump.

3.4 **Do:** switch to 3-button navigation (Settings > Display > Navigation bar > Buttons), rotate
both ways. **See:** button fully visible, not under the side navigation bar. Switch back if you
like. **Send:** screenshot, window dump.

3.5 **Do:** in landscape, use a keyboard that covers the whole screen (full-screen landscape
keyboard). **See:** no button (`reason=INPUT_COVERED`). **Send:** screenshot if it shows pinned in
a corner.

3.6 **Do:** Gboard toolbar > ⋮ > Floating; move the floating keyboard. **See:** button above the
keyboard's right corner, following it within about half a second. **Send:** screenshot and window
dump if it vanishes with `INPUT_COVERED` while the bar is visible.

3.7 **Do:** Gboard one-handed mode. **See:** button above the keyboard's right edge. **Send:**
screenshot.

3.8 **Do:** switch to Samsung Keyboard (Settings > General management > Keyboard list and
default), repeat 3.1. **See:** same placement. **Send:** screenshot, window dump.

3.9 **Do:** close the keyboard with Back, then tap the field again. **See:** hidden within about
0.2 s (`reason=KEYBOARD_CLOSED`), then shown. **Send:** Log.

3.10 **Do:** open WhatsApp's emoji panel, then go back to the keyboard. **See:** hidden while the
emoji panel shows, shown again with the keyboard. **Send:** Log if it stays over the emoji panel.

3.11 **Do:** Back to the chat list, open another chat, tap the field. **See:** hidden on the list,
shown in the new chat. **Send:** Log.

3.12 **Do:** Home with the keyboard open; then Recents and back to WhatsApp. **See:** hidden at
once, not over the launcher (unless you tap a search field there); shown again in WhatsApp once
the keyboard is back. **Send:** Log.

3.13 **Do:** open Replyly's own settings and tap a text field. **See:** never shown
(`reason=EXCLUDED_APP`). **Send:** screenshot, Log.

3.14 **Do:** repeat 3.9–3.12 in Telegram and WhatsApp Business. **See:** the same. **Send:** which
app and step, Log.

3.15 **Do:** with the button showing, scroll the chat and receive messages for 5 minutes. **See:**
no flicker, no lag while typing; only a handful of `button` lines in the Log. **Send:** the number
of `button` lines, and the Log if `button shown` / `button hidden` repeat.

3.16 **Do:** tap the button ten times in a row in WhatsApp, closing the panel each time; repeat in
Telegram. **See:** while a tap is read the button dims and a second tap does nothing; no
`secure check state=UNKNOWN` line. **Send:** the slowest and a typical time from tap to
`tap outcome=` (`adb logcat -v time -s ReplylyOverlay`).

3.17 **Do:** split screen (Recents > WhatsApp icon > Open in split screen view, Chrome in the other
half). Tap WhatsApp's field, then Chrome's address bar. **See:** the button follows the focused
app, and a tap in WhatsApp reads WhatsApp, not Chrome. **Send:** screenshots, window dump.

3.18 **Do:** pop-up view (Recents > WhatsApp icon > Open in pop-up view), tap the field, then the
button; move the pop-up. **See:** button fully on screen and following; the panel is for that
chat. **Send:** screenshots, window dump.

3.19 **Do:** leave the phone locked for 1 hour, unlock, open WhatsApp, tap the field. Then swipe
Replyly away from Recents and tap the field again. **See:** the button both times. **Send:** if
not, whether Settings > Accessibility shows Replyly On or Off, and
`adb logcat -b events -d | grep -i replyly | tail -20`.

## 4. Panel

The panel is a full-screen modal over the chat: the chat dims and takes no touches, the panel is a
sheet at the bottom that moves up with the keyboard. One open makes exactly one LLM request.

4.1 **Do:** in a WhatsApp chat with Gboard open, tap the button. **See:** the chat dims, the panel
slides up directly above the keyboard (it may cover the message box; expected), its top not under
the status bar, "Writing 3 replies…" then 3 cards. The chat's keyboard may close. **Send:**
screenshot.

4.2 **Do:** scroll inside the panel; repeat with Gboard's emoji/clipboard strip showing and in
landscape. **See:** all 3 cards, the instruction row and "Delete this chat's history" reachable,
nothing cut off. **Send:** screenshot per case; say how many cards should fit without scrolling if
it is too tall or short (cap: 480 dp).

4.3 **Do:** tap the dimmed area above the panel; open it again and press Back. **See:** it closes
both times (Back may first close the keyboard), nothing sent; afterwards the WhatsApp field has
the cursor and the button returns once the keyboard is open. **Send:** whether the keyboard
reopened by itself or needed a tap.

4.4 **Do:** tap the panel's Instruction field and type "say I'll call at 6". **See:** Gboard
types into the panel field (not WhatsApp's box); the panel stays above the keyboard. **Send:**
screenshot and
`adb shell dumpsys input_method | grep -E "mCurFocusedWindow|mServedView|mInputShown"` if the
keyboard never comes or typing goes to WhatsApp.

4.5 **Do:** tap Regenerate. **See:** keyboard closes, progress bar, then 3 cards that follow the
instruction. **Send:** the error card text if any.

4.6 **Do:** long-press a word in the instruction field. **See:** selection handles may appear; no
crash. **Send:** crash Log.

4.7 **Do:** "Contact note", type a few words, Save; open "Contact note" again. **See:** your text
is there. **Send:** screenshot.

4.8 **Do:** turn Dark mode on, open the panel; turn on Airplane mode and Regenerate; repeat with
Dark mode off. **See:** readable cards, labels and a readable red-toned error card in both modes.
**Send:** one screenshot each.

4.9 **Do:** instruction "write a long detailed reply" in a chat where you write long Hinglish,
Regenerate. **See:** each card shows its whole text wrapped, no "…", no sideways scrolling.
**Send:** screenshot.

4.10 **Do:** scroll down, "Delete this chat's history" → Cancel; again → Delete. **See:** an
in-panel confirmation (no pop-up), then "Saved messages for this chat were deleted." **Send:**
screenshot.

4.11 **Do:** open the panel in a generic app (Gmail compose). **See:** no "Contact note" button and
no delete link. **Send:** screenshot.

4.12 **Do:** note "requests today" in settings. Open the panel, wait for cards, rotate to landscape
and back. **See:** same cards, no new progress bar, count up by exactly 1. **Send:** counts before
and after.

4.13 **Do:** with cards shown, toggle Dark mode in quick settings (and back), or
`adb shell cmd uimode night yes` / `no`, or `adb shell settings put system font_scale 1.3` / `1.0`.
**See:** the panel stays, recolours or resizes, same cards, count up by exactly 1 since opening.
**Send:** counts, and which change added a request.

4.14 **Do:** have someone message you while the panel is open. **See:** same cards, count up by
exactly 1. **Send:** counts.

4.15 **Do:** open the panel and tap Close while "Writing 3 replies…" shows. **See:** it closes and
no new feedback log entry appears for that open (the request may still count). **Send:** feedback
log screenshot.

4.16 **Do:** open the panel and wait 30 s without touching it, then tap its instruction field.
**See:** it stays open the whole time. **Send:** Log and `adb shell dumpsys accessibility | grep
-A40 "Windows"` taken while open, if it closes by itself.

4.17 **Do:** with the panel open (also while still loading), press the side key, or let the screen
time out; wake. **See:** nothing of the chat, no panel and no button on the lock screen or PIN pad
(Log `screen off`); after unlock the chat without the panel. Repeat with only the button showing:
no button on the lock screen. **Send:** Log, and what was visible.

4.18 **Do:** with the panel open press Home; open it again and press Recents. **See:** it closes at
once (`panel closed reason=window_gone`), not shown over the launcher or on return. **Send:** Log.

4.19 **Do:** with the panel open pull the notification shade down and up. **See:** closing is
fine. **Send:** whether it closed.

4.20 **Do:** with the button, then the panel showing, turn Replyly off in Accessibility (or
`adb shell settings put secure enabled_accessibility_services ""`, then on again in Settings).
**See:** button and panel disappear. **Send:** screenshot, Log.

4.21 **Do:** in split screen with the panel open, tap the other app's half. **See:** the panel
closes (fine). **Send:** what happened.

## 5. Insert

5.1 **Do:** in WhatsApp type "draft" in the field, tap the button, tap a card. **See:** the panel
closes, the field holds exactly the suggestion ("draft" replaced), cursor at the end, **nothing
sent**. **Send:** app version (Settings > Apps > WhatsApp) and Log if no text appears.

5.2 **Do:** repeat 5.1 in WhatsApp Business and Telegram. **See:** the same. **Send:** as 5.1.

5.3 **Do:** use the instruction field first (4.4), then tap a card. **See:** the text lands in the
chat's field, not in the panel. **Send:** screenshot, and whether "Could not find the chat's
message box" showed.

5.4 **Do:** open the panel, press Home, open WhatsApp and another chat. **See:** the panel closed
on Home, so nothing lands in the other chat. **Send:** screenshot.

## 6. Parsers and dumps

How to take a dump: [PARSER_FIXTURES.md](PARSER_FIXTURES.md), section 1. "Send" here always means
the dump JSON plus one line saying what was wrong.

6.1 **Do:** settings → long-press the title "Replyly settings". **See:** "Debug: screen dumps".
**Send:** tell the CoS if nothing happens.

6.2 **Do:** with the service off, open the debug screen; tap "Open accessibility settings", turn it
on, return. **See:** red "Accessibility service is NOT enabled" and a disabled Dump button, then
"Accessibility service is running". **Send:** screenshot.

6.3 **Do:** delay chips 5/10/15 s (10 s selected) → "Dump in 10 s" → switch to a WhatsApp chat.
**See:** toast "Replyly: dump saved (com.whatsapp)" (One UI may hide toasts from background apps:
say so if none), and the dump on top of the list. **Send:** the toast text if it says "no active
window to read".

6.4 **Do:** repeat 6.3, but after switching, tap WhatsApp's field so the keyboard is open when the
countdown ends (use 15 s if needed). **See:** the same messages as 6.3. **Send:** the dump if it
says "Not recognised as a chat", and which delay you needed.

6.5 **Do:** on a dump: Share, Copy, Save, Delete; with scramble off, then on. **See:** share sheet
with JSON; clipboard toast with hidden preview; file in My Files > Downloads > Replyly; delete asks
first; a red warning line only while scramble is off. **Send:** which one failed.

6.6 **Do:** scramble on, Share a dump to yourself. **See:** no readable word of any message or name
(small words included). Only exact chrome lines stay (times, "TODAY", "online", "last seen …",
"N members", "typing…", the encryption notice, "Edited", Telegram's last "Sent/Received … at …"
line), plus digits (phone numbers: check them), emoji and punctuation; one name scrambles the same
everywhere. **Send:** the scrambled dump.

6.7 **Do:** dump a WhatsApp 1:1 chat with messages both ways. **See:** `Parser: whatsapp:view-ids`,
`Title:` = contact, `Group: no`, `←`/`→` right, each bubble's time. **Send:** the dump if the route
is `whatsapp:geometry` or anything is wrong.

6.8 **Do:** check the times in 6.7. **See:** `[9:15 pm]` exactly as in the bubble. **Send:** dump
if missing or with extra words.

6.9 **Do:** dump a group with 2+ people, including two messages in a row from one person. **See:**
`Group: yes`, right senders, the second of the pair with the same sender. **Send:** dump.

6.10 **Do:** in a test group, scroll so only long incoming messages show (none of yours), dump.
**See:** all `←`, none `→ me`. **Send:** dump.

6.11 **Do:** dump a group while "X is typing…" shows. **See:** `Group: yes`, senders from labels or
`?`, never the group name. **Send:** dump.

6.12 **Do:** dump a group with someone not in your contacts. **See:** their sender is the phone
number. **Send:** dump; say if you would rather see the "~ Name".

6.13 **Do:** reply to a message (swipe right), send, dump. **See:** the reply line shows only your
new text, not the quote. **Send:** dump.

6.14 **Do:** dump a chat showing the encryption notice, "TODAY", "N UNREAD MESSAGES", a deleted,
an edited and a forwarded message. **See:** none of those words as messages. **Send:** dump.

6.15 **Do:** send yourself "Today", "10:30", and "ok" twice in a test chat, dump. **See:** all four
as messages (two `→ me ... ok`). **Send:** dump.

6.16 **Do:** dump the WhatsApp chat list; dump a 1:1 chat in WhatsApp Business. **See:**
`Not recognised as a chat.`; then `whatsapp:view-ids` with `Chat key: com.whatsapp.w4b:<name>`.
**Send:** dumps.

6.17 **Do:** dump a Telegram 1:1 chat (phone in English). **See:**
`Parser: telegram:cell-descriptions`, right title (never a pinned message's text), sides, times,
oldest first. **Send:** dump, plus the `bounds` of the `RecyclerView` node in the JSON, if the
route is `telegram:geometry`, there are no messages, or the title is wrong. Repeat in a chat with
a pinned message.

6.18 **Do:** dump a Telegram group; in a test group have one person send two messages in a row,
the second on two lines ("Bring water" ⏎ "and snacks"). **See:** `Group: yes`, right senders, the
second message as `Bring water⏎and snacks` from the same sender. **Send:** dump, and whether that
cell's `contentDescription` starts with the sender's name.

6.19 **Do:** have someone send a two-line message in a Telegram 1:1 chat, dump. **See:** one
message with both lines, the contact as sender. **Send:** dump.

6.20 **Do:** forward a message into a Telegram group and reply to one, dump. **See:** no
"Forwarded from …" line; say whether the quoted text shows in the reply. **Send:** dump.

6.21 **Do:** dump Telegram screens with "Today", "X joined Telegram", "Unread messages". **See:**
none as messages. **Send:** dump.

6.22 **Do:** dump a DM in Instagram, LinkedIn or Slack. **See:** `Parser: generic:visible-text`,
`History: no`, visible messages with `←`/`→` (Slack shows all on the left: expected). **Send:**
dump.

6.23 **Do:** keep every dump from 6.7–6.22. The CoS uses them offline to check the geometry
fallback (used after app updates when ids change). Nothing to tap.

## 7. History

Files: `adb shell run-as com.paragrudani.replyly ls -la databases no_backup`.

7.1 **Do:** with the service on, open a WhatsApp chat, wait 2 s, run the `ls` above, then
`adb shell run-as com.paragrudani.replyly head -c 16 databases/history.db | xxd`. **See:**
`databases/history.db` and `no_backup/db_passphrase.wrapped` (about 100 bytes); random bytes, not
`SQLite format 3`; one `new database passphrase reason=NO_SAVED_PASSPHRASE` Log line on first run
only. **Send:** the `ls` output, the `xxd` line, Log.

7.2 **Do:** stay still in a WhatsApp chat for 2 s, then receive or send a message. **See:** about
1 s after the screen settles, `capture merged inserted=<n> edited=0` (n = visible messages first,
then 1); nothing new logs `capture unchanged`. **Send:** Log with timestamps if the delay is over
2 s or lines repeat many times a second while the screen is still.

7.3 **Do:** scroll up a few screens, then back down. **See:** `inserted=` for older messages on the
way up, none on the way down. **Send:** Log.

7.4 **Do:** keep someone typing for 15 s (or play a GIF/voice note), then send a message yourself
while they type. **See:** a capture line about every 3 s (not several a second, not none), and
`capture merged inserted=1` within about 3 s of your message. **Send:** Log with timestamps.

7.5 **Do:** in a busy group, note the newest message M, leave, let a screen of new messages
arrive, open it again, wait 2 s, then scroll up slowly one screen at a time with 2 s pauses.
**See:** every `capture merged` says `edited=0` (unless someone really edited); M keeps its text.
**Send:** Log and how many screens you scrolled.

7.6 **Do:** open a chat, lock the phone 5 minutes while someone messages you, unlock and go back.
**See:** `capture merged` within about 1 s, no `capture failed`. **Send:** Log.

7.7 **Do:** leave the phone idle overnight with the service on, then open a chat. **See:** capture
still works. **Send:** whether Accessibility still showed Replyly on, and any
`capture failed: <ClassName>` lines.

7.8 **Do:** after inserting a suggestion, edit it and send it yourself; wait 2 s. **See:** Log
`feedback final message attached`, and the newest feedback log entry shows "Sent: <your text>".
**Send:** Log, feedback log screenshot.

7.9 **Do:** after "Delete this chat's history" (4.10), ask for suggestions in that chat; open
"Show prompt" in the feedback log. **See:** only on-screen messages in the prompt; the contact note
still there. **Send:** whether older messages appeared.

7.10 **Do:** in a 1:1 chat with a long history, wait 2 s at the bottom. Jump far up (tap the date
header, or fling up several screens) so nothing from the first screen is visible; wait 2 s, scroll
down one screen, wait 2 s. Go back to the bottom (down-arrow button), wait 2 s, focus the input and
tap the button. **See:** the suggestions answer the newest messages at the bottom, not the old ones
you read; in "Show prompt" (feedback log) the last message is the bottom bubble on screen. Then send
a message using a suggestion and wait 2 s: Log `feedback final message attached`. **Send:** Log from
the first step, and which messages the suggestions answered.

## 8. LLM and models

Needs an OpenRouter key with credit and a WhatsApp chat with at least 10 recent messages, ideally
Hinglish or Gujlish. Note the latency (stopwatch from tap to cards) each time.

8.1 **Do:** settings → endpoint `https://openrouter.ai/api/v1`, model `anthropic/claude-haiku-5.5`,
Save. Open a WhatsApp 1:1 chat, tap the field, tap the button. **See:** 3 cards within about 2 s,
each a different intent (e.g. agree / decline / ask). **Send:** latency, error card text.

8.2 **Do:** read the cards. **See:** same language and script as you write here (Hinglish stays
Latin, no Devanagari even if they write Devanagari); your length, tone and emoji; no invented
times, places or promises. **Send:** pass/fail per check.

8.3 **Do:** instruction "say I'll call at 6", Regenerate. **See:** all 3 follow it. **Send:**
latency, pass/fail.

8.4 **Do:** repeat 8.1–8.3 in a WhatsApp group and a Telegram chat. **See:** the same. **Send:** as
above.

8.5 **Do:** repeat 8.1–8.4 with `google/gemini-3.8-flash` and `~openai/gpt-mini-latest`. **See:**
the same. **Send:** latency and pass/fail per model.

8.6 **Do:** change one character of the key (Replace), tap the button. **See:** "API key rejected
(401)" with "Open settings". Restore the key. **Send:** the exact card text.

8.7 **Do:** model `does-not-exist/model`, tap the button. **See:** an error (400) "Request
rejected. Check the model id in Settings." or (404) "Model or endpoint not found". Restore it.
**Send:** the exact card text.

8.8 **Do:** Airplane mode on, tap the button; tap Retry after turning it off. **See:** "Could not
reach the API" or "The model took too long" within about 10 s, with Retry; Retry works online.
**Send:** the text, and the time if over 60 s.

8.9 **Do:** note any "The reply was cut off" card. **See:** rare. **Send:** the model id and
whether it happens every time.

8.10 **Do:** if a model usually takes over about 2 s, turn on settings → "3 parallel calls (faster
on slow models)", Save, repeat 8.1 and 8.3. **See:** cards with intents agree / decline / ask;
the request count still goes up by 1 per tap. **Send:** latency off vs on, and which you prefer
(keep it on only if at least 0.5 s faster and still sounds like you).

## 9. Settings, cost and feedback log

9.1 **Do:** open Replyly settings, scroll top to bottom. **See:** Usage card (requests today
`0 / 200`, tokens this month), sections LLM (with the parallel-calls switch), Prompt, Limits and
privacy, Blocked apps, Blocked chats, buttons "Feedback log" and "Setup steps". **Send:** two
screenshots (top, bottom).

9.2 **Do:** look at the model chips. **See:** the three shortlist ids wrap, not cut off; the current
one highlighted. **Send:** screenshot.

9.3 **Do:** type a few lines in About me. **See:** the field stays above Gboard; the counter goes
up. **Send:** screenshot of any hidden field.

9.4 **Do:** change Daily request limit to `50`, then toggle the parallel-calls switch. **See:** a
bar "Unsaved changes / Discard / Save" above the gesture bar; Save hides it, Usage shows `0 / 50`;
the switch keeps its state after force-stop and reopen. **Send:** screenshot.

9.5 **Do:** enter Retention days `0` (or Endpoint `http://x`), Save. **See:** the field turns red
with a message, the bar says "Fix the marked fields", nothing saved (reopen to check). **Send:**
screenshot.

9.6 **Do:** dark mode on, reopen settings. **See:** all text readable. **Send:** screenshot of any
low-contrast part.

9.7 **Do:** with a key saved: Replace → `bad key` → Save key; then Cancel. **See:** an error, field
stays open; Cancel shows "API key: saved ••••••••" again. The key is never shown, even partly.
**Send:** screenshot.

9.8 **Do:** force-stop and reopen. **See:** "API key: saved". **Send:** screenshot.

9.9 **Do:** remove the key, open the panel, tap "Open settings". **See:** "No API key yet", then
Replyly opens directly on the **settings screen** (not onboarding or debug), and the panel is gone.
Repeat with the debug screen left open in Replyly beforehand: still lands on settings. **Send:**
screenshot of where it landed, and the Android version if nothing opened.

9.10 **Do:** limit `2`, Save, ask for suggestions 3 times. **See:** two work; the third shows
"Daily limit reached"; Usage shows `2 / 2` in red. **Send:** screenshot.

9.11 **Do:** check settings the next morning (switch away and back if the phone slept). **See:**
`0 / 2`; tokens reset only on the 1st. **Send:** screenshot.

9.12 **Do:** after 3 taps, compare the Usage card before and after. **See:** requests up by 3
(also with parallel calls on), tokens up. **Send:** both counts.

9.13 **Do:** ask for suggestions, insert one, edit and send it; settings → Feedback log. **See:**
the entry with date, chat, 3 suggestions, ✓ on the inserted one, "Sent: <your text>"; "Show
prompt" expands. **Send:** screenshot (blur the chat).

9.14 **Do:** in a generic app not in Replyly's built-in list (Discord, Teams, Snapchat), ask for
suggestions, then "Show prompt" on that entry. **See:** the prompt names the app (e.g. "Discord"),
not its package name. **Send:** the app and what the prompt called it.

9.15 **Do:** force-stop Replyly, reopen the feedback log. **See:** the entries are still there (the
log is stored now). **Send:** screenshot.

9.16 **Do:** Clear log → confirm. **See:** empty list. **Send:** screenshot.

## 10. Security checks

10.1 **Do:** open a banking app (YONO SBI, iMobile, a UPI app) and tap a field. **See:** no button
(`reason=BLOCKED_APP`). **Send:** the app's package name (`adb shell pm list packages | grep -i
<name>`) if the button shows, so it can be added to `policy/SensitiveApps.kt`. Do this for every
banking, UPI or wallet app you have.

10.2 **Do:** Chrome → new Incognito tab → tap the address bar → tap the button. **See:** "Replyly
is off here"; Log `secure check state=SECURE code=6` and `tap outcome=blocked
reason=SECURE_WINDOW`. Then a normal tab: no notice, no `secure check` line. **Send:** Log, and
whether the screen flashes or stutters on a tap.

10.3 **Do:** Telegram secret chat (if you have one): tap the field and the button. **See:**
`reason=SECURE_WINDOW`. **Send:** Log.

10.4 **Do:** note any `state=UNKNOWN` (it blocks the tap). **See:** none. **Send:** the `code=`
value (2 or −1: do 2.1; 1: internal error; 3: called too often; timeout: no answer in 1.5 s).
`state=GONE code=4|5` (window gone mid-tap) shows "Replyly can't read a chat here" and is fine.

10.5 **Do:** debug screen → "Dump in 10 s" → switch to the Chrome Incognito tab (and a banking
app). **See:** toast "Replyly: not saved: this screen is protected (secure window)" (banking:
"blocklisted app" or "banking app"), and no new dump. **Send:** the toast text, and the dump if
one was saved.

10.6 **Do:** open a password field (WhatsApp > Settings > Account > Two-step verification, or a
login screen). **See:** no button (`reason=PASSWORD_FIELD`), and no `capture merged` line.
**Send:** Log.

10.7 **Do:** add a test chat to Blocked chats (`com.whatsapp:<name>`) and an app to Blocked apps.
Open them, tap the button, try a dump, wait 2 s. **See:** "Replyly is off here" or no button, a
"not saved: this screen is protected" toast, and no `capture merged` line. Remove them afterwards.
**Send:** Log, toast text.

10.8 **Do:** open Slack or Instagram DMs and wait 2 s. **See:** no `capture merged` line (generic
apps get no history). **Send:** Log.

10.9 **Do:** `adb shell run-as com.paragrudani.replyly head -c 16 databases/feedback.db | xxd`
(after at least one suggestion). **See:** random bytes, not `SQLite format 3`. **Send:** the
output line.

10.10 **Do:** `adb shell run-as com.paragrudani.replyly cat files/datastore/api_key.preferences_pb | strings`.
**See:** only `api_key_blob` and a Base64 string, never text starting with `sk-or-`. **Send:**
yes/no: does `sk-or-` appear (not the output).

10.11 **Do:** Settings > Apps > Replyly > Storage > Clear storage; re-enable the service (and redo
onboarding), open a chat. **See:** one `new database passphrase reason=NO_SAVED_PASSPHRASE`, then
normal capture; no crash or restart loop. **Send:** crash Log.

10.12 **Do:** with history saved, run
`adb shell run-as com.paragrudani.replyly sh -c 'rm -f databases/history.db-wal databases/history.db-shm; head -c 4096 /dev/urandom > databases/history.db'`,
then `adb shell am force-stop com.paragrudani.replyly` (turn the service back on if needed), open
a chat, wait 2 s, then another chat. **See:** exactly one `history db unreadable, recreating:
SQLiteDatabaseCorruptException`, no `new database passphrase`, then `capture merged`; no second
`recreating`; no crash loop. **Send:** Log and crash Log (also if there is no `recreating` line
but everything works).

10.13 **Do:** uninstall Replyly, install again, set up, open a chat. **See:** by design the key,
counters and history are gone (one `NO_SAVED_PASSPHRASE` line, a fresh `capture merged`).
**Send:** anything that survived the uninstall.

## 11. Polish

Look and feel from the UI polish pass (issue #17). Steps are numbered by area so the parallel
polish PRs never collide: `11.A…` foundation (icon, colours, dark mode, animations, font size),
`11.B…` floating button, `11.C…` panel, `11.D…` onboarding, `11.E…` settings. Each PR adds its
steps under its own letter; numbers inside a letter only grow.

### Foundation (polish #0)

11.A1 **Do:** after installing, look at Replyly on the home screen and in the app drawer.
**See:** an ink-blue icon with a white chat bubble (two blue text lines) and a yellow four-point
sparkle top right; nothing clipped by the icon shape, the mark roughly centred. **Send:**
Screenshot of the home screen.

11.A2 **Do:** Settings > Wallpaper and style > Color palette: turn on "Themed icons" (or Home screen
settings > Themed icons). **See:** the Replyly icon turns into the One UI themed colours with the
same bubble and sparkle, text lines cut out. **Send:** Screenshot of the home screen.

11.A3 **Do:** long-press the Replyly icon, or drag it into a folder. **See:** the round/folder
version still shows the whole mark. **Send:** Screenshot if anything is cut off.

11.A4 **Do:** open Replyly settings. **See:** "replyly" with a small yellow sparkle above
"Settings"; long-pressing it still opens the debug screen. **Send:** Screenshot.

11.A5 **Do:** Settings > Appearance > turn on "Material You colours", go back to the home screen
and return; then open a WhatsApp chat and tap the button. **See:** settings, the floating button
and the panel switch to your wallpaper colours (buttons, switches, the button fill). Turn it off
again: everything goes back to ink blue. Also force-stop Replyly with Material You on and open
it again: a brief flash of ink blue before the wallpaper colours appear is possible (the setting
loads after the first frame). **Send:** two Screenshots (on and off) of settings and of the
button over a chat, and say whether the cold-start flash is noticeable.

11.A6 **Do:** with "Material You colours" on, change the wallpaper (Settings > Wallpaper and
style) to one with a clearly different colour, then reopen Replyly. **See:** Replyly picks up the
new palette. **Send:** Screenshot if it keeps the old colours.

11.A7 **Do:** turn on dark mode (quick settings), open Replyly settings and a WhatsApp chat with
the button and the panel. **See:** dark backgrounds, light text, a light periwinkle button with a
dark mark; all text readable. **Send:** Screenshots of settings, button and panel.

11.A8 **Do:** Settings > Accessibility > Visibility enhancements > turn on "Remove animations"
(on some One UI versions: Advanced settings > Remove animations). Use the button and panel.
**See:** everything still works, things appear without animating. Turn it off afterwards.
**Send:** anything that looks stuck half-way.

11.A9 **Do:** Settings > Display > Font size and style: set the font size two steps larger; open
settings, onboarding and the panel. **See:** no text cut off or overlapping. **Send:** Screenshots
of anything clipped.

### Floating button (polish #3)

Use WhatsApp and Telegram with Gboard (or Samsung Keyboard).

11.B1 **Do:** open a WhatsApp chat and tap the message field so the keyboard opens; close the
keyboard (Back) and open it again a few times. **See:** the button grows and fades in (about a
sixth of a second) and shrinks and fades out when the keyboard closes; smooth, no flicker, no
jump to another spot, never left half-visible. **Send:** a screen recording if it stutters or
blinks.

11.B2 **Do:** with the keyboard open, look at the button against the keyboard and the input bar.
**See:** a round ink-blue button (light periwinkle in dark mode) with the bubble + sparkle mark,
about 8 dp above the keyboard top, on the right, not over the send button; its shadow is soft and
not cut off in a square. **Send:** a screenshot.

11.B3 **Do:** press and hold the button for a second without moving, then let go. **See:** it
shrinks a little while held; on release one short, light tick (the same as tapping a navigation
key, `VIRTUAL_KEY`; not a long buzz) and the panel opens as before. Turn off Settings > Sounds and vibration > System
vibration > Touch interactions and repeat: no tick. **Send:** what the haptic feels like if it is
missing, long or strong.

11.B4 **Do:** put a finger on the button and drag it slowly up to the top of the screen, then down
onto the keyboard. **See:** it follows the finger smoothly, grows slightly while dragged, stops
just below the status bar at the top and, at the bottom, at its usual spot above the message bar
(never over the status bar, the send button or the keys), and stays where you let go. One very
short, crisp tick when the drag starts (a "segment tick", lighter than the tap in 11.B3); none
while it moves. **Send:** a screen recording, and what the drag tick feels like if it is missing
or the same as the tap.

11.B5 **Do:** drag the button a few centimetres up and release; also try a tiny wiggle and a
sideways slide before releasing. **See:** no panel opens after any drag (a drag is never a tap),
and the button does not dim. A plain tap still opens the panel, and only once per tap. **Send:**
which gesture opened the panel by mistake.

11.B6 **Do:** in WhatsApp, drag the button to the middle of the chat. Close the keyboard, open it
again; go Home, come back. **See:** the button returns to the same height above the keyboard
each time. Then open Telegram (or another chat app): there it sits at the default spot (or where
you put it in that app). Switch back to WhatsApp: still your spot. Restart the phone (or
force-stop Replyly and turn it back on in Accessibility): the WhatsApp spot is remembered.
**Send:** which app lost its spot.

11.B7 **Do:** in WhatsApp with the button dragged up, switch to a taller keyboard layout (Gboard
emoji or number row, or one-handed mode). **See:** the button keeps the same gap above the
keyboard and moves with it. **Send:** a screenshot if it ends up over the keys.

11.B8 **Do:** tap the button and watch it while the chat is being read. **See:** it dims until
the panel opens (or the "can't read a chat" notice appears), then is back to full colour when the
panel closes. **Send:** a screenshot if it stays dim.

11.B9 **Do:** turn on TalkBack, open a chat with the keyboard, and swipe to the button. **See:**
TalkBack says "Replyly: suggest replies, button". Swipe up then right (or use the TalkBack menu >
Actions): "Move up" and "Move down" are listed and each moves the button about one button
height; at the highest spot only "Move down" is listed, at the lowest only "Move up"; double-tap
still opens the panel. **Send:** what TalkBack reads, if different.

11.B10 **Do:** turn on "Remove animations" (see 11.A8) and repeat 11.B1 and 11.B4. **See:** the
button appears and disappears at once, dragging still works. **Send:** anything stuck half-way.

11.B11 **Do:** with the keyboard open in WhatsApp, drag the button as high as it goes and look
closely at the top edge next to the camera punch-hole. **See:** the whole button and its shadow
sit below the status bar (clock, icons) and below the punch-hole, with a small gap; nothing is
cut off. Repeat in Telegram. **Send:** a screenshot of the button at its highest point.

11.B12 **Do:** turn auto-rotate on, open a WhatsApp chat in landscape and tap the message field.
**See:** the button shows above the keyboard on the right, fully on screen, even though there is
little room; dragging moves it only a little or not at all, and it never covers the keys or the
send button. Rotate back to portrait: the button is back at your portrait spot (if you dragged
it in landscape, the portrait spot may have changed too; that is a known limit). **Send:** a
landscape screenshot, and say whether the portrait spot changed when you had not dragged in
landscape.

11.B13 **Do:** in WhatsApp, start dragging the button upwards and, without lifting the finger,
make the keyboard taller or shorter (with another finger tap Gboard's emoji key, or swipe the
suggestion strip so the number row shows). Then keep dragging and let go. **See:** while the
keyboard changes the button may overlap the keys for a moment; as soon as you move the finger
again it jumps back inside the new range, and where you let go it sits above the keyboard and
the message bar. **Send:** a screen recording if it stays over the keys after you let go.

11.B14 **Do:** open a WhatsApp chat with the keyboard and the button showing. Pull down quick
settings and toggle Dark mode, then close quick settings (keyboard still open). **See:** the
button switches straight away to the dark look (light periwinkle with a dark mark), and back to
ink blue when you toggle again, without closing the keyboard. **Send:** a screenshot if the
button keeps the old colours until the keyboard is closed and opened.

11.B15 **Do:** in WhatsApp, drag the button down as far as it goes (it stops just above the
message bar). Now type a long message, or several lines with Shift+Enter, so the message bar
grows to 3–4 lines. Close and reopen the keyboard. **See:** the button is never over the message
bar or the send button: after the bar grows it sits above the taller bar. Delete the text: it
goes back to your spot. **Send:** a screenshot if the button covers the bar or the send button.

11.B16 **Do:** with the button showing, press the power button to turn the screen off, then
turn it on again (lock screen visible, do not unlock yet). **See:** no Replyly button, not even
a faded one, on the lock screen. Unlock: the button comes back over the chat as usual. **Send:**
a photo of the lock screen if the button shows there.

### Suggestion panel (polish #2)

Panel motion, gestures and the instruction chips. Do 11.C1–11.C10 with "Remove animations" OFF,
then 11.C11 with it on. Haptics need "Touch interactions" vibration on (Settings > Sounds and
vibration > System sound/vibration control).

11.C1 **Do:** in a WhatsApp chat, tap the button. **See:** the chat dims while the sheet slides up
from the bottom edge with a soft settle (a hint of overshoot, no wobble), in about half a second;
no flash of the sheet at its final place before it slides. **Send:** a screen recording
(Quick settings > Screen recorder) if it jumps, stutters or bounces more than once.

11.C2 **Do:** watch the panel while it loads. **See:** 3 grey placeholder cards with a soft light
band sweeping left to right, about once a second, and no spinner; then the 3 real cards fade and
slide up one after another (a short, even ripple, top card first). **Send:** a screen recording if
the shimmer is choppy or the cards appear all at once.

11.C3 **Do:** look at the coloured tags on the cards (light and dark mode). **See:** "Agree" green,
"Decline" red, "Ask" amber, any other word grey-blue; all readable. **Send:** Screenshot.

11.C4 **Do:** tap a card. **See and feel:** the card turns blue briefly, one light tick from the
vibration motor, the sheet slides down, and WhatsApp's message box holds the text (nothing sent).
**Send:** whether you felt the tick, and a screen recording if the panel closes before the card
turns blue or the text is missing.

11.C5 **Do:** open the panel again and press and hold a card for about half a second. **See and
feel:** a stronger vibration, a dark "Copied" pill at the top of the panel for about 1.5 s, the
panel stays open, WhatsApp's box unchanged. Then long-press WhatsApp's message box and paste.
**See:** the copied reply. Open Gboard's clipboard strip. **See:** the clip is hidden or shown as
dots (marked sensitive). **Send:** Screenshot of Gboard's clipboard strip.

11.C6 **Do:** tap Regenerate (the round arrow). **See:** the cards fade down and out, the
placeholders shimmer, then 3 new cards ripple in. Replyly settings' usage count for today goes up
by exactly one per tap (note it before, check after). **Send:** the counts, and a screen recording
if the cards jump instead of fading.

11.C7 **Do:** tap the "Shorter" chip. **See:** it shows a check mark, the cards regenerate once,
the replies get shorter. While the placeholders shimmer, tap "Shorter" and "Add emoji" quickly
several times. **See:** the chips are greyed out and nothing changes; the usage count in settings
goes up by exactly one for the first tap. Tap "Shorter" again when the cards are back. **See:** the check
goes away and the cards regenerate once without it. **Send:** the counter before and after.

11.C8 **Do:** tap the instruction field (Gboard opens), type "say I'll call at 6", then tap the
"In English" chip without closing the keyboard. **See:** the keyboard closes, the chip row stays
visible above Gboard while typing (not hidden behind it), and the new replies follow both the
chip and the text. Repeat with Gboard's emoji strip open. **Send:** Screenshot with the keyboard
open, and one if the chips are under the keyboard.

11.C9 **Do:** with the panel open, put a finger on the panel's top bar and drag it down past about
a third of its height, let go. Open it again, scroll the cards to the top and keep dragging down
inside the cards; open it again and flick down quickly from the top bar; open it again and drag
down only a little, let go. **See:** the first three close the panel smoothly (sliding down, the
dim fading), the last one springs back. **Send:** a screen recording of anything that sticks half
way or closes when you only meant to scroll the cards.

11.C10 **Do:** tap the dimmed chat above the panel; open it again and press Back. **See:** both
slide the panel down (not a cut) and the WhatsApp field keeps its cursor. **Send:** a screen
recording if either closes abruptly or leaves a dim layer behind.

11.C11 **Do:** turn on "Remove animations" (11.A8) and repeat 11.C1, 11.C2, 11.C4 and 11.C9.
**See:** the panel appears and closes at once, the placeholders are static (no sweeping band), the
cards appear together; tapping, long-press copy and swipe-down still work. **Send:** anything that
still animates or gets stuck. Turn it off afterwards.

11.C12 **Do:** with TalkBack on (Settings > Accessibility > TalkBack), open the panel and swipe
through it. **See:** each card is read once as its tag and text ("Agree. Haan pakka…"), with
"double-tap to insert"; the TalkBack menu (swipe up then right, or three-finger tap) offers
"Insert" and "Copy"; the chips read as "Shorter, checkbox/selected" style toggles; the arrow reads
"Regenerate replies"; "Copied" and errors are announced. **Send:** a TalkBack audio/screen
recording of anything read twice, unlabelled or missing.

11.C13 **Do:** in a WhatsApp chat, open the panel and tap the instruction field so Gboard opens (also
try with Gboard's emoji or clipboard strip open, and with display size set to the largest under
Settings > Display > Font size and style / Screen zoom). **See:** the whole top bar (grabber, chat
name, note and Close icons) stays fully below the status bar (clock, battery); the cards scroll in
the space between. **Send:** a screenshot with the keyboard open if any part of the top bar is
under the status bar or cut.

11.C14 **Do:** turn on all four chips, then paste or type a long instruction (more than 200
characters) into the field. **See:** a small "n/m" counter appears under the right end of the field
when about 30 characters are left; typing stops at the limit and the counter turns red; an emoji at
the limit is either whole or not added (never a broken box). Now turn one chip off and on again
with a full field. **See:** turning it on shortens the text in the field (visible), and the new
replies follow the whole field. **Send:** a screenshot of the counter, and one if a broken
character appears.

### Onboarding welcome flow

11.D1 **Do:** uninstall Replyly (or Settings > Apps > Replyly > Storage > Clear data), install and
open it. **See:** the welcome flow opens on "Replies in your style" with three page dots; "Skip"
top right, "Next" bottom right. Swipe left and right between the three pages with your thumb.
**See:** the page follows your finger, settles softly (no hard jump, no bounce past the edge),
the neighbouring page fades in; the dots stretch to the current page. **Send:** a screen
recording if a swipe stutters or settles on the wrong page.

11.D2 **Do:** on the first page tap "Next", "Next", then "Back"; then go to page 1 and tap "Skip";
then use the system Back gesture on the checklist. **See:** each button slides to the right page
(about half a second); "Skip" lands on "Set up your phone"; Back on the checklist goes to the
privacy page, Back on the first page leaves the app. **Send:** what happened instead.

11.D3 **Do:** on "Set up your phone", tap "Open Accessibility", turn Replyly on, come back with
Back. **See:** within a second step 2 (and step 1) tick: the ring fills blue, then a white check
draws itself; "N of 5 done" goes up. Repeat for "Allow" (battery: accept the dialog), "Open
battery settings" + "I've done this" (sleeping apps), and "Open settings" (enter key and model,
then Back). **See:** each step ticks on return without touching anything. **Send:** the step
that did not tick, and whether it ticked after leaving and reopening Replyly.

11.D4 **Do:** Settings > Accessibility > Replyly: turn it off while on the checklist (via the
notification shade quick settings or recents), come back. **See:** step 2 goes back to an empty
ring with "Not done", no crash. Turn it back on: it ticks again. **Send:** a screenshot if the
ring and the text disagree.

11.D5 **Do:** with every step done, reopen Replyly. **See:** it opens on Settings. Tap "Setup
steps". **See:** it opens straight on the checklist (page 3, five ticks shown at once, no
animation), Back returns to Settings. **Send:** a screenshot if it opens on the first page.

11.D6 **Do:** turn on TalkBack and open "Setup steps". Swipe through the checklist. **See:**
TalkBack reads "Set up", the title as a heading, then each step as one item, e.g. "Battery:
done", "Sleeping apps: not confirmed", then its instruction and buttons. While TalkBack is on,
complete one step and come back: TalkBack announces the new state once ("Battery: done"), not
the "N of 5 done" counter as well. Swipe to the
page dots: "Page 3 of 3". **Send:** anything read twice, skipped, or read as "unlabelled".

11.D7 **Do:** with "Remove animations" on (11.A8), page through the welcome flow and complete a
step. **See:** pages change at once and the tick appears at once, nothing half-drawn.
**Send:** a screenshot of anything stuck half-way.

11.D8 **Do:** clear Replyly's data (Settings > Apps > Replyly > Storage > Clear data) with at
least one step not done, open Replyly, and on the first page tap "Skip", then "Skip for now" at
the bottom of the checklist. On Settings, tap "Setup steps". **See:** it opens straight on "Set
up your phone" (page 3), not on "Replies in your style". Press Back once. **See:** you are back
on Settings, without passing through the privacy or first page. Repeat from the checklist's step
5 button ("Open settings") > "Setup steps": same result. **Send:** a screen recording of the
pages Back passes through if it is not straight to Settings.

11.D9 **Do:** turn on auto-rotate. In the welcome flow go to "Your privacy" and rotate the phone
to landscape and back; then do the same on "Set up your phone", once from first run and once
from Settings > "Setup steps". Then turn on Developer options > "Don't keep activities", and for
each of those screens press Home, reopen Replyly from recents, and rotate again. **See:** every
time it stays on the same page, ticks already shown do not replay, and Back from the checklist
still goes where it did before (Settings when opened from "Setup steps", the privacy page on
first run). Turn "Don't keep activities" off afterwards. **Send:** which screen jumped page or
where Back went instead.

### Settings, feedback timeline and "What's new" (polish item 7 and 9)

11.E1 **Do:** install a CI build of Replyly over the one you have (do not uninstall), open
Replyly's Settings (if onboarding shows first, finish it; the sheet waits for Settings). **See:** a "What's new in Replyly" sheet slides up from the bottom with 3 to 4 short
points and a "Got it" button. Tap "Got it", close Replyly from Recents and open it again.
**See:** the sheet does not come back. Settings > About > What's new shows it again on demand.
**Send:** "11.E1 ok", or a Screenshot plus whether it showed 0 times or more than once.

11.E2 **Do:** uninstall Replyly, install it fresh and finish onboarding until settings opens.
**See:** no "What's new" sheet on a fresh install. **Send:** Screenshot if it appears.

11.E3 **Do:** in Settings > Appearance, toggle "Material You colours" on and off; then change
"Daily request limit" and tap "Save" in the bar at the bottom. **See (feel):** a light tick on
each toggle and a short confirm buzz once Save has stored the change; nothing on other taps. Now
type 0 as the daily limit and tap Save: "Fix the marked fields" shows and there is no buzz. **Send:** "too strong",
"too weak" or "none" for each, if not right. (Haptics follow Settings > Sounds and vibration >
System vibration; if that is off you will feel nothing.)

11.E4 **Do:** in Replyly settings, scroll from the top to "About" and back, slowly and with a
fast fling; then tap into "About me" so the keyboard opens. **See:** smooth scrolling with no
jumps or stutter; section headers (AI provider, Your style, Privacy, Limits and usage,
Appearance, About) stay readable; the focused field stays above the keyboard and the Save bar
sits above the keyboard too. **Send:** a screen recording if anything stutters or hides behind
the keyboard.

11.E5 **Do:** Settings > Limits and usage. **See:** a ring that fills to today's share of the
daily limit, with the % inside; at the limit it turns red. With "Remove animations" on (11.A8)
the ring appears filled at once. With TalkBack on, the whole card is one item that reads the
requests, the tokens and the percentage once. **Send:** Screenshot, and what TalkBack reads if it
stops on the ring separately.

11.E6 **Do:** Settings > Privacy > Feedback log after a few suggestions. **See:** entries grouped
under "Today", "Yesterday" or a date; each shows the chat, the app's name (e.g. "WhatsApp", not
"com.whatsapp") with the model, the time in your phone's 12- or 24-hour style (Settings > General
management > Date and time > Use 24-hour format: switch it and reopen the log), the 3 replies
with the one you inserted on a blue background marked "Inserted", and "You sent" with your final
message once it was seen. With TalkBack on, only the inserted reply says "Inserted"; the others
read just their text. **Send:** Screenshot (blur private chats if you like), and the app name if
it shows a package id instead.

11.E7 **Do:** Settings > AI provider > Edit on an OpenRouter profile. **See:** under "Model" a
list of radio rows (the three shortlist ids and "Other model id"), the current model selected;
tapping a row selects it. Tap "Other model id": a "Model id" field appears where you can type any
id; Save keeps it, and reopening the editor shows "Other model id" selected with your id. On an
Anthropic profile the list has the Haiku id and "Other model id"; on an OpenAI profile there is
just the field. **Send:** Screenshot of the editor if a row is cut off or the field is missing.

11.E8 **Do:** leave the Feedback log open across midnight (or set the phone's clock to 23:59 with
automatic time off, open the log, wait two minutes). **See:** entries from before midnight move
under "Yesterday" without leaving the screen. Turn automatic time back on. **Send:** Screenshot if
they still say "Today".

## 12. Provider profiles (issue #18)

Endpoint, key and model now live in a provider profile: where sections 8 and 9 say "settings →
endpoint / API key / model", use Settings → "Provider profiles" → Edit on the selected profile.
You need an OpenRouter key; for 12.4–12.6 also an OpenAI and an Anthropic key with a little
credit. Note the Usage card's "Requests today" and tokens before each test.

12.1 **Do:** with a build from before profiles installed and a key saved, install the new APK over
it and open Settings. **See:** under "Provider profiles" one selected row "OpenRouter",
"OpenAI-compatible · <your model>", no red "No API key". Tap the button in a WhatsApp chat: 3
cards, without entering the key again. **Send:** screenshot of the profiles section; error card
text if any.

12.2 **Do:** tap "Test connection" on the OpenRouter row. **See:** "OK · N ms" within about 3 s;
Requests today goes up by exactly 1 and tokens by a few. **Send:** the text and the usage numbers
before and after.

12.3 **Do:** "Add profile" → preset "OpenAI" → paste an OpenAI key → model: a current reasoning
model from your OpenAI account (one that needs `max_completion_tokens`) → Save. Tap "Test connection" on the new row. **See:** the editor's hint says
"Requests go to https://api.openai.com/v1/chat/completions"; the test shows "OK · N ms". **Send:**
the test text, or the error text.

12.4 **Do:** "Add profile" → preset "Anthropic" (base URL `https://api.anthropic.com/v1`, model
`claude-haiku-5-5`) → paste an Anthropic key → Save → "Test connection". **See:** the hint says
"Requests go to https://api.anthropic.com/v1/messages"; the test shows "OK · N ms". **Send:** the
test text, or the error text.

12.5 **Do:** tap the Anthropic row to select it. In a WhatsApp chat tap the button, then
Regenerate twice. **See:** 3 cards each time within about 2 s, in your language and style, never
"The reply was cut off" or "Could not read the model's reply". Open Settings → Feedback log.
**See:** the newest entries show model `claude-haiku-5-5`. **Send:** latency of each try, any
error card text, pass/fail on style.

12.6 **Do:** select the OpenAI profile and repeat 12.5. Then turn on "3 parallel calls" and repeat
once with the Anthropic profile. **See:** as 12.5; parallel mode still adds 1 request per tap.
**Send:** latency per profile, parallel off vs on.

12.7 **Do:** switch back to the OpenRouter row, tap the button. **See:** cards come back; the
feedback log shows the OpenRouter model again. **Send:** pass/fail.

12.8 **Do:** edit the Anthropic profile, base URL `https://api.anthropic.com` (no `/v1`), Save,
Test. **See:** the hint still ends in `/v1/messages`; "OK · N ms". **Send:** pass/fail.

12.9 **Do:** on the Anthropic profile: Replace the key with a wrong one (change one character),
Save, Test. Then restore the key and set model `claude-does-not-exist`, Save, Test. **See:** "API
key rejected (401). Check the key in Settings." then "The API returned an error (404). Model or
endpoint not found. Check Settings." Each test adds 1 request. Restore the model. **Send:** the
exact texts.

12.10 **Do:** edit any profile → "Add header" → name `X-Replyly-Test`, value `abc123` → Save. Edit
again. **See:** "X-Replyly-Test: value saved", the value is never shown; "API key: saved ••••••••".
Force-stop Replyly and reopen: still the same. Test connection still OK (most providers ignore
unknown headers). Remove the header. **Send:** screenshot of the editor.

12.11 **Do:** while running 12.2–12.9, keep
`adb logcat -v time | grep -i -E "sk-|x-api-key|abc123|authorization"` open. **See:** no lines.
**Send:** any line it prints (blur the key).

12.12 **Do:** delete a profile that is not selected; then delete the selected one. **See:** a
confirmation each time; after deleting the selected one another row becomes selected. With one
profile left, "Delete profile" is disabled. **Send:** pass/fail.

12.13 **Do:** select a profile without a key (add one and skip the key), then open Setup steps.
**See:** step 5 (API key and model) is not done. Select a profile with a key and open Setup
steps again: step 5 is done.
**Send:** pass/fail.

12.14 **Do:** edit any profile → "Add header" twice, so the last header value field is at the
bottom of the editor. Tap that bottom value field so the keyboard opens. Then rotate the phone to
landscape and back. Then press Back (gesture or button). **See:** the editor's top bar sits below
the status bar, never under it; with the keyboard up the focused field stays visible above the
keyboard, with no large empty gap between them; after rotating, the typed text is still there.
Back first closes the keyboard; a second Back closes the editor without saving (edits are
dropped). **Send:** a screenshot with the keyboard up, one after rotating, and say what each Back
did.

12.15 **Do:** edit the OpenRouter profile (which has a saved key), tap the "OpenAI" preset, set a
model, and tap Save without typing a key. **See:** the key field opens with "Enter the key for
this provider" and nothing is saved. Tap Cancel. Then edit it again, tap "Remove" next to the
saved key, then Cancel, and edit again. **See:** "API key: saved ••••••••" is still there.
**Send:** pass/fail.

## 13. Message dates (issue #26, PR 1)

The parsers now read date separators ("TODAY", "YESTERDAY", "THURSDAY", "21 NOVEMBER 2026",
"21/11/2026") and give each message below one that date. Telegram also reads the day from each
cell's last line ("Received yesterday at 9:15 PM"). The debug screen does not print a date per
message yet; it prints one **Notes** line per dump, for example:
`date separators: 2 (2026-11-21, 2026-11-22), 7 of 9 messages dated, 1 replies to me`.
The dates in brackets are the separators read, top to bottom; `?` is a separator whose text the
parser could not turn into a date. All formats below are guesses from public knowledge: the
S23 (One UI, Indian English) decides.

How to take and send a dump: [PARSER_FIXTURES.md](PARSER_FIXTURES.md), section 1. Use a test
chat and share with **scramble off** for these steps: scramble keeps "TODAY"-style separators but
scrambles month names inside Telegram's "Received Nov 19 at …" lines, which hides what 13.6 checks.

13.1 **Do:** open a WhatsApp 1:1 chat with messages from today and yesterday, scroll so both the
"YESTERDAY" and "TODAY" separators are on screen, and take a dump. **See:** the Notes line says
`date separators: 2 (<yesterday>, <today>)` with the right dates, and "N of N messages dated"
except for messages above the first separator. **Send:** the dump, and the exact separator text
WhatsApp shows (capitals, spacing).

13.2 **Do:** in the same chat scroll up to messages older than a week, so a separator with a full
date is on screen; dump. Then scroll to messages 2 to 6 days old (weekday separators); dump.
**See:** the separators show up in the Notes line with the right dates (a weekday is the most
recent past one). **Send:** both dumps, and the exact text of the full-date separator (for example
"21 NOVEMBER 2026", "21 Nov 2026" or "21/11/2026").

13.3 **Do:** scroll slowly in the middle of a long chat and watch the top of the list: WhatsApp
shows a floating date pill there while scrolling. Dump while it is visible (start the dump, then
keep scrolling a little until the countdown ends). **See:** no message line has the pill's text
as its text. The Notes line counts the pill only if it uses the separator's view id
(`conversation_row_date_divider`) or has no id. Then it lists the pill's date next to the inline
divider's, the same date twice with no message between them, for example
`date separators: 3 (2026-11-20, 2026-11-20, 2026-11-21), 6 of 6 messages dated, 0 replies to me`: the two count as
one, and the messages keep their dates. If the Notes line ends with
`dates dropped: separators out of order` and says `0 of N messages dated`, the pill showed a date
that does not match the divider or sat below a message: send that dump. **Send:** the dump; say
whether the pill was on screen when the toast came. In the dump (scramble off), search for the
pill's text and send the `viewId` of the node that holds it (or "none"). Once that id is known,
the parser ignores the pill by its id.

13.4 **Do:** repeat 13.1 in WhatsApp Business. **See:** the same as 13.1, `Parser:
whatsapp:view-ids`. **Send:** the dump.

13.5 **Do:** in a WhatsApp group, long-press one of your messages → Reply, and ask someone to reply
to it by quoting it (or use a second phone). Dump the chat with their reply on screen. **See:**
the Notes line says `1 replies to me` (one per quoted reply of yours on screen); a reply that quotes
someone else does not count. **Send:** the dump, and what the quote's author line says on screen
("You"?).

13.6 **Do:** open a Telegram chat with messages from today, yesterday and more than a week ago;
dump once per day range. **See:** `Parser: telegram:cell-descriptions`, a line
`N cells dated by their own text` equal to the message count, and the date headers ("Today",
"November 19") in the Notes line. **Send:** the dumps (scramble off), and the last line of one
older cell as you read it with TalkBack, word for word.

13.7 **Do:** in Telegram, reply to someone's message, and have them reply to one of yours. Dump
with both on screen. **See:** for now the Notes line says `0 replies to me` (the reply header's
accessibility text is not known yet). **Send:** the dump (scramble off): it shows where Telegram
puts the reply header, so the parser can learn it.

13.8 **Do:** install this build over the previous one (history kept), open a chat that has
history, and open the debug screen. **See:** the app opens normally, no crash, and the panel still
shows suggestions in that chat (the history DB moved to version 2). **Send:** pass/fail; a crash
report or `adb logcat -v time | grep -i -E "room|migration|replyly"` output if it fails.

13.9 **Do:** just after midnight, open a chat with messages from before and after midnight on
screen; dump. **See:** the messages before midnight are under "YESTERDAY" with yesterday's date,
those after under "TODAY" with today's. **Send:** the dump and the time you took it.

13.10 **Do:** open a WhatsApp chat at about 23:55 and keep it open on screen without scrolling
until after midnight. Have a message arrive after midnight (from a second phone, or ask someone).
At about 00:05, take a dump without scrolling first. **See:** whether the old separator above the
evening's messages still says "TODAY", and whether a second "TODAY" appeared above the new
message. The Notes line should end with `dates dropped: stale today` (one "TODAY") or
`dates dropped: separators out of order` (two), and say `0 of N messages dated`. **Send:** the
dump, the exact time you took it, and what the separators said on screen.
Two more variants, each on its own night:
- **Locked overnight.** At about 23:55 open the chat, lock the phone with the chat on screen, and
  leave it until the morning. Unlock and take a dump at once, without scrolling. **See:** whether
  the separator above yesterday's messages now says "YESTERDAY" (WhatsApp redrew the rows on
  resume) or still "TODAY". With a stale "TODAY" and every message on screen earlier in the day
  than the capture time, the Notes line shows no `dates dropped`: the check cannot see it.
- **Scrolled up.** At about 23:55 scroll up in the chat until no "TODAY" separator is on screen
  (only older days, or no separator), keep it on screen past midnight, and dump at about 00:05
  without scrolling. **See:** what the separators on screen say: a separator drawn before midnight
  ("YESTERDAY" meaning the day before yesterday) is one day off, and nothing on screen shows it.
**Send:** for each variant, the dump, the exact time you took it, and what the separators said on
screen. These decide whether the merge needs a rule for stale screens without "TODAY".
