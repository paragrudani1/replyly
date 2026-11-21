# Replyly design system

For agents building Replyly screens. Code: `app/src/main/kotlin/com/paragrudani/replyly/ui/theme/`.
See every token rendered in `app/src/test/screenshots/brand/specimen_{light,dark}.png`.

**Feel:** calm, premium, friendly. A quiet ink-blue tool that sits on top of someone else's chat
app. The one warm thing is the marigold sparkle. Nothing shouts.

## Use the theme

Every surface is wrapped in `ReplylyTheme { }`, including windows outside the Activity (the
overlay panel). With no arguments it reads dark mode, the user's "Material You colours" setting
and "Remove animations". Tests and previews pin them: `ReplylyTheme(darkTheme = true, dynamicColor = false)`.

| You need | Read |
|---|---|
| Colour roles (primary, surface, …) | `MaterialTheme.colorScheme` |
| Intent chips, accent, backdrop | `ReplylyTheme.colors` (`forIntent("agree")` returns a `ColorPair`) |
| Text styles | `MaterialTheme.typography` |
| Corner shapes | `MaterialTheme.shapes`, `SheetShape` |
| Spacing, radii, elevation | `Spacing.L`, `Radii.M`, `Elevation.Sheet` |
| Animation specs | `ReplylyTheme.motion` |
| Haptics | `rememberReplylyHaptics().perform(HapticEvent.X)` |
| View code (floating button) | `replylyColorScheme(context, dark, dynamic)`, `ReplylyMotion.forContext(context)`, `view.performReplylyHaptic(event)` |

## Colour

| Token | Light | Dark | Use |
|---|---|---|---|
| `ReplylyPalette.Ink` / primary | `#2D3E9A` | primary `#B9C3FF` | Brand. Filled buttons, switches, links, the button fill. |
| `ReplylyPalette.Marigold` / `colors.accent` | `#F5B83D` | same | The sparkle only. Never as text on a light surface. |
| background / surface | `#FAFAFD` | `#121318` | Page. |
| surfaceContainerLow…Highest | `#F4F4F9`…`#E2E2E9` | `#1A1B21`…`#34343B` | Grouped sections, cards, sheets (tone, not shadow). |
| onSurface / onSurfaceVariant | `#1A1B23` / `#45464F` | `#E3E1EA` / `#C6C5D0` | Body text / secondary text. |
| tertiary(Container) | `#7A5200` / `#FFDEA6` | `#F5BD48` / `#5D4100` | Warm highlights (marigold family). |
| error(Container) | `#BA1A1A` / `#FFDAD6` | `#FFB4AB` / `#93000A` | Errors. |
| `colors.agree` | `#D5F0DC` on `#0E5127` | `#1D4A2B` / `#B4EEC2` | Intent chip "agree". |
| `colors.decline` | `#FFDAD4` / `#7A2318` | `#6B2A20` / `#FFDAD4` | Intent chip "decline". |
| `colors.ask` | `#FFE2B0` / `#5C3D00` | `#5D4100` / `#FFDEA6` | Intent chip "ask". |
| `colors.backdrop` | black 32 % | black 50 % | Dim behind the panel. |

Rules:

- Every text/background pair above passes WCAG AA (4.5:1); `ThemeContrastTest` computes it. A new
  colour pair gets a line in that test.
- Use roles, never hex values in screens, so dark mode and Material You work.
- With Material You on, Material roles come from the wallpaper; intent chips keep their colours
  (they carry meaning) and the accent becomes `tertiary`.

## Type

Figtree, bundled (`res/font`). Devanagari and emoji fall back to the system font.

| Style | Size / line | Weight | Use |
|---|---|---|---|
| headlineMedium | 28 / 36 | SemiBold | Screen titles. |
| headlineSmall | 24 / 32 | SemiBold | Onboarding page titles. |
| titleLarge | 22 / 28 | SemiBold | Wordmark on settings. |
| titleMedium | 16 / 24 | SemiBold | Section and card titles. |
| titleSmall | 14 / 20 | SemiBold | Panel chat title, small headers. |
| bodyLarge | 16 / 24 | Regular | Settings rows. |
| bodyMedium | 15 / 22 | Regular | Default text; suggestion text. |
| bodySmall | 13 / 18 | Regular | Supporting text. |
| labelLarge | 14 / 20 | Medium | Buttons. |
| labelMedium / labelSmall | 12 / 16, 11 / 16 | Medium | Chips, metadata. |

Sentence case everywhere, including labels and chips. No all-caps labels.

## Spacing, shape, elevation

- **Spacing** (4 dp grid): `XXS 2`, `XS 4`, `S 8`, `M 12`, `L 16`, `XL 24`, `XXL 32`, `XXXL 48`.
  Screen edges 24 (settings, onboarding) or 16 (panel); between cards 12–16; inside a card 16.
- **Radii**: `XS 6` (small tags), `S 10` (chips), `M 16` (cards, fields), `L 24`, `XL 28` (sheets).
  Buttons are full pills (Material default). Bigger surface, rounder corner.
- **Elevation**: `Flat 0`, `Raised 1` (tonal cards), `Bar 3` (save bar), `Sheet 6` (panel),
  `Floating 8` (button). Separate things by surface tone first; shadows only for things floating
  over another app.

## Motion

| Token | Value | Use |
|---|---|---|
| `MotionDurations.SHORT` | 100 ms | Press feedback, tap highlight. |
| `MotionDurations.MEDIUM` | 150 ms | Button scale + fade in/out. |
| `MotionDurations.LONG` | 250 ms | Cards entering, content changes. |
| `MotionDurations.EXTRA_LONG` | 400 ms | Onboarding page transitions. |
| `MotionDurations.STAGGER` | 40 ms | Between the 3 cards. |
| `MotionEasing.Standard / Enter / Exit` | M3 emphasized curves | Moving / entering / leaving. |
| `MotionSprings` | damping 0.85, stiffness MediumLow | Panel slide-up, drags settling. |

Always build specs through `ReplylyTheme.motion`:

```kotlin
val motion = ReplylyTheme.motion
val alpha by animateFloatAsState(if (shown) 1f else 0f, motion.tween(MotionDurations.MEDIUM, MotionEasing.Enter))
val offset by animateDpAsState(target, motion.spring())
LaunchedEffect(Unit) { delay(motion.staggerDelay(index).toLong()); visible = true }
if (!motion.reduced) Shimmer() else StaticSkeleton()
```

When the user turned on "Remove animations" (`ANIMATOR_DURATION_SCALE == 0`), `motion.reduced`
is true: `tween`/`spring` snap, `staggerDelay` is 0, `viewDuration` is 0. Loops (shimmer,
pulsing) must check `motion.reduced` and not start. View animations use
`ReplylyMotion.forContext(context).viewDuration(MotionDurations.MEDIUM)` and
`MotionEasing.viewInterpolator(MotionEasing.Enter)` (never copy the curve's control points).

Motion answers a user action (tap, open, dismiss). No idle animation, no decorative entrance on
every element.

## Haptics

One helper, `ui/theme/Haptics.kt`, one feedback type per event. Never call
`performHapticFeedback` yourself.

| Event | `HapticEvent` | System feedback | When |
|---|---|---|---|
| Tap | `Tap` | `VIRTUAL_KEY` | Tap on the floating button (opens the panel). |
| Card insert | `CardInsert` | `CONFIRM` | A suggestion's text was set in the chat field. |
| Long-press copy | `LongPressCopy` | `LONG_PRESS` | A suggestion was copied to the clipboard. |
| Drag snap | `DragSnap` | `SEGMENT_TICK` | The dragged button snaps to an edge. |
| Toggle | `ToggleOn` / `ToggleOff` (`HapticEvent.toggle(on)`) | `TOGGLE_ON` / `TOGGLE_OFF` | A switch changed. |
| Save | `Save` | `CONFIRM` | Settings or a note were saved. |

```kotlin
val haptics = rememberReplylyHaptics()          // Compose
haptics.perform(HapticEvent.CardInsert)
buttonView.performReplylyHaptic(HapticEvent.Tap) // View code (overlay button)
```

- The system "Touch feedback" setting (`Settings.System.HAPTIC_FEEDBACK_ENABLED`) is respected:
  when it is off, nothing plays. So is `View.isHapticFeedbackEnabled`.
- Play the haptic when the action succeeds (insert done, saved), not on press-down. Material
  buttons and switches already click; don't add `Tap` on top of them.
- "Remove animations" does not silence haptics; only "Touch feedback" does.
- A new event needs a row here and in `HapticsTest`.

## Accessibility

- **Touch targets: 48 dp.** Material buttons, chips, switches and `IconButton` already comply.
  For anything you make clickable yourself, add `Modifier.minTouchTarget()` **after**
  `clickable`. The floating button is a 44 dp circle (`OverlayComponent.BUTTON_DP`) in a 56 dp touch
  window (`TOUCH_DP`); the ring leaves room for its shadow, so it uses `Elevation.Sheet`, not `Floating`.
- **Contrast**: AA for all text (see Colour). Icons and outlines that carry meaning need 3:1.
- **Font size**: every screenshot test also renders at 130 %; nothing may clip. Don't fix heights
  on text containers; let rows grow.
- **Reduced motion**: see Motion.

### TalkBack labels

- Every icon-only control has a `contentDescription` that says what it does: "Close", "Copy
  reply", "Suggest replies". Decorative icons (the sparkle in the wordmark, chip icons next to
  text) get `contentDescription = null`.
- Name the action, not the look: "Regenerate replies", not "Refresh icon".
- One focus stop per thing the user acts on. Merge a row's parts (`Modifier.semantics(mergeDescendants = true)`,
  or `toggleable` on a whole switch row as settings does) so TalkBack reads "Material You
  colours, switch, off" once.
- Clickables say what a tap does with `onClickLabel` when the text alone is unclear: a suggestion
  card reads its text, then "double-tap to insert"; long-press: `onLongClickLabel = "Copy"`.
- Screen and section titles get `Modifier.semantics { heading() }` so users can jump between them.
- State changes the user can't see must be announced: errors and "Inserted" use a live region
  (`Modifier.semantics { liveRegion = LiveRegionMode.Polite }`) or are focused.
- The wordmark reads as "Replyly" (`ReplylyWordmark` sets it).
- Never put chat text in a log to debug TalkBack (AGENTS.md rule 2).

## Brand assets

- **Launcher icon**: adaptive, vector only. `drawable/ic_launcher_background.xml` (ink),
  `ic_launcher_foreground.xml` (white bubble, ink text lines, marigold sparkle),
  `ic_launcher_monochrome.xml` (themed icons), wired in `mipmap-anydpi/ic_launcher{,_round}.xml`.
- **Mark** (`drawable/overlay_button_icon.xml`): the same bubble + sparkle in one tintable colour
  (lines are holes). The floating button uses it.
- **Sparkle** (`drawable/ic_sparkle.xml`): four-point star, tint it.
- **Wordmark**: `ReplylyWordmark(style = …)`: lower-case "replyly" + marigold sparkle.

## Do and don't

| Do | Don't |
|---|---|
| Use `MaterialTheme.colorScheme` roles and `ReplylyTheme.colors`. | Hard-code hex colours or `Color.Black` in screens. |
| Use `Spacing`, `Radii`, `Elevation`. | Invent new dp values for padding. |
| Separate with surface tone (`surfaceContainer*`). | Stack shadows and outlines on every card. |
| One accent moment (the sparkle). | Marigold buttons, marigold text, gradients. |
| Sentence-case, plain verbs: "Insert", "Try again", "Open settings". | ALL-CAPS labels, "Submit", "Oops!". |
| Errors say what happened and the one thing to do. | Apologise or show raw exception text. |
| Build animations via `ReplylyTheme.motion`. | Call `tween()` / `spring()` directly, or start loops when `reduced`. |
| Play haptics via `HapticEvent` (`rememberReplylyHaptics`). | Call `performHapticFeedback` directly. |
| Add a screenshot test for every new screen and state. | Merge UI changes without recorded PNGs. |
| Keep insert-only: a card tap only sets text. | Add any send, click or Enter action on the chat app. |

## Enforced rules

`DesignRulesTest` (in `app/src/test/.../invariants/`, part of the gate) scans every Kotlin file
in `src/main` **outside `ui/theme/`** and fails on:

| Pattern | Use instead |
|---|---|
| `Color(0x…)`, `"#RRGGBB"` / `"#AARRGGBB"` | `MaterialTheme.colorScheme` roles, `ReplylyTheme.colors`; View code: `replylyColorScheme(…)`. New colours go in `Color.kt`. |
| bare `tween(` / `spring(`, `TweenSpec(` / `SpringSpec(`, importing them | `ReplylyTheme.motion.tween(…)` / `.spring()`; View code: `ReplylyMotion.forContext(context)`. |
| `RoundedCornerShape(` with a dp literal | `MaterialTheme.shapes.*`, `SheetShape`, or `RoundedCornerShape(Radii.X)`. |
| `performHapticFeedback(` | `rememberReplylyHaptics().perform(…)` / `view.performReplylyHaptic(…)`. |

The failure lists each `file:line` and the token or helper to use. Fix the code; don't widen the
test. A genuinely new token belongs in `ui/theme/` (with a row in this file).
