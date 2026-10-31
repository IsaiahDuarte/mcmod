# Q72 — Cell and drive feedback (2026-10-31)

**Cell tooltip.** A client cannot read a cell's ledger, so a cell carries a `cell_summary` data
component written when it leaves a drive (`StorageDriveEntity.removeCell`, which breaking and
upgrading also use) and removed when it is installed. It holds stored units, capacity (−1 for
infinite), the type count and the eight largest entries of the first catalog page. A cell's contents
change only while it sits in a drive, so the snapshot stays true while it is carried. The tooltip
reads "Stored: S / C (P%) · T types" and draws the entries as slots with amounts
(`CellTooltipRenderer`, like the pattern tooltip).

**Bay lamps.** `StorageDriveBlock.BAYS` changed from booleans to `DriveBay` (empty, online, near
full at ≥ 75%, full, offline). The drive computes them from the network status, each cell's
ownership (current, unconflicted, at this bay) and its total and capacity. It does this on every
change and once a second from the block's ticker, using one ownership lookup per bay, the same
cadence as other device lamps. Changing a lamp is cosmetic and never rebuilds the network. Each
state has its own glyph (one lamp, two amber, two red plus a red fill bar, one dark), not only a
colour. An existing world's saved `bay_N=true` states fall back to the default (empty) on load,
and the ticker sets the right lamp within a second.

## Checks

- `DriveBayTest`: thresholds (749/1000 online, 750 near full, 1000 full), infinite never full,
  no overflow at `Long.MAX_VALUE`.
- `DriveFeedbackGameTests.bayLampsFollowFillAndNetworkAndARemovedCellSummarizesItsContents`:
  an empty cell's bay reads online; filling to three quarters reads near full; filling the rest
  reads full; removing the controller reads offline. Taking the cell out empties the bay, and the
  cell's summary is stored = capacity, 100%, 3 types, with entries cobblestone, oak log, iron
  ingot largest first. The tooltip has the stored line with those numbers, and its image is the
  summary. 475 GameTests passed.
- `PresentationStateGameTests.lampsAndBaysMirrorDeviceState` updated for the enum, and still passes.
- Presentation client `drive-bays.png`: a wired drive with an empty cell (blue), one at 80%
  (amber) and a full one (red bar), beside an unwired drive whose two cells are dark.
- `python3 tools/art/generate_art.py --check` and `python3 scripts/verify.py`: PASS.
