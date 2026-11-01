# Q27 — Infinite fluid cell (2026-10-31)

QD14 resolved: infinite fluid cell yes, bulk cell tools no. ADR 0048 amended, and the SPEC
storage table and "Later" list updated.

## Change

- **Tier:** `CellTier.INFINITE_FLUID` (fluid, level 3, `Long.MAX_VALUE`, infinite), added after
  the existing tiers so their ordinals are unchanged.
- **Item and art:** `infinite_fluid_cell` is registered from the tier loop. Its texture comes from
  `tools/art/generate_art.py` (the fluid line now includes `infinite`, with the Infinite tier's
  colours and ∞ mark). `runData` generated its model, and the program vocabulary now lists it.
- **Upgrade:** the Infinite Module upgrades an Advanced fluid cell through the existing
  `CellTier.upgrade` and `DeviceRegistry.resize`. No new recipe.
- **Core ledger:** `LedgerState` and `ResourceLedger` (constructor and `resize`) allowed infinite
  only for items. They now refuse it only for energy.

## Tests

- **`RecipeProgressionTest.frozenNumericTableMatchesTheImplementation`:** pins the new capacity
  row, checks that only the two Infinite tiers are unbounded, and that Advanced fluid upgrades
  to Infinite fluid.
- **`DriveScreenGameTests.fullDriveRefusesWithoutLossAndAFluidCellReachesInfinite`:** replaces
  the old assertion that fluid cells stop at Advanced.
  - In the drive screen, the Infinite Module upgrades the Advanced fluid cell in place (same
    backing), and the module is used up.
  - The cell then stores four times an Advanced cell's capacity.
  - A `DeviceSavedData` save/load round trip keeps it infinite and fluid, with the same
    contents.
- **`ResourceAccountingTest.fluidLedgersMayBeInfiniteButEnergyLedgersMayNot`:** a fluid ledger
  resizes to infinite, takes the rest of a signed long, and round-trips through
  `LedgerStateCodec`. An infinite energy ledger is refused. The old assertion that infinite
  fluid ledgers throw now uses energy.
- **`StorageDriveGameTests.fluidTierAndPortableReloadCannotCreateOwners`:** the Infinite Module
  in the offhand now succeeds. The cell holds 5,000,000 mB, and the stock and claims survive the
  bad placed reload offline.
- **`RecipeGameTests`:** `infinite_fluid_cell` is in the list of items obtained by upgrade, not
  by recipe.
- **Test spacing:** the drive-screen test sent five clicks in one tick. Before Q127 the gate
  dropped every click after the first, so its downgrade assertions passed without anything
  happening. Its clicks are now 3 ticks apart, and each step's effect is asserted.
- **Full GameTest run:** 503 of 503 pass.
