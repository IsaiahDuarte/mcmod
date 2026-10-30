# Q15 — Wireless range upgrade

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) Q15 (M15). Design:
[ADR 0054 amendment](../decisions/0054-throughput-upgrades.md#amendment--access-point-range-2026-10-30-ergonomics-plan-q15);
balance row in [ADR 0048](../decisions/0048-final-balance.md); player guide throughput table.

## Checks

- `WirelessGameTests.rangeUpgradesWidenReachAndRaiseTheCost` (survival player, real
  `useItemOn` clicks): a base point refuses a handheld 100 blocks away; Throughput installs on
  use (item consumed, no menu), the handheld then opens at 100 blocks, and an open session
  drains a multiple of 32 FE per tick; Overdrive installs, 300 blocks is refused and 200
  opens, at 64 FE per tick and range 256; a save/load keeps ENDGAME; breaking the point drops
  one Throughput and one Overdrive.
- Regression for the click order: with `ThroughputUpgradeItem.onItemUseFirst` removed, the same
  test fails at the first install (tier stays BASE: the Access Point's own use showed its status
  instead). Restored afterwards.
- The existing `handheldUsesAccessPointRangePowerAndActionCost` (base 64 blocks, 16 FE) still
  passes, and `RecipeProgressionTest`'s frozen base constants are unchanged.
- Full GameTest run: all 450 passed.
- `python3 scripts/verify.py`: PASS on the first run (all 450 GameTests).
