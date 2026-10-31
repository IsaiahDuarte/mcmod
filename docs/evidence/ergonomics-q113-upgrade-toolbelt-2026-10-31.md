# Q113 — Upgrade toolbelt (2026-10-31)

**Item.** `upgrade_toolbelt` (stack of 1; recipe: string, leather, iron ingot) carries an
`UpgradeBelt(throughput, overdrive)` component, 0–64 each. `UpgradeBeltItem` takes cursor upgrades
when clicked with them and gives one back (Overdrive first) on a right-click with an empty cursor.
Its tooltip shows the counts.

**Slot.** `UpgradeSlots.offer` names the device's next upgrade the toolbelt holds. With an empty
cursor, `BUTTON` installs it via `fromBelt`, which charges the toolbelt only after the device
accepts. `REMOVE` (right-click) takes the top upgrade out, into the toolbelt when it has room
(`intoBelt`). The five device menus accept both buttons. `UpgradeSlotWidget` draws the offer as a
faded icon with a **+** badge and adds a tooltip line.

**Art and data.** `generate_art.py` `upgrade_toolbelt()` (belt, buckle and two tier-colored
pouches) and the reference sheet; `runData` (recipe, advancement, item model).
`RecipeGameTests` now expects 28 recipes. `RecipeProgressionTest` classifies the toolbelt as
starter stage, with leather and string as starter vanilla inputs.

## Checks

- `ThroughputUpgradeGameTests.toolbeltInstallsAndTakesBackWithoutTheCursor`, on an Importer port
  with a toolbelt holding 1 + 1:
  - the slot offers Throughput;
  - clicks install Throughput, then Overdrive, from the toolbelt;
  - a full slot offers nothing;
  - two right-clicks put Overdrive, then Throughput, back into the toolbelt;
  - the cursor stays empty throughout.
- 483 GameTests passed twice.
- Presentation client `buffer-slot-goal-scale-4.png`: with a toolbelt (2 + 1) in the inventory, the
  Buffer's empty upgrade slot shows the faded Throughput card with **+**.
- `python3 tools/art/generate_art.py --check`, `python3 scripts/verify.py`: PASS.
