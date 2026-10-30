# M145 — Recovery after a network rebuild

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) finding M145, investigated on request after Q3
made it frequent. Design: [ADR 0056 amendment](../decisions/0056-program-limits-and-overload.md#amendment--rules-take-turns-2026-10-30-ergonomics-plan-m145);
docs in [PROGRAMS](../modules/PROGRAMS.md), [BUFFERED_INTERFACES](../modules/BUFFERED_INTERFACES.md)
and [MACHINE_INTERFACES](../modules/MACHINE_INTERFACES.md).

## Reproduction

M145 blamed neighbouring GameTest structures. A copy of the failing furnace test that calls
`WorldNetworks.changed` on its own network once after deploying failed in all 6 runs (three
variants, two runs each), and so did a copy of the stock-buffer test with one rebuild before the
seven are taken (5 of 5). So any rebuild can trigger these failures; a neighbour only makes one
likely. On a server, rebuilds also follow chunk loads next to a network (M1).

## Defects found

1. **A program rule could starve forever.** A controller ran one command at a time from a
   single queue. A rule that ran again removed its queued commands and appended the new batch
   at the end. Rules run in source order, so the furnace rule always re-queued before the output
   rule and pushed it back each interval. Once commands drained slower than ten ticks (a
   rebuild or busy server slowing storage replies), the output rule never ran and logged nothing.
   Logging showed only the furnace rule's moves executing.
2. **Buffers and machine presets stayed refused after a rebuild.** Both keep a storage-catalog
   cursor between attempts. A cursor names the network scope (with the topology generation) it
   was read in. After a rebuild it is refused synchronously as DENIED, and that refusal path
   kept the cursor, so every later attempt was refused again. A stack trace in the buffer's
   `admitted` path showed the catalog `page` call refusing.

Not fixed here, now **M147** and slice **Q108**: storage discovery pages through the world's
whole device and node registries, eight handles per page. The same buffer read more than 80 empty
pages from other GameTest networks before reaching its own cell, so it sometimes misses the test's
deadline.

## Fixes

- `core.scheduling.RuleQueue`: pending commands by rule, taken round-robin, each rule keeping its
  latest batch only and its turn while it has commands queued; bounded at 64 with the existing
  "queue full" log. `WorldPrograms` uses it in place of its `ArrayDeque`.
- `WorldBufferImports.admitted` and `WorldMachinePresets.admitted` drop the cursor and page count
  when a submission is refused, as their asynchronous failure paths already did.

## Checks

- `RuleQueueTest` (4 unit tests): a rule behind one that queues twelve commands each ten-command
  interval runs every interval; rules alternate and keep their own order; a re-run rule
  replaces its batch and keeps its turn; the bound drops and reports the excess.
- `SideAndSlotsGameTests.aBusyRuleDoesNotStarveTheRuleAfterIt`: a rule with twelve storage reads
  that never find stock, then a rule that empties a chest into storage. On the old code the
  chest was never emptied (55 shortages logged, nothing moved); it passes with `RuleQueue`.
- `BufferExportGameTests.stockRecoversAfterANetworkRebuild`: the stock test with a rebuild before
  the seven are taken. The same scenario failed 5 of 5 on the old code (DENIED); it passes.
- `MachinePresetGameTests.itemStockRecoversAfterANetworkRebuild`: an item Stock preset rebuilt
  at its goal must replenish after consumption. It failed on the old `WorldMachinePresets` (the
  only failure in that run) and passes with the fix.
- 19 full GameTest runs with the fixes (16 alone, some with temporary logging since removed, and 3
  in `verify.py`): `oneInterfaceFeedsFurnaceTopAndSideAndEmptiesItsBottom` failed 0 times,
  against 12 of 16 before; `stockStopsAndReplenishesWithoutOverWithdrawing` failed 7 times, always
  with status SHORTAGE (once with a page in flight), never DENIED. The two failures traced page
  by page were both the M147 scan; the other five were not traced. No other test failed.
- `python3 scripts/verify.py`: PASS (all 443 GameTests) on the third run; the first two failed
  only on the stock test above.
