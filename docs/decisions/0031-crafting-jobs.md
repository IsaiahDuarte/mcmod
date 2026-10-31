# ADR 0031 — Persisted crafting jobs with single reservations

Status: selected; core/persistence JVM evidence and native headless jobs. A terminal
request/job UI, processing patterns, published branch jobs and keep-stock requests
are later slices. Extends D03 ([ADR 0005](0005-persistence-ownership.md)).

## Context

R06 requires submission to reserve against current state exactly once, recursive
execution, output attribution that never treats a storage count increase as proof,
cancellation that releases unused reservations and returns recoverable staging, and
explicit persistent states. R09 requires conservation across orderly save/restart.

## Decision

**Ownership.** A job (`core.crafting.CraftingJob`) is the reservation owner in every
digital cell ledger it reserves from and records the remaining reserved units per
backing. Units leave a ledger only by `extract(..., owner = job)` into job-owned
`staged` units; staging returns only by accepted `insert`. Intermediates, outputs and
remainders stay staged until delivery, so a job's own output is attributed by its
staging, not by storage counts.

**Persistence.** `CraftingJobs` lives in the canonical world data as nested
`CraftingJobs` schema1 inside world schema 13 (`DeviceSavedData.SCHEMA`), so a job
and the ledger reservations it owns are written by the same save. Schema 12 migrates
to an empty table and is marked dirty. Load rejects (quarantines the whole world,
retaining the original tag) unknown/duplicate/malformed jobs, an unknown network, or
any recorded reservation that differs from the ledger's reservation for that job.
Bounds: 64 jobs per world, 8 per principal, 256 steps, 256 reservation entries and
64 staged keys per job, 4 MiB encoded. Recovery audits keep `WorldSchema` 12 because
the tables they embed are unchanged.

**Submission.** On the server thread: the requester needs CRAFT at the request
origin; the plan uses the segment's Crafting Unit index and the CRAFT storage view of
digital cells (stock read once per key, ≤128 keys). Only an `OK` plan is admitted;
its `consumed` stock is reserved in stable cell order. Any shortfall rolls back every
reservation made and returns `changed`; nothing is reserved for `missing`, `cycle`,
`limit`, `denied` or `offline`. Preview is the same plan without reservation.

**Execution.** Each server tick, every job not already queued and not in its 20-tick
retry wait submits one shared `FairScheduler` task (network, principal, cost 16). A
task re-checks CRAFT authority at a VALID position in the job's segment (request
origin, offering unit or segment root), finds the step's pattern at a current unit
with the same version, pulls missing inputs from staging then its own reservations,
re-runs the server recipe at the unit and requires the exact encoded output and
remainders, then records one craft. Failures set WAITING with a reason
(`offline`, `denied`, `pattern_missing`, `recipe_changed`) and retry later.

**Delivery and cancellation.** After the last step, or after the requester (or an
operator) cancels, the job releases any remaining reservations and inserts staging
into cells in the requester's CRAFT view; leftovers wait with reason `full`. Consumed
ingredients are not restored and nothing is replayed or refunded. A job retires only
when staging and reservations are empty; a bounded in-memory notice records it, and the requester, if online, gets a "Crafting job finished/cancelled" chat message (verified in the real-client Jobs tour).

## Alternatives

A separate jobs file could desync from ledger reservations after a crash; keeping
both in one SavedData makes them consistent. Returning intermediates to storage
between steps would let other users take them and would make attribution depend on
storage counts. A private per-tick budget would bypass shared fairness; jobs use the
same scheduler as transfers and presets.

## Consequences and limits

Version one uses digital cells only (no connector/external stock or holds), crafting-
table patterns only, and the Crafting Unit's own segment. Steps run crafts
sequentially at one craft per task. The terminal UI to preview, start and cancel
jobs is the next slice; the server API is `WorldCraftingJobs.preview/submit/cancel`.

## Amendment — typed reasons (2026-10-26, ergonomics plan Q54)

Job wait reasons are `core.crafting.JobReason` and request and rule outcomes are
`core.crafting.JobStatus`, instead of about 25 string literals compared with `.equals` (M52).
Each constant's key (`EnumKeys.key`: the name in lower case) is what the save stores in
`Reason`, what packets carry (`WireEnums`, bounded at 32 characters) and the suffix of its lang
key. The keys equal the strings written before, so the nested job schema and existing saves do
not change. A refused storage or movement operation maps through `JobReason.of`, which names
every `OperationResult.Reason`; before, the lower-cased enum name was stored, so `rate_limit`
and nine other reasons, and the `no_power` status, had no lang entry and showed as raw keys.
Loading a job whose reason no code writes now marks the save malformed instead of keeping an
unknown string. Job state and step status travel as `CraftingJob.State` and
`TerminalJobPackets.StepStatus`. `LangKeysTest.everyTypedReasonAndStatusHasItsKey` requires a
lang entry for every value.

## Amendment — craft as many as possible (2026-10-30, ergonomics plan Q7)

A **Max** button previews the largest request the network can satisfy now, bounded by the
4,096-unit request limit and the planner's own limits. `CraftingPlanner.maximum` plans one unit
(none possible: amount 0, and that plan names the shortage), then the cap (reported as `capped`),
then binary-searches between them, keeping the largest amount that plans as OK. That is at most
about 14 plans of at most 4,096 nodes each, against one stock lookup per key per plan, run on the
server thread like a preview and gated by the same command rate. It assumes asking for more is
never easier, which holds for the planner's greedy pattern order; were it not to hold, Max
would show a smaller feasible amount, never an infeasible one, because the returned plan is the
OK plan for that amount. Alternative: computing the maximum from the plan's ratios (rejected:
byproducts, substitutes and shared intermediates make the bottleneck nonlinear).

## Amendment — amount entry (2026-10-30, ergonomics plan Q64)

The amount box parses a bounded expression on the client (`CraftAmount`: 32 characters, `+ - *
/`, 8 levels of parentheses, whole results only, intermediate values over 2^40 refused as too
many), so the server still receives one bounded integer. `=N` sends `TARGET` with N (up to
10^9). The server previews N minus the actor's available stock of the key, the same stock its
plans count (`WorldCraftingJobs.available`), capped at 4,096 (status CAPPED). When nothing is
missing it answers the new status ENOUGH and clears the preview. `QUICK` and `QUICK_TARGET`
preview and start in one command for Shift+Start, spending one terminal action like Start.
The ×1 and ×8 preview buttons became − and + steppers that preview the stepped amount, since
the row has no room for more buttons. Alternatives: computing `=N` on the client (rejected:
the client does not know the actor's available stock across segments), and evaluating
expressions on the server (rejected: nothing would gain from it, and the packet stays one
integer).

## Amendment — plan tree, quiet jobs and Retry (2026-10-30, ergonomics plan Q65)

The preview also carries the plan as a tree (`core.crafting.PlanTree`): depth-first nodes from
the request down through each crafted resource's pattern inputs, scaled by that step's crafts,
at most 64 rows (the plan-row cap) and 12 deep. A resource already open on its own path is
shown once as a loop leaf, and a pattern that changed since planning is not expanded. The
client draws it with connector lines and a ½–1× zoom. Retry is client-only: after a failed
preview, Start re-sends the same preview.

A job can be started quiet (`NOTIFY` in the terminal session, default on each time the terminal
opens). `CraftingJobs` keeps the quiet job IDs, retires a job's flag with the job, and
`WorldCraftingJobs` skips both the toast and the stored notice for a quiet job. They persist in
the nested `CraftingJobs` table as schema 7: an optional `Quiet: true` on that job, written only
when some job is quiet, so worlds without one keep their schema 5 or 6 bytes. Schema 6 tables
and older reject the field, and `Quiet: false` is malformed (the flag is written only when set).
Alternatives: keeping the flag in memory (rejected: a restart would turn a quiet job loud) and a
field on every job snapshot (rejected: it would touch every job constructor for one boolean).
