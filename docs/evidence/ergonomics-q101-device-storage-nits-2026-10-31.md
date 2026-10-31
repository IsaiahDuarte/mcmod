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
