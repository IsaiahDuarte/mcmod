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
