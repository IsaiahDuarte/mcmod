# Q78 — Fresh cell on creative pick (2026-10-31)

`CellItem.inventoryTick` checks a creative player's cells. A cell whose ledger sits in a drive bay,
or the later of two of the player's stacks with the same ledger, is unbound with
`CellHandleData.clear` and loses its summary. It becomes a fresh, empty cell of the same tier, and
installing it starts a new ledger. A malformed handle or a quarantined registry changes nothing.
For the limits, see the ADR 0005 note.

## Checks

- `DriveFeedbackGameTests.creativeCopyOfAStoredCellIsAFreshCell`:
  - a survival player's copy of a stored cell is left alone;
  - in creative the same copy becomes an unbound cell with no summary;
  - the drive's cell keeps its handle and its 40 cobblestone;
  - of a removed cell and its copy, the first keeps the ledger and the second is fresh.
- 482 GameTests passed twice.
- `python3 scripts/verify.py`: PASS.
