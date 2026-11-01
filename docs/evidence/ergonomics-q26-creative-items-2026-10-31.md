# Q26 — Creative items (2026-10-31)

QD13 resolved yes. ADR 0048 and ADR 0039 amended.

## Change

- **Creative Energy Source:** `CreativeEnergySourceBlock` and `CreativeEnergySourceEntity` in
  `platform/power`. It exposes FE that never runs out and pushes `Integer.MAX_VALUE` per tick to
  each face. The block model and blockstate are hand-written; the textures come from
  `generate_art.py` and the item model, loot table and pickaxe tag from `runData`.
- **Creative Wireless Terminal:** `WirelessTerminalItem(creative = true)`.
  - It has no battery and needs no charge.
  - A session opened with it reaches the whole dimension (`WirelessAccess`). Swapping in an
    ordinary handheld ends that reach.
  - Pick Block from it is free and has no range limit.
  - Its texture comes from `generate_art.py`. It is in the Curios charm tag.
- **Creative cells:** the Infinite Item and Infinite Fluid Cells, both in the creative tab. An
  AE2-style item-minting cell is declined (ADR 0048 amendment).

## Tests

- **`CreativeItemsGameTests`:**
  - **`theCreativeSourcePowersAnAccessPointAndNeverRunsOut`:** extracting `Integer.MAX_VALUE`,
    then 1,000 FE, never lowers its stored FE, and it takes nothing in. An access point beside
    it fills with no other power.
  - **`theCreativeHandheldNeedsNoChargeAndReachesAcrossTheDimension`:** 250 blocks straight
    above the access point, a fully charged ordinary handheld is `out_of_range`. The uncharged creative
    one opens the terminal and stays open. Swapping in the ordinary handheld closes it.
  - **`theCreativeTabOffersTheCreativeItems`:** the tab lists the source, the handheld and both
    Infinite cells.
- **`RecipeGameTests`:** both new items have no recipe, and every other item keeps exactly one
  survival source.
- **Full GameTest run:** 506 of 506 pass.
- **Test placement:** the first version moved the player 300 blocks along X, and twice in
  `verify` `gridShortcutsMoveOneAndSwapIntoTheHotbar` failed ("Shift+wheel down stores exactly
  one"). The far point was moved straight up on the guess that loading a chunk was the cause.
  - **Correction (Q28):** that test failed once more with no far move in the suite, so the guess
    was wrong. The flake is recorded as Q129. The straight-up far point is kept: it is still a
    valid test of reach.

## Not covered

- **Pick Block:** the free, unlimited Pick Block from the creative handheld has no GameTest of
  its own. It uses the same checks as the ordinary pick, with the range and charge steps
  skipped.
