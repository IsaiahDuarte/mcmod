# Q22 — Wrench

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) Q22 (M22). Design:
[ADR 0059 amendment](../decisions/0059-unified-interface.md); player guide, Wrench and recipe table.

## Checks

`WrenchGameTests` (own batch `wrench`, see below):
- `aTurnedExporterFeedsItsNewFace`:
  - An Exporter faces east into a chest and keeps 8 iron there.
  - The owner's wrench turns it to south, the next face touching a block (down, up and north are clear).
  - It is the same block entity and lease, and it then keeps the south chest at 8. The east chest is left as it was.
- `aTurnWaitsForAMoveInTransitAndNeedsEdit`:
  - A staged move whose context names the Exporter is unresolved, and the turn is refused with the face unchanged.
  - After the move is cancelled and retired, a player without Edit is refused and the owner turns it.
- `sneakPickUpKeepsSettings`:
  - The Exporter's rate is set to 32, then sneak-use picks it up into the inventory and nothing is dropped in the world.
  - Placed again, it has the same lease id and rate 32.

Other checks:
- `RecipeProgressionTest`: `wrench` is a Starter item. `RecipeGameTests` now counts 27 recipes.
  Generated recipe, advancement and item model come from `runData`.
- `runPresentationClient` PASS: `held-first-person-wrench.png` shows the new texture in hand.
- `python3 scripts/verify.py` PASS (470 GameTests).

## Batch-sensitive tests (follow-up Q114)

With the three Wrench tests in the default batches, unrelated tests failed intermittently in 5
of 6 runs:
- `fullInventoryRefusesBeforeConsumingAnything` ("Terminal still open")
- `partitionLimitsInsertsSurvivesUpgradeAndKeepsContentsReachable`
- `presetDraftSamplesDoNotDeployOrConsumeUntilExplicitSave`
- once, `recoveryQuantumRetainsRemainderAcrossScheduledSteps`

Bisecting by disabling one Wrench test at a time still failed. With all three disabled, 2 of 2
runs passed. So any two added tests shift the default batch layout enough to expose the flake;
no particular Wrench test causes it. The Wrench tests run in their own batch: 3 of 3 full runs
passed.

### Fixed here

The cause is timing, not shared state: a new network can rebuild again just after it first
becomes valid, and how soon depends on server load. Tests that act right after the first
"ready" lose their menu or their submission. Fixed:
- `TerminalGameTests` / `TerminalCraftingGameTests` `loaded()`: reopen the terminal if a
  rebuild closed it, and check again after the broadcast (which closes a stale menu). Before,
  an unchecked cast crashed the whole test server.
- `DrivePartitionGameTests`: deposits wait for an open terminal, are resent at most every few
  ticks (the command rate gate drops closer ones), and `ready()` accepts any loaded status.
- `MachineSettingsGameTests` preset-draft test: waits until the network scope has been stable
  for 10 ticks before opening the editor.
- `TransferProgressGameTests` recovery test: a step may move less than a full quantum under load,
  and a refused submission (network rebuilding) is resubmitted; the test still checks that the
  source is never extracted again and nothing is lost.

With the Wrench tests in their own batch: 7 of 7 full runs passed after these fixes. Moved back
into the default batches: 4 of 5 passed; the failure was `fullInventoryRefusesBeforeConsumingAnything`
crashing on another unchecked `(TerminalMenu)` cast inside a test step, and once
`machineSavedBeforeItsInsertionFreezesTheJobUntilResolved` ("Operator resolves"). The Wrench
tests stay in their own batch; the remaining casts are Q114.
