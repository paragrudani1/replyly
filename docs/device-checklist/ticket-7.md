# Device checklist: ticket #7 Onboarding (Samsung S23, One UI)

Start from a fresh install of the debug APK (uninstall Replyly first, or clear its storage).
Replyly opens on **Set up Replyly** with 5 cards, all "Not done" or "Not confirmed".
After each step, come back to Replyly (Back or Recents): the card should update by itself, without a restart.

If a step fails, send back: the step number, what you tapped, what screen opened (a screenshot), and what the card says.

## Assumed One UI menu paths (unverified)

| Step | Path we assume |
|---|---|
| 1 | Settings → Apps → Replyly (App info) → ⋮ (top right) → "Allow restricted settings" → PIN/fingerprint |
| 2 | Settings → Accessibility → Installed apps → Replyly → switch on |
| 3 | Settings → Apps → Replyly → Battery → "Unrestricted" |
| 4 | Settings → Device care → Battery → Background usage limits → Sleeping apps / Deep sleeping apps / Never sleeping apps |
| 5 | Inside Replyly: Settings screen (API key and model) |

## Intents that may not resolve on One UI

- Step 4 first tries Samsung Device care `com.samsung.android.lool/com.samsung.android.sm.battery.ui.BatteryActivity`, then the older `com.samsung.android.sm.ui.battery.BatteryActivity`. Both are guesses. If neither opens, it falls back to `Intent.ACTION_POWER_USAGE_SUMMARY` (general battery usage), then to App info.
- Step 3 opens `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (a system "let app run in background?" dialog), and falls back to App info.
- If nothing opens, a toast says "Could not open that screen". Report this.

## Steps

1. **Restricted settings, blocked state.** Tap card 2's "Open Accessibility" → Installed apps → Replyly.
   Expect: the switch is greyed out or tapping it shows a "Restricted setting" dialog. Go back to Replyly.
   Card 1 should still say "Not done".
2. **Restricted settings, allow.** On card 1 tap "Open App info".
   Expect: Replyly's App info page opens. Tap ⋮ (top right). Expect an "Allow restricted settings" item (it may only show after step 1). Tap it and confirm.
   Card 1 stays "Not done" for now: Replyly can't read this setting, so it counts as done only once step 2 is done. Report if the ⋮ item is missing or named differently.
3. **Accessibility.** On card 2 tap "Open Accessibility" → Installed apps → Replyly → switch on → Allow.
   Back in Replyly, expect **cards 1 and 2 both "✓ Done"** (green).
   Then switch the service off: both cards go back to "Not done".
   Report if card 2 ever shows "Turned on but not running" while the switch is on. That means the service did not connect, which could be One UI killing it.
4. **Battery.** On card 3 tap "Allow".
   Expect: a system dialog asking to let Replyly always run in the background. Tap Allow. Card 3 → "✓ Done".
   Cross-check: Settings → Apps → Replyly → Battery should now show **"Unrestricted"** selected. Report it if it shows "Optimized" while card 3 says Done, or the other way round.
   Also try the reverse: set Battery to "Optimized" in App info. Card 3 should go back to "Not done".
5. **Sleeping apps (manual, cannot be detected).** On card 4 tap "Open battery settings".
   Report which screen opened: Device care → Battery, the general battery usage screen, or App info.
   Follow the path in the table above: make sure Replyly is not under "Sleeping apps" or "Deep sleeping apps". Optionally add it to "Never sleeping apps".
   Back in Replyly, tap "I've done this". Card 4 → "✓ Done (you confirmed it)". Force-stop and reopen Replyly: it should still be confirmed. "Undo" clears it.
6. **API key and model.** On card 5 tap "Open settings": the Replyly settings screen opens.
   Once ticket #6 has landed, enter the key, keep or choose a model, then return to Setup steps: card 5 should show "✓ Done".
7. **Start screen.** With all 5 cards done, the button at the bottom reads "Done: go to settings".
   Force-stop and reopen Replyly: it should open straight on **Settings**. Turn the accessibility service off and reopen: it should open on **Set up Replyly** again.
8. **Reachable from settings.** On the settings screen tap "Setup steps": the onboarding screen opens with the current status.
