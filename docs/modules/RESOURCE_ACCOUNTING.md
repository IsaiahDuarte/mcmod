# Resource accounting and handler contracts

Implemented P1 foundation; no block, terminal, crafting or script integration yet.
Selection and limits are owned by [ADR 0002](../decisions/0002-resource-accounting.md).

## Identities and units

`ResourceKey` owns immutable defensive component bytes, a bounded namespaced
registry ID and `ResourceKind`. Items use whole units, fluids whole millibuckets
(1,000 mB/bucket), energy whole FE. Keys have at most 65,536 encoded bytes and
256 registry-ID characters. The platform codec normalizes amount/count to one,
sorts nested compound keys, preserves ordered lists and rejects nesting beyond
64 levels. Item/fluid reconstruction must exactly round-trip the identity;
unregistered, incompatible or empty identities fail explicitly.

Resource identity includes components, not just item/fluid registry names. The
portable core never retains Minecraft objects. Handler stacks are copied when
encoding or constructing offers; stacks returned by inspection are never mutated.

## Ledger API

`ResourceLedger(id, kind, capacity, infinite, catalogLimit, reservationLimit)`
accepts a stable backing UUID, nonnegative long capacity in that kind's units and
positive administrative entry/claim limits. Infinite is supported only for items
and still rejects quantities beyond the device's signed-long representation.
Capacity and technical/catalog/claim limits are distinct rejection reasons.

All mutable ledger access runs on its constructing thread. `stock(key)` returns
total/reserved/available/loaded. Offline stock remains visible but available is
zero and insert/extract/reserve reject. `snapshot()` returns immutable contents
for explicit persistence/planning, copying at most the configured catalog limit;
it is not a per-tick catalog scan. Persistence/migration are not yet implemented.

- `insert(key, requested)` returns actual accepted units and a typed reason for
  any remainder. Wrong resource, full/offline and administrative/technical limits
  never remove contents.
- `reserve(owner, key, requested)` claims all requested available units or none.
  Repeated calls add to the same owner's claim. Competing claims share one ledger.
- `extract(key, requested, null)` uses only unreserved stock. With an owner UUID,
  extraction uses only that owner's claim and reduces the claim by actual units.
- `release(owner, key)` releases that claim without changing physical contents.
- `resize(capacity, infinite)` preserves identity, contents and claims; unsafe
  downgrades reject without change. Fluid/energy cannot become infinite.

Negative quantities, invalid identities or malformed construction parameters
throw `IllegalArgumentException`; thread violations throw `IllegalStateException`.
Expected storage rejection is an `OperationResult`, not an exception.
`StorageTotals.total` accepts an explicitly bounded caller-selected store list,
deduplicates identical backing references and uses `BigInteger` for aggregate
counts. Different store objects claiming one UUID reject as conflicting ownership.
P2 adds authorized view construction and durable owner resolution.

## Transfers and external handlers

`ResourcePort` offers synchronous `simulateInsert`, `extract`, `insert` and stable
`backingId`. All quantities are long units of the exact key. Returned amounts must
be nonnegative and at most offered. Simulation neither reserves space nor proves
future acceptance. Callers must acquire capabilities on the required world thread
and revalidate side, scope, generation and loaded status before mutation (P2/P3).
These P1 ports do not discover world capabilities or grant authority themselves.

`StagedTransfer.start(owner, key, source, destination, requested, stagingCapacity,
authorized)` caps extraction to owned staging capacity and advisory destination
acceptance, validates authority before simulation and again before extraction,
records extracted units as staging, then tries one insertion. `deliver()` retries
only existing staging after fresh authorization. `cancel()` returns staging to
the original backing source as owned cleanup; it never extracts again or refunds
consumed resources. Partial returns remain staged and can retry. Distinct source
and destination IDs are required. An outer scheduler must bound simultaneous
records and supply the space it actually owns; P1 is not a global staging manager.

`owner`, `key`, `staged`, `delivered`, `returned`, `state`, `reason`, `diagnostic`
describe the operation. For `QUARANTINED`, the staged count is the last known
pre-fault amount, **not authoritative available stock**. A handler can mutate and
throw or lie about quantities. Such records return `UNCERTAIN` and cannot retry,
cancel or fabricate a refund; diagnosis/recovery belongs to D03. Supported handler
partial rejection conserves source + destination + trusted staging exactly.
The implementation catches runtime handler failures, not arbitrary JVM failures.

The NeoForge adapters accept a caller-supplied backing UUID and selected
capability, with no optional mod dependency:

| Port | Contract and bounds |
| --- | --- |
| `LedgerPort` | Digital ledger, optional reservation owner; all mutations obey ledger availability. |
| `ItemHandlerPort` | Explicit slot window of 1–64 slots on the selected side; scans at most that window and mutates at most one slot per call. Insertion returns accepted units from NeoForge's remainder-stack result. Extraction rejects wrong metadata/quantity. Repeated scheduled calls progress through stock. |
| `FluidHandlerPort` | One resource-sensitive fill/drain call in mB; preserves fluid components and validates returned identity/amount. A handler's internal tank traversal is external work; performance evidence only covers supported representative handlers. |
| `EnergyHandlerPort` | One receive/extract call in FE with actual returned amount and handler rate limits; no implicit energy consumption. |

Each external amount is capped at `Integer.MAX_VALUE`; item handlers normally
further restrict extraction to the stack's maximum. Ports do not promise rollback
or atomicity across inventories and do not widen sided access. External handler
implementation time cannot be preempted by guest instruction metering.

## Example

This portable example uses a test identity; platform callers obtain a key from
`PlatformResourceCodec.itemKey`/`fluidKey` so it can reconstruct real stacks.

```java
var iron = new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[0]);
var source = new ResourceLedger(UUID.randomUUID(), ResourceKind.ITEM, 1024, false, 256, 64);
var destination = new ResourceLedger(UUID.randomUUID(), ResourceKind.ITEM, 64, false, 256, 64);
source.insert(iron, 100);
var move = StagedTransfer.start(UUID.randomUUID(), iron,
    new LedgerPort(source, null), new LedgerPort(destination, null), 100, 100, () -> true);
// source has 36, destination has 64; move.delivered() is 64.
```

The authorization supplier in production must evaluate current grants and
topology handles; a constant supplier is only suitable for a controlled example.

## Verification

`ResourceAccountingTest` checks signed-long limits, exact aggregate totals,
duplicate backing references, competing claims, upgrades/downgrades, offline
storage, administrative limits, partial acceptance, revocation cleanup and
quarantine without replay. Generated sequences use seeds `0xFAC7001`, `0xFAC7002`,
`0xFAC7003`, each with 10,000 operations and conservation assertions after every
step. Replay messages include seed and step.

`PlatformResourceTest` uses actual NeoForge `ItemStackHandler`, `FluidTank` and
`EnergyStorage`, with real Minecraft registry/component codecs. It verifies
metadata equality/round trips, oversized identity rejection, selected slots,
full/partial capacity and FE rate limits. These tests do not replace world sided
capability, unload/reload or client integration checks in P2/P3/P6.
