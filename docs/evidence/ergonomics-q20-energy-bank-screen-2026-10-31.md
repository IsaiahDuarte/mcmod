# Q20 — Energy Bank screen and in-place upgrade

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) Q20 (M20). Design:
[ADR 0012 amendment](../decisions/0012-energy-bank-access.md); player guide, Energy Bank.

## Checks

- `EnergyBankAccessTest.lastTickReportsTheCompletedTicksFlow`:
  - The current tick reads idle.
  - The next tick reads 700 in / 200 out.
  - Moving energy in that tick keeps the previous reading.
  - Idle ticks read idle.
- `EnergyBankScreenGameTests.moduleSlotUpgradesInPlaceKeepingEnergy`: a Starter bank on a valid network holds 60,000 FE, and an empty-hand use opens the screen showing that charge.
  - An Advanced Module (skips Expanded) is refused and stays in the slot; closing the screen returns it.
  - A player without Edit is refused.
  - An Expansion Module turns the block into an Expanded bank with the same lease backing and 60,000 FE. The module is used up and the screen stays valid.
  - A second Expansion Module (no step, or back) is refused.
  - The bank rejoins its network online with the Expanded capacity.
- `runPresentationClient` PASS:
  - `bank-scale-{1,4}.png` show the charge meter at 40,000 / 100,000 FE, the in/out line, the limit, "Network: Valid" and the Module slot.
  - The first capture had the "Module" label running past the panel edge at scale 1. It moved left of the slot.
- `python3 scripts/verify.py` PASS (467 GameTests).

The offhand path is unchanged (`CellUpgradeItem`).
