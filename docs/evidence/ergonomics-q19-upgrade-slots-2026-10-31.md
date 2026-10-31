# Q19 — Upgrade slots

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) Q19 (M19, M88). Design:
[ADR 0054 amendment](../decisions/0054-throughput-upgrades.md); player guide, Speed upgrades.

## Checks

- `ThroughputTierTest.removalTakesTheTopStep`: ENDGAME→ADVANCED→BASE; a link's ENDGAME→BASE;
  BASE has nothing to remove.
- `ThroughputTextTest.upgradeTooltipsNameTheDevicesThatTakeThem`: the Throughput tooltip names
  Importer, Exporter, Interface, Cable Port, Crafter and Wireless Access Point by their block
  names; the Overdrive tooltip names the Gateway; the slot's "accepts" lines name the right
  upgrades (lang test).
- `ThroughputUpgradeGameTests.upgradeSlotInstallsRemovesInOrderAndClampsTheRate`, through
  `ConnectorSettingsMenu.clickMenuButton` on an open Storage Interface screen:
  - Overdrive before Throughput is refused and stays on the cursor.
  - Throughput, then Overdrive, install from the cursor and are used up; the synced slot state reads Overdrive; the rate is 1024.
  - A stranger cannot open the screen. The slot's server path refuses their removal (nothing changes) and returns a refused upgrade to their cursor.
  - An empty-handed click takes Overdrive out first (tier Throughput, rate clamped 1024 → 256). After the rate is set to 100, the next click takes Throughput out and keeps 100 (it fits).
  - A third click on the empty slot gives nothing.
  - A buffer at 576 drops to 256 when its Overdrive is removed.
- `runPresentationClient` PASS:
  - The slot panel shows beside the Exporter, Buffer and Crafter screens at scales 1 and 4 (`exporter-overdrive-scale-4.png` shows the Overdrive in the slot; `crafter-scale-1.png` shows an empty one).
  - New step: a real empty-handed press and release on the Exporter's slot takes the Overdrive out. The port reads Throughput at 256 items/tick, the player holds the Overdrive, and no item entity is dropped.
  - The tooltip text is recorded in `result.txt`.
- `runJeiClient` PASS: `jei-buffer-ghost.png` shows JEI's item list starting below the slot
  panel (the extra-area handler).
- `python3 scripts/verify.py` PASS (466 GameTests).

Found while testing: an upgrade change re-validates a port's network, which makes its open screen
stale, so the screen closes on the next broadcast. The first version set the removed item on the
cursor after that point, where it would have gone back to the inventory; the slot now hands items
over explicitly (cursor while the screen is open, else inventory). No path drops or duplicates an
upgrade.

Not done: the optional toolbelt (M89) is Q113. Gateway link removal is covered by the shared
`LinkModule.remove` path and the policy screen's slot, but no GameTest drives a linked pair through
the slot.
