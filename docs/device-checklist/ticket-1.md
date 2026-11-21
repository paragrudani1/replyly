# Device checklist: ticket #1 (overlay and button)

Overlay windows, the keyboard window, focus and FLAG_SECURE only exist on a real phone. The VM
tests cover the decisions (when to show, where, what to block, which field to insert into); none
of the steps below ran anywhere. Useful commands (USB debugging on):

- Replyly's logs: `adb logcat -s ReplylyOverlay ReplylyA11y ReplylyPolicy`
  (event names and reasons only, never chat text). Lines you will see:
  `button shown`, `button hidden reason=<REASON>`, `tap outcome=open|no_chat|blocked reason=<REASON>`,
  `secure check state=SECURE|UNKNOWN code=<n>`.
- Window list (to compare positions): `adb shell dumpsys window windows | grep -E "Window #|mFrame|InputMethod"`.

Until #2 lands real parsers, a tap ends in "Replyly can't read a chat here" (`tap outcome=no_chat`)
in every app. Steps 1 to 4 work without #2; do steps 5 to 8 on a build with #2 (and #5 for a real panel).

**Update first:** after installing this build over an older one, turn the Replyly accessibility
service off and on once (Settings > Accessibility > Installed apps > Replyly), so Android picks up
the new `canTakeScreenshot` capability. If you skip it, step 6 logs `code=-1`.

## 1. The button sits above Gboard (portrait)

1. Open a WhatsApp chat and tap the message field. Gboard opens.
2. Expect within a moment: a round purple button (speech bubble) at the right, just above the
   message bar (not above the keyboard's toolbar row, and not over WhatsApp's send/mic button).
3. Expect it to be fully visible, not under the status bar or navigation bar.
4. Type a multi-line message (4 lines) so the bar grows. Expect the button to move up with the bar.
5. If it fails, send back: a screenshot, the `button ...` log lines, and the output of the
   `dumpsys window` command above.

## 2. Placement in other keyboard modes

1. **Landscape:** rotate the phone in the same chat. Expect the button at the right, above the bar.
2. **Floating keyboard:** in Gboard, toolbar > ⋮ > Floating. Expect the button above the floating
   keyboard's right corner, not off screen. Move the floating keyboard; expect the button to follow
   within about half a second.
3. **One-handed mode** (Gboard): expect the button above the keyboard's right edge.
4. **Samsung Keyboard:** switch the default keyboard to Samsung Keyboard (Settings > General
   management > Keyboard list and default) and repeat step 1. Expect the same placement.
5. If any fails, send back: which mode, a screenshot, and the `dumpsys window` output.

## 3. Shows and hides at the right times

For each step, expect the button to appear or vanish within about 0.2 s. Check the logs for the reason.

1. Close the keyboard with Back. Expect: hidden (`reason=KEYBOARD_CLOSED`).
2. Tap the message field again. Expect: shown.
3. Open WhatsApp's emoji panel (smiley in the bar). Expect: hidden while the emoji panel replaces
   the keyboard; shown again when you return to the keyboard. Send back the log line if it stays
   shown over the emoji panel.
4. Switch chats: Back to the chat list (expect hidden), open another chat, tap the field (expect shown).
5. Home button with the keyboard open. Expect: hidden at once, and not shown over the launcher
   unless you tap a search field there (generic mode shows it in any app that is not blocked).
6. Recent apps, then back to WhatsApp. Expect: shown again once the keyboard is back.
7. Open a password field (for example WhatsApp > Settings > Account > Two-step verification, or any
   login screen). Expect: hidden (`reason=PASSWORD_FIELD`).
8. Open Replyly's own settings and tap a text field. Expect: never shown (`reason=EXCLUDED_APP`).
9. Open a banking app (any one from step 6) and tap a field. Expect: never shown (`reason=BLOCKED_APP`).
10. Do 2 to 6 in Telegram and WhatsApp Business too.
11. While the button shows, scroll the chat and receive a message. Expect: the button stays put,
    no flicker. If the logs show `button shown` / `button hidden` repeating, send them back.

## 4. Battery and speed

1. With the button showing, leave the chat open for 5 minutes while messages arrive.
2. Expect no visible lag while typing. Send back how many `button` log lines appeared (only
   changes are logged; it should be a handful, not hundreds).

## 5. Tapping the button opens the panel, and its field takes the keyboard

1. In a WhatsApp chat with the keyboard open, tap the button.
2. Expect: the screen dims, and the panel slides up from the bottom (with #5: suggestion cards).
   The chat's keyboard may close; that is expected.
3. Tap the panel's instruction field. Expect: the keyboard opens and typing goes into the panel's
   field, not into WhatsApp. Expect the panel to sit above the keyboard, not under it.
4. Press Back. Expect: the panel closes (or the first press closes the keyboard and the second
   closes the panel; both are fine). Tap outside the panel (the dimmed area): expect it to close too.
5. After closing, expect the WhatsApp message field to have the cursor again, and the button to
   come back once the keyboard is open. Send back whether the keyboard reopens on its own or
   needs a tap on the field.
6. If the panel field never gets the keyboard, send back: a screenshot and
   `adb shell dumpsys input_method | grep -E "mCurFocusedWindow|mServedView|mInputShown"`.

## 6. FLAG_SECURE: banking app and incognito tab

1. Open a banking app (for example YONO SBI or iMobile). Tap any text field. Expect: no button
   (the banking list blocks it first; step 2 tests FLAG_SECURE on its own).
2. Chrome: open a new Incognito tab, tap the address bar. Expect the button shows (Chrome is
   allowed). Tap it. Expect: the "Replyly is off here" notice, and in logs
   `secure check state=SECURE code=5` and `tap outcome=blocked reason=SECURE_WINDOW`.
3. Switch to a normal (non-incognito) Chrome tab, tap the address bar and the button. Expect:
   no "off here" notice (`tap outcome=no_chat` or `open`), and no `secure check` line.
4. Telegram secret chat (if you have one): tap the field and the button. Expect: `reason=SECURE_WINDOW`.
5. If step 2 shows `state=UNKNOWN`, send back the `code=` value (−1: service not restarted after
   update; 1: internal error; 3: called too often; timeout: no answer).
6. Also send back: does the screen flash or stutter when you tap the button? (The check captures
   the window once and throws the pixels away at once.)

## 7. The suggestion lands in the chat's field (needs #2 and #5)

1. WhatsApp chat: type "draft" in the field, tap the button, wait for the cards, tap one.
2. Expect: the panel closes, the field now holds exactly the suggestion ("draft" replaced), the
   cursor at the end, and **nothing sent**. You send it yourself.
3. Repeat in WhatsApp Business and Telegram.
4. Repeat with the panel's instruction field used first (step 5.3), so focus moved to the panel
   before the insert. Expect: the text still lands in the chat's field, not in the panel.
5. Open the panel, then go Back to the chat list and into another chat before tapping a card
   (if the panel allows it). Expect: an error such as "field gone", never text in the wrong chat.
6. If the text does not appear, send back the app name and the WhatsApp/Telegram version
   (Settings > Apps > the app > version).

## 8. Banking package names

`app/src/main/kotlin/com/paragrudani/replyly/policy/SensitiveApps.kt` lists the banking, UPI and
wallet apps from memory. For each such app you have installed:

1. Open it and tap a text field. Expect: no button (`reason=BLOCKED_APP`).
2. If the button shows, send back the app's package name (`adb shell pm list packages | grep -i <name>`,
   or Replyly's debug dump), so it can be added.

## 9. One UI killing the service

1. Do the onboarding steps (battery Unrestricted, not in Sleeping apps).
2. Leave the phone locked for 1 hour, unlock, open WhatsApp, tap the field.
3. Expect: the button shows. If it does not, check Settings > Accessibility > Installed apps >
   Replyly: if it shows On but nothing works, or Off, send back which, and
   `adb logcat -b events -d | grep -i replyly | tail -20`.
4. Swipe Replyly away from Recents, then repeat step 2's last part. Expect: the button still shows
   (the accessibility service lives apart from the app's task).
