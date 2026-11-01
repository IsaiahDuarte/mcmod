# Q134 — Shift-drag to store (2026-11-01)

From playthrough note 17.

## Change

- **`ShiftDrag`** (client):
  - A Shift + left press on a player inventory slot starts a drag.
  - Each further inventory slot with items that the drag passes over is moved once.
  - Releasing the button, or letting go of Shift, ends it.
- **Terminal:**
  - The pressed slot is stored by the existing Shift-click deposit.
  - Dragged-over slots are queued and stored one per client tick while the terminal isn't busy.
    The server takes one move at a time and drops commands while busy, so sending them all at
    once would store only the first.
  - The queue is cleared outside the Items tab and the Patterns view.
- **Portable Storage:** each dragged-over slot gets a vanilla `QUICK_MOVE` click.
- **Fix found while testing:** the terminal's Shift-click deposit took its slot from the last
  rendered frame's hovered slot. It now takes the slot at the click position (`slotAt`), the same
  rule vanilla uses to pick up.
- **Player guide:** the Depositing and Storing lines mention Shift-drag.

## Tests

`TerminalClientVerification` (`runTerminalRestartClient`, passed): in stage 6, the server puts 16
cobblestone in inventory slots 9, 10 and 11. With Shift held through the test hook, the tour
presses on slot 9, drags over 10 and 11 (11 twice), and releases.

- All three slots empty.
- Storage holds exactly 48 cobblestone.

Not covered by a test: Portable Storage shares `ShiftDrag`, and its move is the vanilla
quick-move it already used for Shift-click.
