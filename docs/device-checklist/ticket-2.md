# Device checklist: ticket #2 (parser and debug dumps)

Every view id, text format and geometry rule the parsers assume, written from public knowledge and never seen on the S23.
Most steps are the same move: take a dump of the chat (see [PARSER_FIXTURES.md](../PARSER_FIXTURES.md), section 1), compare the summary on the dump card with the screen, and share the dump (scrambled is fine) if anything is wrong.
"Send back" always means: the dump JSON plus one line saying what was wrong.

## Debug screen

1. **Hidden entry.** Open Replyly, long-press the title "Replyly settings". Expect the "Debug: screen dumps" screen. If nothing happens, tell the CoS.
2. **Service off.** With Replyly's accessibility service off, open the debug screen. Expect a red card "Accessibility service is NOT enabled" and a disabled "Dump in 5 s" button. Tap "Open accessibility settings": expect Settings > Accessibility. Turn the service on, go back: expect "Accessibility service is running".
3. **Delayed capture.** Tap "Dump in 5 s", switch to any WhatsApp chat within 5 s. Expect the toast "Replyly: dump saved (com.whatsapp)" over WhatsApp (One UI could hide toasts from apps in the background: if no toast appears, say so). Go back to Replyly: expect the dump on top of the list. If the toast says "no active window to read", send back that message.
4. **Keyboard open.** Repeat step 3 but tap the WhatsApp message field first so the keyboard is open. Expect the dump to parse the same messages as step 3 (the parser accepts an input field anywhere below the top 30% of the screen). Send back the dump if the summary says "Not recognised as a chat."
5. **Share, copy, save, delete.** On a dump: Share opens the share sheet with the JSON as text; Copy shows a clipboard toast with the preview hidden (sensitive); Save creates `My Files > Downloads > Replyly > <name>.json`; Delete asks, then removes the card. Report any that fail.
6. **Scramble.** Turn on "Scramble text when sharing", Share a dump to yourself. Expect no readable message text or names, but readable times, "TODAY", "online", digits and emoji. Send it back: the CoS checks it still parses the same way.

## WhatsApp (`com.whatsapp`)

7. **1:1 chat by view ids.** Dump a 1:1 chat with a few messages each way. Expect `Parser: whatsapp:view-ids`, `Title:` = the contact name, `Group: no`, each message with `←`/`→` correct and the bubble's time. This confirms these ids:
   - title `conversation_contact_name`, subtitle `conversation_contact_status`;
   - message text `message_text` (and `caption` under a photo);
   - time `date` (inside the same row, at or below the text);
   - ticks `status` exist only on my bubbles;
   - the message list is the largest scrollable node (`android:id/list` assumed, not required).
   If the route says `whatsapp:geometry`, the ids differ: send the dump.
8. **Time format.** In step 7's summary, times look like `[9:15 pm]` exactly as in the bubble. (Android 14+ puts a narrow no-break space before am/pm; the parser keeps it.) Report if times are missing or include extra words.
9. **Group chat.** Dump a group with messages from 2+ people, including two in a row from one person. Expect `Group: yes`, each incoming message with the right sender, and the second of the pair with the same sender as the first (WhatsApp shows the name only on the first). Confirms:
   - sender label id `name_in_group_tv`, shown inside the bubble above the text;
   - the group subtitle lists members and ends with "You" (also used to detect a group when only my messages are on screen).
10. **Unsaved number in a group.** If a group has someone not in contacts, expect their sender to be the phone number (the "~ Name" next to it is ignored, assumed id `pushname_in_group_tv`). Tell the CoS if you would rather see the ~name.
11. **Reply quote.** Reply to a message (swipe right), send it, dump. Expect the reply's line to show only your new text, not the quoted one. Confirms quote previews use other ids (`quoted_title`/`quoted_text` assumed), not `message_text`.
12. **Noise.** In a chat that shows the encryption notice ("Messages and calls are end-to-end encrypted..."), a date separator ("TODAY"), a "N UNREAD MESSAGES" banner, a deleted message ("This message was deleted"), an edited message ("Edited") and a forwarded one ("Forwarded"): expect none of those words as messages. Send back the dump if any shows up.
13. **Message that looks like chrome.** Send yourself "Today" and "10:30" in a test chat. Expect both as messages.
14. **Same text twice.** Send "ok" twice in a row. Expect two `→ me ... ok` lines.
15. **Chat list.** Dump the WhatsApp chat list (not a chat). Expect `Not recognised as a chat.`

## WhatsApp Business (`com.whatsapp.w4b`)

16. Repeat step 7 in WhatsApp Business. Expect `Parser: whatsapp:view-ids` and `Chat key: com.whatsapp.w4b:<name>`. Confirms Business uses the same id names under its own package.

## Telegram (`org.telegram.messenger`)

17. **1:1 chat.** Dump a 1:1 chat. Expect `Parser: telegram:cell-descriptions`, the right title, sides and times. Confirms:
   - each message cell exposes a description (text or content description) whose **last line** is `Sent today at 6:42 PM, Read` for mine or `Received today at 6:42 PM` for theirs (English wording assumed, phone in English);
   - title and subtitle are text nodes above the message list (`last seen recently`, `online`).
   If the route is `telegram:geometry` or there are no messages, send the dump: the description format differs.
18. **Group.** Dump a group. Expect `Group: yes` and the right senders. Confirms the subtitle reads `N members` (or `N members, M online`) and that each incoming cell's description starts with the sender's name on its own line.
19. **Multi-line message in a 1:1 chat.** Have someone send a two-line message. Expect one message with both lines (`⏎` between them) and the contact as sender, not the first line as sender.
20. **Forwarded and reply.** Forward a message into a group, and reply to one. Expect no "Forwarded from ..." line in the text. For the reply, tell the CoS whether the quoted text shows up in the message (the description format for replies is unknown).
21. **Noise.** Date headers ("Today"), service messages ("X joined Telegram"), and "Unread messages" must not appear as messages (assumed: they have no `Sent/Received` line).

## Geometry fallback (used when ids or descriptions fail)

These rules only matter after an app update; the dumps from steps 7-21 let the CoS check them offline. Nothing to tap, but keep the dumps.

22. Incoming bubbles share one left edge, mine share one right edge; the side is decided by these edges first, then ticks, then the nearer screen edge.
23. A bubble is the smallest container holding its text and time, narrower than 92% of the list width; rows are full width.
24. Date separators, system notices and unread banners are centred (same gap left and right, within 4% of the list width).
25. In a group, the sender name is the first line of an incoming bubble, above the text, at most 60 characters, not inside a stacked block (a reply-quote preview is a box with a name above a text).
26. The toolbar is everything above the message list; the title is its first line that is not a status ("online", "last seen", "typing…", "N members").

## Generic apps

27. Dump a DM in Instagram, LinkedIn or Slack. Expect `Parser: generic:visible-text`, `History: no`, and the visible messages with `←`/`→` (no senders). Slack shows every message on the left, so `→ me` will not appear there: expected for generic apps.
