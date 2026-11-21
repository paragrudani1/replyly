# Device checklist: ticket #1 (overlay and button)

Overlay windows, the keyboard window, focus and FLAG_SECURE only exist on a real phone. The VM
tests cover the decisions (when to show, where, what to block, which field to insert into); none
of the steps below ran anywhere. Useful commands (USB debugging on):

- Replyly's logs: `adb logcat -s ReplylyOverlay ReplylyA11y ReplylyPolicy`
  (event names and reasons only, never chat text). Lines you will see:
  `button shown`, `button hidden reason=<REASON>`, `tap outcome=open|no_chat|blocked reason=<REASON>`,
  `secure check state=SECURE|UNKNOWN|GONE code=<n>`, `screen off`, `panel closed reason=window_gone`,
  `panel not opened reason=window_gone`.
- Time a tap: `adb logcat -v time -s ReplylyOverlay`; the time from the tap to `tap outcome=` is
  the read time.
- Window list (to compare positions): `adb shell dumpsys window windows | grep -E "Window #|mFrame|InputMethod"`.

Until #2 lands real parsers, a tap ends in "Replyly can't read a chat here" (`tap outcome=no_chat`)
in every app. Steps 1 to 4, 10.3, 11 (dimming and timing) and 12.1 work without #2; do the rest on a
build with #2 (and #5 for a real panel).

**Update first:** after installing this build over an older one, turn the Replyly accessibility
service off and on once (Settings > Accessibility > Installed apps > Replyly), so Android picks up
the new `canTakeScreenshot` capability. If you skip it, every tap shows "Replyly is off here" and
the log says `secure check state=UNKNOWN code=2` (or `code=-1`): the FLAG_SECURE check fails closed.

## 1. The button sits above Gboard (portrait)

1. Open a WhatsApp chat and tap the message field. Gboard opens.
2. Expect within a moment: a round purple button (speech bubble) at the right, just above the
   message bar (not above the keyboard's toolbar row, and not over WhatsApp's send/mic button).
3. Expect it to be fully visible, not under the status bar or navigation bar.
4. Type a multi-line message (4 lines) so the bar grows. Expect the button to move up with the bar.
5. If it fails, send back: a screenshot, the `button ...` log lines, and the output of the
   `dumpsys window` command above.

## 2. Placement in other keyboard modes

1. **Landscape, both ways:** rotate the phone left, then right, in the same chat (the punch-hole
   camera ends up on the left, then on the right). Expect the button at the right, above the bar,
   not under the camera cut-out.
2. **Landscape with 3-button navigation** (Settings > Display > Navigation bar > Buttons; the bar
   sits on the side in landscape): rotate both ways again. Expect the button fully visible, not
   under the navigation bar. Switch back to gestures afterwards if you prefer them.
3. **Full-screen landscape keyboard:** in landscape, if the keyboard covers the whole screen (an
   app or keyboard setting that turns off the side-by-side layout; WhatsApp's field in landscape on
   some keyboards), expect no button (`reason=INPUT_COVERED`). Send back a screenshot if it shows
   pinned in a corner instead.
4. **Floating keyboard:** in Gboard, toolbar > ⋮ > Floating. Expect the button above the floating
   keyboard's right corner, not off screen. Move the floating keyboard; expect the button to follow
   within about half a second. If the button vanishes with `reason=INPUT_COVERED` while the
   message bar is plainly visible, send back a screenshot and the `dumpsys window` output.
5. **One-handed mode** (Gboard): expect the button above the keyboard's right edge.
6. **Samsung Keyboard:** switch the default keyboard to Samsung Keyboard (Settings > General
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
   `secure check state=SECURE code=6` and `tap outcome=blocked reason=SECURE_WINDOW`.
3. Switch to a normal (non-incognito) Chrome tab, tap the address bar and the button. Expect:
   no "off here" notice (`tap outcome=no_chat` or `open`), and no `secure check` line.
4. Telegram secret chat (if you have one): tap the field and the button. Expect: `reason=SECURE_WINDOW`.
5. Any `state=UNKNOWN` blocks the tap ("Replyly is off here"): the check fails closed. Send back
   the `code=` value (2, or −1: the service was not turned off and on after the update; 1: internal
   error; 3: called too often twice in a row; timeout: no answer in 1.5 s). `state=GONE code=4|5`
   means the display or window went away mid-tap and shows "Replyly can't read a chat here".
6. Also send back: does the screen flash or stutter when you tap the button? (The check captures
   the window once and throws the pixels away at once.)

## 7. The suggestion lands in the chat's field (needs #2 and #5)

1. WhatsApp chat: type "draft" in the field, tap the button, wait for the cards, tap one.
2. Expect: the panel closes, the field now holds exactly the suggestion ("draft" replaced), the
   cursor at the end, and **nothing sent**. You send it yourself.
3. Repeat in WhatsApp Business and Telegram.
4. Repeat with the panel's instruction field used first (step 5.3), so focus moved to the panel
   before the insert. Expect: the text still lands in the chat's field, not in the panel.
5. Open the panel, then press Home, open WhatsApp again and go into another chat. Expect: the
   panel closed when you pressed Home (step 10.3), so no card can land in the other chat.
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

## 10. The panel never outlives the chat

1. **Lock with the panel open:** open the panel in a WhatsApp chat, then press the side key.
   Wake the phone. Expect: on the lock screen, no panel and no button (log `screen off`). After
   unlocking: the chat, no panel; the button comes back once the keyboard is open.
2. **Timeout with the panel open:** open the panel while it is still loading (with #4/#5), put the
   phone down and let the screen time out. Wake it. Expect the same as step 1: nothing of the chat
   readable on the lock screen.
3. **Lock with only the button showing:** keyboard open, button shown, press the side key, wake.
   Expect: no button on the lock screen, including on the PIN pad.
4. **Home with the panel open:** expect the panel to close at once (`panel closed
   reason=window_gone`), and not to show over the launcher.
5. **Recents with the panel open:** expect the panel to close. Go back to WhatsApp: no panel.
6. **The panel stays open by itself:** open the panel and wait 30 s without touching anything,
   then tap its instruction field. Expect: it stays open the whole time. If it closes on its own
   right after opening, send back the log lines and
   `adb shell dumpsys accessibility | grep -A40 "Windows"` taken while it is open.
7. **Notification shade with the panel open:** pull the shade down, then up. Send back whether
   the panel closed (closing is acceptable; it means the shade covered the chat's window).
8. **Rotate with the panel open** (with #5, after the cards have loaded): expect the panel to stay,
   the same cards, and no second LLM call (no new request in the cost screen or in #4's logs).
9. **Dark mode with the panel open:** `adb shell cmd uimode night yes`, then `... night no`.
   Expect: the panel stays, its colours follow, the cards are the same, no second LLM call.
10. **Font size with the panel open:** `adb shell settings put system font_scale 1.3`, then
    `... font_scale 1.0`. Expect the same as step 9.
11. **Service off while shown:** with the button showing, turn Replyly off in Settings >
    Accessibility (use `adb shell settings put secure enabled_accessibility_services ""` if you
    cannot reach Settings without closing the keyboard; turn it back on in Settings afterwards).
    Expect: the button disappears. Repeat with the panel open. Expect: the panel disappears too.
12. If any step fails, send back which step, a screenshot (not of a private chat), and the log lines.

## 11. Ten taps per app

1. In WhatsApp, tap the button ten times in a row (close the panel or the notice each time).
   While a tap is being read, expect the button to dim, and a second tap during that time to do
   nothing.
2. Expect no `secure check state=UNKNOWN` line in the ten taps. Note the time from each tap to
   `tap outcome=` (see the timing command at the top): send back the slowest and a typical one.
3. Repeat in Telegram.

## 12. Split screen and pop-up view

1. **Split screen:** Recents > WhatsApp's icon > Open in split screen view, with Chrome in the
   other half. Tap WhatsApp's message field. Expect: the button above the keyboard at the right of
   the keyboard, and a tap reads the WhatsApp chat (not Chrome). Then tap Chrome's address bar:
   expect the button to follow the focused app.
2. With the panel open in split screen, tap into the other app's half (outside the panel closes
   it; that is fine). Send back what happened.
3. **Pop-up view:** Recents > WhatsApp's icon > Open in pop-up view. Tap the message field. Expect
   the button above the keyboard, fully on screen. Tap it: expect the panel for that chat. Move
   the pop-up while the panel is closed: expect the button to follow.
4. If the button shows for the wrong half, or a tap reads the other app, send back screenshots and
   the `dumpsys window` output.
