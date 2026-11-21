# Device checklist: ticket #3 (encrypted history)

These need the real Samsung S23. Robolectric has no Android Keystore and cannot load SQLCipher's
native library, so none of this ran on the VM. Useful commands (USB debugging on):

- Replyly's logs: `adb logcat -s ReplylyCapture ReplylyDbKey ReplylyHistoryDb ReplylyA11y`
  (event names and counts only, never chat text).
- Files: `adb shell run-as com.paragrudani.replyly ls -la databases no_backup`.

Until ticket #2 lands real parsers, capture finds no chat and stores nothing. Do steps 2 to 9 on a
build that has #2 merged.

If a build of #3 from before 2026-11-21 evening is installed, clear Replyly's storage first
(Settings > Apps > Replyly > Storage > Clear storage): the history schema changed without a version
bump, and the old file would fail to open.

## 1. The DB opens and is encrypted

1. Install the debug APK, enable the accessibility service, open WhatsApp and one chat. Wait 2 s.
2. Run `adb shell run-as com.paragrudani.replyly ls -la databases no_backup`.
3. Expect: `databases/history.db` exists, and `no_backup/db_passphrase.wrapped` exists (about 100 bytes).
4. Run `adb shell run-as com.paragrudani.replyly head -c 16 databases/history.db | xxd`.
5. Expect: random-looking bytes, **not** `SQLite format 3`.
6. Expect in logcat: one `new database passphrase reason=NO_SAVED_PASSPHRASE` line on first run only.
7. If it fails, send back: the `ls` output, the `xxd` line, and the logcat lines from the three tags.

## 2. Passive capture runs and has the right latency

1. Open a WhatsApp chat and stay still for 2 s. Then receive or send one message.
2. Expect in logcat, about 1 s after the screen stops changing:
   `capture merged inserted=<n> edited=0` (first open: n = visible messages; after the new message: 1).
   A capture that finds nothing new logs `capture unchanged`.
3. Note the time between the message appearing and the log line (logcat timestamps).
   Target: about 1 to 1.5 s. Send back the measured delay if it is over 2 s, or if lines repeat
   many times per second while the screen is still (that would mean the wait never settles).
4. Scroll the chat up a few screens, then back down. Expect `inserted=` for the older messages and
   no `inserted=` on the way back down (no duplicates).

## 3. Generic apps and blocked screens store nothing

1. Open Slack or Instagram DMs and wait 2 s. Expect: no `capture merged` line.
2. In WhatsApp, open the chat lock / any screen with a password field. Expect: no `capture merged` line.

## 4. History survives an app update

1. With some history saved (step 2), install a newer debug APK over the old one
   (`adb install -r replyly-debug.apk`, or the latest-debug release). Do not uninstall.
2. Open the same chat and wait 2 s.
3. Expect: **no** `new database passphrase` line, and `capture merged` shows only new messages
   (or no line at all if nothing changed). History was kept.
4. If you see `new database passphrase reason=...`, send back that line and the APK versions.

## 5. Reinstall wipes history (expected)

1. Uninstall Replyly, install it again, enable the accessibility service, open a chat.
2. Expect: one `new database passphrase reason=NO_SAVED_PASSPHRASE` line and a fresh
   `capture merged inserted=<n>`. Android deletes the Keystore key and the app data on uninstall,
   and backup is off, so old history is gone. That is by design.

## 6. Capture with the screen off and after One UI kills the service

1. Open a WhatsApp chat, then lock the phone (screen off) for 5 minutes. Have someone send you a message.
2. Unlock and go straight back to the chat. Expect: a `capture merged` line within about 1 s, and the
   message is in history (no crash, no `capture failed` line).
3. Leave the phone idle for a few hours (or overnight) with the service on, then open a chat.
4. Expect: capture still works. If One UI killed the service, Settings > Accessibility shows it off;
   turn it back on and expect a `ReplylyA11y`/capture log line and `capture merged` again.
5. Send back: whether the service was still enabled, and any `capture failed: <ClassName>` lines.

## 7. Key loss does not crash-loop

1. Settings > Apps > Replyly > Storage > Clear storage (this removes the DB and the wrapped key).
2. Re-enable the accessibility service, open a chat.
3. Expect: one `new database passphrase reason=NO_SAVED_PASSPHRASE` line, then normal capture.
   The app and the service do not crash or restart in a loop.
4. If anything crashes, send back `adb logcat -b crash -d`.

## 8. Feedback final message (needs #1, #4, #5 merged)

1. Open a chat, tap the Replyly button, insert a suggestion, edit it a bit, and send it yourself.
2. Wait 2 s. Expect in logcat: `feedback final message attached`.
3. On the debug/settings feedback screen (#6), the newest entry shows the text you actually sent.

## 9. Delete this chat's history (needs #5 merged)

1. In a chat with history, open the panel and use "Delete this chat's history".
2. Expect: the next suggestion request for that chat has no older history in its prompt
   (only what is on screen now), and the contact note is still there.

## 10. An unreadable history.db is recreated once, no crash loop

Step 7 removes the DB and the key together, so it never tests a DB the key cannot open. This does.

1. With some history saved (step 2), run:
   `adb shell run-as com.paragrudani.replyly sh -c 'rm -f databases/history.db-wal databases/history.db-shm; head -c 4096 /dev/urandom > databases/history.db'`
2. Run `adb shell am force-stop com.paragrudani.replyly`. If Settings > Accessibility now shows
   Replyly off, turn it back on.
3. Open a WhatsApp chat and wait 2 s.
4. Expect in logcat: exactly one `history db unreadable, recreating: SQLiteDatabaseCorruptException`
   line, **no** `new database passphrase` line (the key is fine), then `capture merged inserted=<n>`.
   The app and the service do not crash or restart in a loop.
5. Open another chat and wait 2 s. Expect no second `recreating` line.
6. If it fails, send back: the `ReplylyHistoryDb`/`ReplylyCapture` lines, any
   `capture failed: <ClassName>` line, and `adb logcat -b crash -d`. If there is no `recreating`
   line but capture works and nothing crashed, send that back too (SQLCipher may have recreated
   the file by itself).

## 11. A screen that never stops changing is still captured

The 1 s wait restarts on every screen change; it now fires at the latest 3 s after the first one.

1. Open a 1:1 WhatsApp chat and ask the other person to keep typing for 15 s (the "typing…"
   indicator), or play a GIF or a voice note in the chat.
2. Expect in logcat, while it goes on: a `capture unchanged` or `capture merged` line about every
   3 s. Not several per second, and not zero lines until it stops.
3. While they are still typing, send a message yourself. Expect a `capture merged inserted=1`
   line within about 3 s.
4. If it fails, send back the `ReplylyCapture` lines with their logcat timestamps.

## 12. A busy group after a gap: scrolling up does not overwrite history

Checks that a read after a gap in history never turns an old message into an "edit".

1. Pick a busy WhatsApp group. Open it, wait 2 s, and note the newest message (call it M).
   Leave the group and do not open it while at least a screen of new messages arrives.
2. Open the group again and wait 2 s. Expect `capture merged inserted=<n> edited=0`.
3. Scroll up one screen slowly (so the top bubble is cut off), wait 2 s, then one more screen,
   wait 2 s.
4. Expect: every `capture merged` line says `edited=0` (unless someone really edited a message in
   that time). An `edited=1` or more while nobody edited anything is the bug.
5. If a prompt preview or the feedback screen (#4, #6) is available: the history around the gap
   still shows M with its original text.
6. If it fails, send back the `ReplylyCapture` lines and how many screens you scrolled.

## 13. After reading old messages, the prompt context is the bottom of the chat again

Needs #4 (the assembler) merged, or a debug view of the prompt.

1. Open a 1:1 WhatsApp chat with a long history. Wait 2 s at the bottom.
2. Jump far up (tap the date header, or fling up several screens quickly) so that nothing on the
   screen was on the first screen. Wait 2 s, scroll down one screen, wait 2 s.
3. Go back to the bottom (the down-arrow button) and wait 2 s.
4. Focus the input field and tap the Replyly button.
5. Expect: the suggestions answer the newest messages at the bottom, not the old ones you read in
   step 2. If a prompt preview exists, its last message is the bottom bubble on screen.
6. Send a message, wait 2 s. Expect a `feedback final message attached` line in logcat when a
   suggestion was used just before.
7. If it fails, send back the `ReplylyCapture` lines from step 1 on, and say which messages the
   suggestions answered.

