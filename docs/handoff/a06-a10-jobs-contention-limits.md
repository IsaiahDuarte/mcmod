# Handoff: A06/A07/A08/A10 slice (jobs UI, script/craft contention, program limits)

Branch: `worktree-agent-a614c8de168bafe44`
(worktree `.claude/worktrees/agent-a614c8de168bafe44`). Reserved ADR number: 0056 (not written yet).

## Done (A10, uncommitted before this WIP commit)

- `core.program.HostBudget`: 256 host-work units per rule invocation. One unit per storage
  catalog page; label queries cost 1 + found/16. Exceeding it traps the invocation.
  `WorldPrograms.host(...)` charges every stock/machines query, including preview.
- `ProgramEvaluator.MAX_HOST_CALLS = 512` for friendly rules, the same limit as Wasm.
- `core.program.ProgramLog`: holds 16 lines of up to 160 characters. Immediate repeats collapse
  into "line (×n)". Each invocation keeps at most 8 guest log lines plus one suppression line.
  `ProgramControllerEntity` delegates its log to this class.
- `WorldPrograms` now checks scheduler admission:
  - A refused rule gets status `throttled` and logs one line when it enters that state.
  - It measures how late each invocation runs (`lag(controller)`). An invocation 20 or more ticks
    late gets status `delayed` and logs once.
- Bug found and fixed: a full shared scheduler queue could permanently stall a network rebuild.
  The refused `wake()` was never retried, so membership stayed REBUILDING. The fix:
  - `FairScheduler.submitReserved` adds `RESERVED_SLOTS` = 8 slots of maintenance headroom.
  - `WorldNetworks` uses it and retries `wake()` each tick while regions are active.
  - It also clears its own scheduling-limit diagnostic once admission succeeds.
- Tests:
  - `ProgramLimitsTest` (JVM, 5 tests).
  - `FairSchedulerTest.reservedMaintenanceSlots...`.
  - `ProgramLimitsGameTests` (3 GameTests): queue saturation throttles visibly and recovers; a
    saturated network still gives the program its share with bounded latency; a host-budget trap
    stops one program while its neighbour finishes. Ingots are conserved, counting in-flight
    staging. The two overload tests run in their own batches, and synthetic load expires on its
    own.

## Verified

- `./gradlew test --tests dev.izzy.factorycore.core.program.ProgramLimitsTest` — PASS.
- `./gradlew test --tests dev.izzy.factorycore.core.scheduling.FairSchedulerTest` — PASS.
- `./gradlew runGameTestServer` — PASS, 308 GameTests, before the reserved-slot/WorldNetworks
  change.
- Not rerun after the reserved-slot change and the backlog assertion edit:
  `runGameTestServer`, `python3 scripts/verify.py`. This commit is WIP.

## Next

1. Rerun `python3 scripts/verify.py`. Then add a GameTest that a topology rebuild completes
   while the queue is saturated: place a cable during the queue test and wait for VALID.
2. Write ADR 0056, update docs/modules/PROGRAMS.md and SCHEDULING.md, add lang keys
   `gui.factorycore.program.status.throttled` and `.delayed`, append an IMPLEMENTATION.md
   section and update the A10 row.
3. A08: program logs should give actionable reasons. BUSY means a crafting job leases the
   machine; storage stock fully reserved by jobs should be reported as such. Add a GameTest
   where a deployed program's `move` clears `output_not_clean` and then gets BUSY during the
   lease while the job collects exactly its output.
4. A06/A07 UI, not started:
   - Terminal Jobs-mode detail panel: clicking a job row sends a new DETAIL action and a
     `TerminalJobPackets.Detail` payload. It shows the plan tree, missing inputs, per-machine
     dispatch status, Retry (clear `retryAt`) and Cancel. Published-branch steps must hide
     branch machine positions.
   - A Crafting Interface status inspection.
   - A separate graphical client fixture using the GUI lock.
5. Published-branch multiplayer checks: not started.
