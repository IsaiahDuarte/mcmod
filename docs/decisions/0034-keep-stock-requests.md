# ADR 0034 — Keep-stock crafting requests

Status: selected; core/persistence JVM evidence and native headless rules.

## Context

R06: keep-stock crafting counts expected in-flight output, deduplicates by owner/rule/
target and bounds outstanding work; continuous production supports low/high
thresholds and backpressure without repeated manual requests or voiding.

## Decision

`KeepStockRule(id, principal, network, segment, origin, key, low, high)` with
`1 ≤ low ≤ high ≤ 2^20`. A principal has at most one rule per network/segment/key
(setting again replaces it and keeps the existing rule's ID); 16 rules per principal and 64
per world. Rules persist
with jobs in the nested `CraftingJobs` table schema 3 (`Rules`), and each job records
the `Rule` that started it; schemas 1 and 2 still load. Load rejects a rule for an
unknown network.

Every 100 ticks each rule queues one shared-scheduler task under its principal.
Evaluation: if a job is in flight that the rule started, or that has the rule's
principal, network, segment and requested key (the dedup key, whatever started the job),
do nothing (the in-flight output is already counted); otherwise check the principal's current CRAFT authority,
sum available units in the CRAFT storage view, and when below `low` submit an
ordinary job for `high − available` (at most 4,096). The job reserves once and runs
like a manual request; full storage leaves outputs staged (backpressure). Revoked
grants or offline storage record a status (`denied`, `offline`) and start nothing.
Removing a rule leaves its in-flight job to finish; setting the rule again while that job
runs starts nothing until it finishes (M50: a fresh ID used to start a second job and
overshoot, `KeepStockGameTests.keepTurnedOffAndOnMidJobStartsNoSecondJob`).

The terminal Jobs mode toggles a rule for the selected craftable at the previewed
amount (64 if none) with `low = high`; rows show the threshold as `≥N`.

## Alternatives

Counting expected outputs by summing staged/planned units across arbitrary jobs would
need a per-key ledger of promises; one job per rule makes dedupe and in-flight
accounting exact. Polling every tick would waste work for a slow-moving target.

## Consequences

A rule refills at most once per job duration plus up to 100 ticks. Separate low/high
thresholds are supported by the API; the terminal sets equal thresholds. Rule status
and the next due tick are runtime-only, one entry per live rule: removing a rule drops both,
and each tick prunes entries whose rule is gone (M51).

## Amendment — Exporter Craft missing (2026-10-31, ergonomics plan Q8, QD4)

An Exporter's Keep may set **Craft missing** (`MachinePresets.Deployment.craft`, saved as
`Craft` only when set; kept while paused so a resume restores it). Applying it needs the Craft
grant besides the usual ones. When an export pass finds none of the Keep's resource in storage, the
Exporter submits one job for the machine's shortfall (Keep minus what the machine holds), for the
filter's first exact or registry item or fluid, under the deployment authority's grants and at the
Exporter's position. The job carries a persistent link derived from the Exporter's node
(`WorldMachinePresets.craftLink`), so `submitLinked` never starts a second job while one exists,
also after a reload, and keep-stock deduplication (ADR 0034) is unchanged. The job's output goes
to storage like any other, and the Exporter sends it on its next pass. Refusals use the job
status: the Exporter shows `craft_<status>` (for example the principal's job limit) in place of
its storage reason. A tag-only filter shows `craft_unsupported`, since a tag names no single
thing to craft.

Alternative considered: crafting straight into the machine (rejected: it would bypass storage
accounting and the shared delivery path, and the job's output already reaches the Exporter
through storage).
