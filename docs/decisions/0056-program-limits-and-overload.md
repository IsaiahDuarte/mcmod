# ADR 0056 — Program host/log limits and visible overload

Status: implemented. Native and JVM checks pass in the canonical gate (2026-10-19).

## Context

A10 requires bounded host work, events and logs for every program. Under world overload it
requires visible throttling and failure isolation. Before this ADR:

- A friendly rule could issue unbounded catalog and label queries.
- Logs could be flooded.
- A refused scheduler admission was silent.
- A full shared queue could strand a network rebuild as REBUILDING forever, because the refused
  `wake()` was never retried.

## Decision

- **Host-work budget.** `HostBudget` allows 256 host-work units per rule invocation. One storage
  catalog page costs one unit; a label query costs 1 + found/16. `WorldPrograms.host(...)`
  charges every stock and machines query, previews included. Exceeding the budget traps only
  that invocation. Friendly rules share Wasm's limit of 512 host calls
  (`ProgramEvaluator.MAX_HOST_CALLS`).
- **Logs.** `ProgramLog` holds 16 lines of at most 160 characters. Immediate repeats collapse
  into `line (×n)`. Each invocation keeps at most 8 guest log lines plus one suppression line.
- **Visible overload.**
  - **Throttled.** A refused admission sets status `throttled`, which shows as "Throttled: server
    busy, retrying". The rule is coalesced into its next interval, and the change is logged once.
  - **Delayed.** An invocation 20 or more ticks after its due tick sets status `delayed` ("Running
    late: server busy"). That is logged once too.
  - **Unchanged budgets.** Neither state changes fair budgets or quotas.
- **Reserved maintenance.** `FairScheduler.submitReserved` adds 8 slots of headroom that only
  network maintenance may use. `WorldNetworks` retries `wake()` every tick while regions are
  active, so topology rebuilds always progress.

## Alternatives

- **Raise the shared queue limit.** Rejected: it only moves the stall further out.
- **Silent coalescing.** Rejected: SPEC requires visible throttling.
- **Per-program threads.** Forbidden.

## Consequences

- **Rotation is fast for cheap work.** Under credit-heavy contention a cheap program is served
  within a few ticks. In testing, 480 maximum-credit competitors delayed it by only 2 ticks.
  Programs therefore report `delayed` only when the per-tick time budget is exhausted.
- **The fixture spends real time.** The saturation GameTest's competitors spend real time (0.5 ms
  each) rather than only credits.

## Evidence

- JVM: `ProgramLimitsTest` (5 tests) and
  `FairSchedulerTest.reservedMaintenanceSlots…`.
- GameTests: three `ProgramLimitsGameTests`.
  - A saturated queue throttles visibly and recovers. A cable placed during the saturation still
    joins the network. That check failed with the reserved slot removed and passes with it.
  - A time-saturated world delays and logs, with bounded lag.
  - A host-budget trap isolates one program.

## Amendment — rules take turns (2026-10-30, ergonomics plan M145)

A controller's pending commands are now held per rule and taken round-robin
(`RuleQueue`, see [PROGRAMS](../modules/PROGRAMS.md)). Coalescing is unchanged: a rule that runs
again replaces its queued batch. The old single queue moved that batch to the end, so with rules
re-run in source order, the first rule always re-queued ahead of the second. Once commands
drained slower than the interval (a rebuild or busy server slowing storage replies), the second
rule never ran and logged nothing. A full GameTest suite made this frequent: a furnace program's
output rule failed in 12 of 16 runs. Alternatives: keeping a re-run rule's place in one queue
(rejected: a busy rule at the front re-fills the front and still starves the rest), and running
all of a rule's commands before the next rule (rejected: the same starvation). Order within a
rule is kept; the order between rules was never specified.
