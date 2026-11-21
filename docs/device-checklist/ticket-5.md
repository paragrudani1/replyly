# Ticket #5: Panel UI, device checks

Needs: the app installed on the S23, the accessibility service on, an API key saved, and tickets #1
(overlay + button), #3 (history) and #6 (settings) merged. Use a WhatsApp 1:1 chat with some recent
messages, ideally in Hinglish. Gboard is the keyboard. Never send the API key or chat text back; a
screenshot with the chat blurred is fine.

## A. Height with Gboard open

1. Open the WhatsApp chat, tap the message box so Gboard opens, tap the Replyly button.
2. You should see: the panel sits above the keyboard, the chat's message box is still visible
   between the panel and Gboard (or the panel covers only chat bubbles, not the message box),
   and the panel top does not go under the status bar.
3. Scroll inside the panel: all 3 cards, the "Delete this chat's history" link and the
   instruction row are reachable.
4. Repeat with Gboard's clipboard/emoji strip showing (taller keyboard) and in landscape.

Send back: a screenshot of each case, and whether any part was cut off. If it is too tall or too
short, say roughly how many cards should fit without scrolling (the cap is
`ComposeReplyPanel.DEFAULT_MAX_HEIGHT`, 340 dp).

## B. Typing into the instruction field from the overlay

1. With the panel open, tap the "Instruction" field.
2. You should see: Gboard switches to the field and your typing appears in it (not in WhatsApp's
   message box). The panel stays open and is not covered by the keyboard.
3. Type "say I'll call at 6", tap Regenerate. The keyboard closes, a progress bar shows, then 3 new
   cards follow the instruction.
4. Tap a card. You should see: the text lands in WhatsApp's message box (not in the instruction
   field), the panel closes, nothing is sent. Check that the message is still unsent.
5. Long-press a word in the instruction field: selection handles may appear; the app must not crash.
6. Open "Contact note", type a few words, tap Save. Tap "Contact note" again: your text is there.

Send back: pass/fail per step. If step 2 fails (no keyboard, or typing goes to WhatsApp), say what
happened; #1's window flags then need a change. If step 4 inserts nowhere or shows "Could not find
the chat's message box", #1's inserter needs to target the remembered message box.

## C. Dark mode

1. Quick settings: turn Dark mode on. Open the panel in the WhatsApp chat.
2. You should see: dark panel background, readable card text and intent labels, the error card
   (turn on Airplane mode, tap Regenerate) readable in red tones.
3. Repeat with Dark mode off.

Send back: one screenshot each (blur the chat).

## D. Long Hinglish suggestions

1. In a chat where you usually write long Hinglish messages, type the instruction
   "write a long detailed reply" and tap Regenerate.
2. You should see: each card shows its whole text, wrapped onto several lines, no text cut off with
   "…", no sideways scrolling. The panel body scrolls to show all 3 cards.

Send back: a screenshot, and whether any card text was cut.

## E. Errors and settings link

1. Settings: delete the API key. Open the panel. You should see "No API key yet" and an
   "Open settings" button. Tap it: Replyly's settings open, the panel closes.
2. Turn on Airplane mode, tap Regenerate (or reopen the panel). You should see "Could not reach the
   API" or "The model took too long" with a Retry button; Retry works once you are back online.

Send back: pass/fail per step. If "Open settings" does nothing, note the Android version; Android may
have blocked the activity start from the overlay.

## F. Delete this chat's history

1. In the WhatsApp chat, open the panel, scroll down, tap "Delete this chat's history".
2. You should see a confirmation inside the panel (no pop-up dialog). Tap Cancel: nothing changes.
3. Tap it again, then Delete. You should see "Saved messages for this chat were deleted."
4. Open the panel in a generic app (e.g. Gmail compose): there is no "Contact note" button and no
   delete link.

Send back: pass/fail per step.

## G. No extra LLM requests while the panel is open

Each request counts against the daily limit, so the panel must make exactly one per open. Before
each case, note the "requests today" count (Settings) or the number of entries in the feedback log.

1. Open the panel in the WhatsApp chat and wait for the 3 cards. Rotate the phone to landscape and
   back.
2. You should see: no new "Writing 3 replies…" progress bar, the same 3 cards, and the count went
   up by exactly 1 since you opened the panel.
3. With the panel open and the cards shown, pull down quick settings and toggle Dark mode (then
   toggle it back).
4. You should see: the panel recolours but shows the same 3 cards, no progress bar, and the count
   went up by exactly 1 since you opened the panel.
5. Ask the other person to send you a message while the panel is open (or send one from a second
   phone).
6. You should see: the same cards, no new progress bar, count still up by exactly 1.
7. Open the panel and tap Close while "Writing 3 replies…" is still showing (turn on a slow
   connection or tap fast).
8. You should see: the panel closes, and no new feedback log entry appears for that open (the
   request may still count against the limit if it already reached the provider).

Send back: the count before and after each case. If any case adds more than 1, say which one;
#1 then re-creates the panel's `ComposeView` or composition on that event and must keep it instead.
