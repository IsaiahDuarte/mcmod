# Q135 — Typing in the Program Controller (2026-11-01)

From playthrough note 22 and `docs/image_1.png`.

## Crash report

- **Crash reports:** the Prism instance (`All the Mods 10 Aeronautics ATM10A/minecraft`) has only
  `crash-2026-11-01_06.27.34-fml.txt`, and the test server has four from 06:27–06:28. All are
  mod-loading failures (`factorycore requires neoforge 21.1.252`, found 21.1.250), from before
  the playthrough.
- **Client logs:** `latest.log` has no errors from `dev.izzy`.
- **Pasted trace:** the stack in the notes is Polymorph failing to mixin JEI's
  `RecipeTransferUtil`, logged as an error and not fatal. It was triggered by a click on a JEI
  ingredient that opened JEI's recipe screen. That is the likely "crash": JEI's recipe screen
  replacing the editor mid-typing. JEI reads R and U as hotkeys unless a vanilla `EditBox` has
  focus, and our editor is not one.

## Change

- **`TextKeys`:** a client listener at HIGHEST priority on `ScreenEvent.KeyPressed.Pre`. For a
  screen implementing `TextKeys.Capture` whose text widget has focus, it delivers the key to the
  screen and cancels the event, so JEI, our guide key and other mods never see it. Escape is left
  alone. `ProgramScreen` implements it while the editor has focus.
- **`Tooltips.show`:** wraps lines to at most half the screen (and 260 px), and queues the
  tooltip after the widgets with a positioner that flips and then clamps so the box never leaves
  the screen. The editor's error tooltip uses it.

## Tests

- **`TooltipsTest`:** placement right of the cursor, a flip near the right edge, and a sweep of
  cursor positions with a box wider than either side, which stays inside the 4 px margins.
- **`ProgramAuthoringClientVerification`** (`runTerminalRestartClient`, passed): with the editor
  focused, `ClientHooks.onScreenKeyPressedPre(R)` is cancelled and the text is unchanged; without
  focus it is not cancelled.
- **Screenshot:** `program-error-hint-scale-2.png` shows the wrapped tooltip on screen, drawn
  over the buttons.

## Not verified

- **JEI itself:** not exercised in `runJeiClient` with a hovered ingredient. The change cancels
  the event JEI listens to, so a check in the user's pack is still needed.
