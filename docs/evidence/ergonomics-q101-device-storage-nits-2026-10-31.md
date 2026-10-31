# Q101 — Device and storage nits (2026-10-31)

Done in parts, each committed with a `Q101:` subject.

## M120: cells refuse hidden stores (confirmed bug, fixed)

**Check first.** Nothing refused it: with the new check turned off,
`PortGameTests.cellsRefuseFilledContainersAndBoundCells` fails, and an Importer moves a filled
shulker box and a bound cell into a cell.

**Fix.** `NestedContents.refused` covers a bound cell (or a malformed handle) and an item whose
`CONTAINER` or `BUNDLE_CONTENTS` holds anything. Empty shulker boxes, bundles and fresh cells are
ordinary items. An item without components is never decoded. It is checked where items enter a
cell:
- `TransferEndpoints.Port.admit`/`insert`, which every network transfer uses: refused as
  `UNSUPPORTED` with 0 moved;
- `TerminalContainers.apply` for puts (compensating restores are not checked, so nothing taken can
  be stranded).

**Test.** `PortGameTests.cellsRefuseFilledContainersAndBoundCells`: cobblestone and an empty
shulker box are imported; the filled box and the bound cell stay in the chest; storage holds
neither. 484 GameTests passed.

## M121: taking empty cells apart

`CellItem.use` (Sneak-use in the air; `doesSneakBypassUse` keeps Sneak-use on a drive installing)
gives back the starter recipe's ingredients, read from the recipe manager so they follow the
recipe, plus Expansion/Advanced/Infinite Modules for each level above starter. A bound cell must
hold nothing: no stock and no reservations. Its ledger is then forgotten with the new core
`DeviceRegistry.retireEmpty`, before anything is given back. It retires only a current,
unconflicted, detached, empty ledger; anything else is refused unchanged. A cell with stock is
refused with a message.

**Tests.**
- `DeviceRegistryTest.onlyADetachedEmptyLedgerCanBeRetired`: placed, stocked and stale ledgers stay;
  an empty portable one goes once, and its handle is then unknown.
- `DriveFeedbackGameTests.sneakUseTakesAnEmptyCellApart`:
  - a fresh cell gives 4 iron, 4 redstone and a chest;
  - a cell holding 3 cobblestone is refused and untouched;
  - an empty bound cell's ledger is forgotten;
  - an Expanded cell also returns its Expansion Module.
- 485 GameTests passed.

## M119: hoppers move whole cells

`DriveCellHandler` (registered as the drive's `ItemHandler.BLOCK` capability on every face)
exposes the four bays with a limit of 1. A cell goes into an empty bay through
`StorageDriveEntity.installCell`, and a whole cell comes out through `removeCell`: the same calls
the screen makes, so leases, detachment and the Q72 summary are unchanged. Only cells are
valid. Anything the drive refuses (a duplicate or stale cell, a drive needing recovery) stays in
the hopper. The handler is always present, and it refuses when the drive can't take a cell
(a capability that is sometimes null would be cached stale).

**Test.** `DriveFeedbackGameTests.hoppersMoveWholeCellsInAndOutOfADrive`: a hopper above installs
a cell holding 12 cobblestone into bay 1 (its ledger is placed). A hopper below then takes it out
whole, detached, with its 12 cobblestone. 486 GameTests passed.
