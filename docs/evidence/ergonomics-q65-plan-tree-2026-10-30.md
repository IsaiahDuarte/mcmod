# Q65 — Plan tree and notifications

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) Q65 (M67). Design:
[ADR 0031 amendment](../decisions/0031-crafting-jobs.md#amendment--plan-tree-quiet-jobs-and-retry-2026-10-30-ergonomics-plan-q65);
player guide under Plan and Finished jobs.

## Checks

- `PlanTreeTest` (3 tests): torches → sticks → planks → logs, with coal missing, comes out depth
  first with amounts scaled by each step's crafts. The row limit truncates and says so, a
  changed pattern is a leaf, and a two-recipe loop is shown once as a LOOP leaf.
- `CraftingJobGameTests.aJobStartedWithNotifyOffSendsNoNotice`: two stick jobs, one started
  quiet. A save/load of the job table keeps the quiet flag on that job only. After both finish,
  only the Notify-on job left a notice, and the quiet flag retired with its job.
- `CraftingJobPersistenceTest.aQuietJobRoundTripsAsSchemaSeven`: without quiet jobs the table
  stays schema 5. With one it is schema 7, loads back quiet and saves identically. `Quiet` in a
  schema-6 table, or `Quiet: false`, is refused. The existing unknown-schema case moved from 7
  to 8.
- `runPresentationClient` PASS: the 200-stick shortage plan in Tree mode at GUI scale 4
  (`jobs-plan-tree-scale-4.png`) and scale 1 (`jobs-plan-tree-scale-1.png`): Stick → Oak Planks
  → Oak Log (missing) with connector lines. The same step checks that Start reads Retry after
  the failed preview; the ✉ toggle is shown on.
- `TerminalJobPacketsTest`: action bound now NOTIFY + 1.
- Not exercised graphically: Ctrl+wheel zoom (the captures use zoom 1), and the Retry and ✉
  clicks (NOTIFY's server effect is covered by the GameTest through `submit(..., false)`).
- `runTerminalClient`, `runTerminalRestartClient` PASS (the restart reads the job table).
- Found while rerunning `runJeiClient`: the Q5 processing transfer took JEI's cycling display
  ingredient, so the furnace step sometimes filled red sand instead of sand (it failed once
  here, and passed in earlier runs). It now takes the first alternative the player has
  (inventory or terminal rows), else the first listed one. `runJeiClient` then PASS.
- `python3 scripts/verify.py`: PASS (all 459 GameTests).
