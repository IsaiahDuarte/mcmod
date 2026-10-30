# Q63 — Grid shortcuts

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) Q63 (M63, M64). Design:
[STORAGE_TERMINAL](../modules/STORAGE_TERMINAL.md#grid-shortcuts-q63); player guide under
Taking items and Depositing.

## Checks

- `TerminalGameTests.gridShortcutsMoveOneAndSwapIntoTheHotbar`: with 10 cobblestone stored,
  WITHDRAW 1 (Shift+wheel up) takes exactly one, DEPOSIT_ROW 1 (Shift+wheel down) stores it back,
  two CURSOR_ONE (Ctrl+wheel up) leave 2 on the cursor and 8 stored, and SWAP into hotbar slot 3
  stores the 3 dirt there first and fills the slot with the remaining 8 cobblestone.
- `TerminalGameTests.regionDepositStopsWhenStorageFills`: with 100 units of room, Space-click on
  the main inventory (DEPOSIT_REGION 1) stores the first 64 dirt and 36 of the next stack; that
  stack keeps 28, the third stack (64) and the hotbar (5) are untouched.
- `TerminalPacketsTest`: action bound now SWAP + 1; the view's new `all` count round-trips.
- `runTerminalClient` PASS: with Qty sort, the order is held (the `holdOrder` test hook stands
  in for Shift) while the server takes 21 of the top entry. The view re-sorts, but the shown grid
  keeps "Client hopper variant" first with its new count 2 (`terminal-sort-frozen.png`, which
  also shows "Showing 110 of 110" under the `@minecraft` search). The 21 are then put back and
  the remaining steps pass.
- Not exercised: the Shift+wheel, Ctrl+wheel, Space-click and hotbar-key input handlers and the
  cursor tooltip (client input only; their server actions are covered above).
- Full GameTest run: all 457 passed.
- `python3 scripts/verify.py`: PASS (all 457 GameTests); `runPresentationClient` and `runJeiClient` also PASS.
