# ADR 0034 — Keep-stock crafting requests

Status: selected; core/persistence JVM evidence and native headless rules.

## Context

R06: keep-stock crafting counts expected in-flight output, deduplicates by owner/rule/
target and bounds outstanding work; continuous production supports low/high
thresholds and backpressure without repeated manual requests or voiding.

## Decision

`KeepStockRule(id, principal, network, segment, origin, key, low, high)` with
`1 ≤ low ≤ high ≤ 2^20`. A principal has at most one rule per network/segment/key
(setting again replaces it); 16 rules per principal and 64 per world. Rules persist
with jobs in the nested `CraftingJobs` table schema 3 (`Rules`), and each job records
the `Rule` that started it; schemas 1 and 2 still load. Load rejects a rule for an
unknown network.

Every 100 ticks each rule queues one shared-scheduler task under its principal.
Evaluation: if any job started by the rule is in flight, do nothing (the in-flight
output is already counted); otherwise check the principal's current CRAFT authority,
sum available units in the CRAFT storage view, and when below `low` submit an
ordinary job for `high − available` (at most 4,096). The job reserves once and runs
like a manual request; full storage leaves outputs staged (backpressure). Revoked
grants or offline storage record a status (`denied`, `offline`) and start nothing.
Removing a rule leaves its in-flight job to finish.

The terminal Jobs mode toggles a rule for the selected craftable at the previewed
amount (64 if none) with `low = high`; rows show the threshold as `≥N`.

## Alternatives

Counting expected outputs by summing staged/planned units across arbitrary jobs would
need a per-key ledger of promises; one job per rule makes dedupe and in-flight
accounting exact. Polling every tick would waste work for a slow-moving target.

## Consequences

A rule refills at most once per job duration plus up to 100 ticks. Separate low/high
thresholds are supported by the API; the terminal sets equal thresholds. Rule status
is runtime-only.
