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


## Preset continuity extension — 2026-10-24 (U6)

The pause exists to protect authority. An accepted preset runs under its deployer's
grants, and an upgrade or binding change made by someone else must not silently keep
automation running in the deployer's name under new settings. Nor may it silently
move that automation into the editor's name. That reason holds only when the editor
is not the deployer, so U6 keeps the pause for that case and drops it otherwise:

- When the installing or editing actor is the preset's deployer, the preset is
  redeployed with the same mode, filter and goal through the ordinary `deployPreset`
  checks: VIEW, EDIT, DEPLOY, and WITHDRAW/DEPOSIT for active modes. A binding edit
  redeploys at once. An upgrade rebuilds the network, so its redeploy waits on a
  transient scheduled block tick (every 5 ticks, at most 20 times) until the network
  is VALID, then re-checks grants. A failure leaves the preset paused.
- When anyone else makes the change, the preset pauses as before. `MachinePresets`
  remembers the interrupted mode in memory only; it is not saved and grants nothing.
  The machine screen's Presets page then preloads that mode and shows "Apply to
  resume". Apply is an ordinary deployment in the clicking player's name, so it still
  needs that player's own grants.

The earlier "Deploy" button is now labelled Apply and the separate "Set goal" step is
gone: Apply saves the whole draft, including the typed goal, and deploys it.

## Amendment — Crafter speed (parity E2, PD5 option a, 2026-10-25)

The Crafter accepts the same items through `ThroughputUpgradeable`: Throughput, then
Overdrive, one step each under EDIT. They raise crafting-table crafts per scheduled job run
from 1 to 8 to 32. A run is charged `TASK_COST × batch` (16 × 1 / 8 / 32 credits), and the
batch is fixed when the run is submitted, so a run never makes more crafts than it paid
for. 32 is the most that fits the per-task credit cap (512). The tier is saved in the
Crafter (`FactoryCoreThroughput`; absent means Base), and breaking the Crafter drops the
installed items. Processing patterns are unchanged (one craft per dispatch, one machine
lease); parallel Crafters are E3.

### Parallel Crafters (parity E3)

Measured first: the crafting index kept only the first holder of a duplicated pattern ID, so
a second Crafter holding a copy sat idle and a job never split. Now a crafting-table offer
lists the other loaded holders of the same pattern (same ID and contents, same segment
publication; at most 32). A run's batch is the sum of their batches, capped and paid as
above, and crafts rotate across them, each checked as current and using its own location
for movement permits. Processing patterns still run one dispatch per job (one machine
lease). Different patterns for the same output are still chosen by priority, not split.

