# Q67 — Saved views

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) Q67 (M79). Design:
[ADR 0052 amendment](../decisions/0052-terminal-search-preferences.md#amendment--saved-views-2026-10-30-ergonomics-plan-q67);
player guide under Saved views.

## Checks

- `CraftableGridGameTests.savedViewsRestoreSearchSortAndView`:
  - Saves `oak`, Qty ↑ and the All view as view 2.
  - Changes all three, sets Tall, and reopens the terminal.
  - Applying the empty view 1 changes nothing. Applying view 2 restores `oak`, Qty ↑ and All, and Tall stays on.
  - Clearing view 2 leaves five empty entries in the player's saved `Views`.
- `TerminalPacketsTest.savedViewsRoundTripAndTheirCommandsAreBounded`:
  - Five views round-trip; four are refused.
  - A view carrying layout flags, or an empty view with content, is refused.
  - `sortOf` drops Sync and Focus.
  - VIEWS commands outside slot 0–4 or amount 1–3 are refused. The action bound is now VIEWS + 1.
- `runTerminalClient` PASS:
  - With the ★ row open, an empty view 1 click saved `@minecraft` / Qty.
  - Captured at scale 4 (`terminal-saved-views-scale-4.png`) and scale 1 (`terminal-saved-views-scale-1.png`): view 1 white, 2–5 grey, then ⇕, ★ and the lamp. The labels fit at both scales.
  - Right-click then emptied view 1.
- Not exercised graphically: the button tooltips, and Shift-click (its server effect, a save over a set view, is the same VIEW_SAVE the GameTest covers).
- `runPresentationClient`: failed at first (stage 205). The saved-view buttons start visible after a GUI-scale re-init, until the next tick hides them, so a click on ⚒ in that tick went to an invisible saved view. Visibility is now also set in `init`; `runPresentationClient` then PASS, and its timeout message now lists the visible buttons. `runJeiClient`, `runTerminalRestartClient` and a second `runTerminalClient` PASS.
- `python3 scripts/verify.py`: PASS (all 460 GameTests).
