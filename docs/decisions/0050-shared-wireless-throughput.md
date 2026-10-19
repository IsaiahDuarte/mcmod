# ADR 0050 — Shared wireless resource throughput

Status: accepted design; integrated runtime verification in progress.

## Context

R11 and ADR 0006 require one bidirectional budget per physical pair: 256 item
units, 4,000 mB and 8,000 FE per server tick. A scheduler quantum alone cannot
enforce this across terminals, jobs, presets and scripts. Operating power remains
32 FE per endpoint per tick, separate from delivered energy.

## Decision

The server-thread `WirelessBudget` reserves capacity before a mutation and refunds
only a known unused remainder. Every crossed pair receives the same debit; the
tightest remaining hop limits the offer. Both directions, resource identities,
players and services share each resource-kind budget. Simulations are advisory
and do not debit. An uncertain handler call retains its entire admitted offer.
Permits from an earlier tick cannot refund a later tick's usage.

Pair identity is the ordered dimension/endpoint-coordinate pair, retained across
same-tick unload/re-pair to those coordinates. The cache holds at most 32,768
pairs, evicts only entries unused this tick, and refuses further admission with
`TECHNICAL_LIMIT` when all entries are current. Paths contain at most 64 pairs.
This is transient rate accounting, with no persisted resource ownership.

Bounded topology preparation publishes segment-to-pair metadata. Mutation paths
walk the already bounded ancestor chains to their common ancestor; they exclude
that ancestor's upstream link. Invalid/stale topology refuses movement without
loading chunks or scanning network geometry. The route grants no authority:
existing permission, handle, provider and scope checks remain mandatory.

Scheduled transfers place staging at their authenticated origin segment. Source
extraction and destination insertion meter their respective legs. A move through
an origin outside both endpoints may cross a pair twice; those are two actual
staging legs. Known return/progress uses the saved accepted origin. Context-less
legacy settlement only restores already owned staging to its proven original
holder; it cannot invent a historical route and is not new transport admission.

Crafting jobs stage in their request segment. Pull/delivery and processing
insertion/collection use the same permits; published table crafts reserve all
input/output legs before changing staged ingredients. Indivisible crafts wait
for full admission. Terminal fluid actions likewise require the entire container
transaction to fit; FE actions may accept a partial amount. Container capabilities
execute on a disposable item copy before committing owned holders. Known failed
owned commits compensate those exact holders before releasing their permits.

## Alternatives and consequences

Independent service counters permit combined callers to exceed the pair cap.
Metering only the scheduled transfer service misses direct container/crafting
mutations. A second wireless routing scheduler duplicates authority and fairness;
published segment metadata keeps those concerns with the existing topology and
scheduler. Wired-only paths have no wireless cap; existing device limits remain.

Conservative charging after an uncertain provider call may reduce throughput for
the rest of that tick. It cannot duplicate resources or turn an uncertain write
into a presumed rollback. Persistent uncertain ownership still requires the
existing recovery workflow.

## Verification

`WirelessBudgetTest` covers directional aggregation, multihop limits, known partial
refunds, uncertainty, stale permits, bounds and generated conservation sequences
(seed `0x571E1E55`). `WirelessThroughputGameTests` uses real paired endpoints and
scheduled item/fluid/FE transfers, reverse traffic, wired/sibling paths and nested
routes. `TerminalWirelessContainerGameTests` covers indivisible fluid refusal,
partial FE, next-tick resumption and a dishonest energy capability. These are
implemented fixtures; integrated results belong in the implementation plan after
execution. Controlled throughput/performance measurements remain separate.
