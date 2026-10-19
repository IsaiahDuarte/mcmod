# ADR 0054 — Paid throughput progression under unchanged fair budgets

Status: selected; recovered implementation undergoing completion and verification.

## Context

R11 separates capacity, throughput and connection convenience. Advanced and
Endgame need earned speed upgrades while preserving shared fair budgets,
permissions, exact ownership and finite host work. ADR 0048 left these open.

## Decision

Install a Throughput Upgrade (Advanced), then an Overdrive Upgrade (Endgame),
on a Storage Connector, Machine Interface or Buffered Interface. Links accept
one Overdrive directly; both ends need it for their pair's larger cap. Repeated,
skipped, unauthorized and inapplicable steps consume no item. Successful survival
use consumes one. Connector/machine installation requires current Edit; a buffer
also needs its normal deployment/resource grants when reapplying an active preset;
links use their existing Manage/orphan-owner pairing authority.

| Tier | Port items/mB/FE per tick, each direction | Buffer items per tick | Pair items/mB/FE per tick, both directions combined |
| --- | --- | --- | --- |
| Base | 128 / 1,000 / 1,000 | 64 | 256 / 4,000 / 8,000 |
| Advanced | 256 / 4,000 / 8,000 | 256 | unchanged |
| Endgame | 1,024 / 16,000 / 32,000 | 576 | 1,024 / 16,000 / 32,000 |

Base defaults remain 64 items or 1,000 mB/FE. Installation sets the device's rate
to its tier ceiling; players may lower it afterwards. A Machine Interface advances
its configuration fence and pauses the accepted preset, requiring explicit
authorized reapply. Buffer installation uses the same canonical configure path,
retaining its mode, filter and goal. Contents, stable device/backing identities,
foreign holds and reservations are not recreated or copied. Accepted staging
retains its ownership and existing recovery rules after configuration invalidation.

Recipe `IDI/RHR/IDI` uses four iron, two diamonds, two redstone and a hopper for
one Throughput Upgrade. `NDN/DSD/NDN` uses four netherite, four diamonds and a
Nether star for four Overdrives. Neither recipe consumes populated storage.
Capacity modules remain separate items and progression.

An operation costs 16 scheduler credits per begun base quantum (128 items or
1,000 mB/FE). Maximum requests therefore cost 128/256/512 credits. No scheduler
quota increases. Visible throttling reports deferred network work within 20
scheduler ticks. Only bounded active networks receive defer stamps; idle owners
are not scanned for this feedback. Wireless permits stay keyed by physical pair,
so scale changes never reset same-tick spending; repeated/nested hops use the
smaller scale and shared bidirectional usage.

## Persistence and compatibility

Connector references write schema 3 (machine: 4) only when upgraded; base devices
retain schema 1/2 bytes. Buffer references write schema 2 only when upgraded,
retaining schema 1 for base. Both strictly reject unknown tiers/fields and rates
above the saved installed tier. Canonical world schema 13 is unchanged; buffer
policy encoding accepts the new absolute ceiling, with physical-tier enforcement
at configuration. Portable connector/machine/buffer ownership carries the tier.
Known link upgrades return their paid Overdrive separately on break. Link NBT
adds optional version-one `Throughput` (only ENDGAME is accepted when present).
Malformed/future values preserve the entire native before-state, stop pairing,
power admission and routing, and return exactly one evidence-bearing endpoint on
break. Placement re-reads the strict recovery envelope; unsupported data stays
inert and known data can be read by a compatible build. No upgrade refund is
guessed from an unknown tier. Native break/place tests cover unknown strings,
wrong NBT types and tiers unsupported by links.

Machine view payloads add tier, ceiling and throttle feedback; buffer views allow
the larger legal rate values. Their registration versions advance to 2/3
respectively, so incompatible peers refuse the payload contract. Packet bounds
and real client interactions require verification after the change.

## Alternatives and consequences

Raising global scheduler budgets with paid items would violate R11 fairness.
Flat request costs would make large operations consume less accounted work;
proportional costs preserve the old base cost. Automatically upgrading links
from storage capacity would conflate separate progression axes. Endgame buffers
cap at their nine-slot tray (576), rather than claiming an unbounded batch.

Higher device ceilings do not guarantee achieved throughput on a busy server or
through slower providers/banks. Link caps aggregate all callers. Controlled
throughput/performance and survival pacing remain separate release evidence.

## Verification plan and integration changes

The old packet/stock/policy tests rejected 129 items, 1,001 mB/FE or a buffer rate
65 because those were the previous absolute ceilings. Their replacements exercise
the new maximum and maximum+1. Native tests must retain base-hardware rejection;
this is a requirement change, not removal of malformed/rate coverage. Existing
base persistence fixtures remain byte-identical. New JVM tests cover tier steps,
proportional credits, throttling expiry, scaled wireless spending and strict
reference schemas. Native checks cover actual use/consumption/denial, real
provider caps, movement, recipes, link pair scaling and unknown-data recovery.
Results and remaining limitations belong in IMPLEMENTATION.md after execution.

Native ceiling tests use bounded repeated calls because a platform item handler
may return one stack per call. They wait for a fresh server tick between tier
measurements; upgrading does not reset spent physical-provider rate budgets.
Exact aggregate caps, rate exhaustion and old-handle refusal remain asserted.

The first automatic chest workload exposed equal achieved speed at every tier.
After a successful transfer, presets now submit a continuation through the same
fair queue rather than waiting for the next active-entry sweep. Automatic item
requests use one native maximum stack, preserving per-call ownership/failure
boundaries and proportional accounting; additional stacks require additional
scheduled operations. Rate exhaustion waits one tick, while shortage/fault
backoff remains 20 ticks. No scheduler quota rises and authorization/configuration
is rechecked before each operation. A required isolated native workload compares
all three tiers; whole-ledger discovery can still dominate in large worlds.
