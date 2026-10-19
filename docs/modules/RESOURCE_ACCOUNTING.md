# Resource accounting and handler contracts

Implemented P1 foundation, integrated with [physical cells/drives](STORAGE_CELLS.md)
and [live wired availability](WORLD_NETWORKS.md). Player transfer endpoints,
crafting and script integration remain pending.
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
it is not a per-tick catalog scan. `persistentState()` additionally captures exact
owner/key claims; [the initial persistence contract](PERSISTENCE.md) documents
bounded serialization and offline restore. [Canonical world ownership](DEVICE_OWNERSHIP.md)
persists ledgers; durable transfer/job staging and full interruption validation remain pending.

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

### Transfer snapshots and interrupted-call recovery

The `start(..., authorized, journal)` overload invokes an owner-thread
`Consumer<StagedTransfer.Snapshot>` before actual extraction, delivery or return,
and after known accounting completes. The seven-argument overload remains an
in-memory operation with a no-op journal; production persistent endpoints must
use the recording overload. A journal failure before extraction stops the call.
Journal failures after a possible mutation conservatively quarantine the move.
The recorder must not mutate handlers or authorize new work from its callback.

`snapshot()` captures owner, exact resource key, both backing IDs, staged,
delivered and returned units, state/reason, a diagnostic bounded to 512 characters,
and pending call/amount. Quantities are nonnegative signed longs in the key's
units; their accounting sum is checked. Pending amounts record intent, not known
delivered stock. A pending extraction may have zero known staging even when the
external source already removed resources.

`restore(snapshot, source, destination, authorized, journal)` requires the exact
saved backing IDs, binds fresh current-authority callbacks and performs no
handler operation. Known STAGED remainders can deliver after authorization or
return as owned cleanup. COMPLETE records do nothing. Any saved pending EXTRACT,
DELIVER or RETURN restores QUARANTINED, even if its last stored state looked
complete; delivery and cancellation then report UNCERTAIN without replay/refund.
Existing quarantined snapshots stay quarantined. Recovery is on the constructing
thread; snapshot objects are immutable and may be handed to serialization.

Snapshots are accounting data, not portable resource ownership tokens. An outer
canonical manager must keep exactly one live instance per record, bound record
counts/bytes, and admit its record before extraction. The [canonical transfer registry](TRANSFER_PERSISTENCE.md) now supplies that
manager and schema-four SavedData serialization. Intent notification is not an
fsync or an atomic transaction with foreign inventories; interrupted world-save
evidence remains required for physical connectors.

The NeoForge adapters accept a caller-supplied backing UUID and selected
capability, with no optional mod dependency:

| Port | Contract and bounds |
| --- | --- |
| `LedgerPort` | Digital ledger, optional reservation owner; all mutations obey ledger availability. |
| `ItemHandlerPort` | Explicit slot window of 1–64 slots on the selected side; scans at most that window and mutates at most one slot per call. Insertion returns accepted units from NeoForge's remainder-stack result. Extraction rejects wrong metadata/quantity. Repeated scheduled calls progress through stock. |
| `FluidHandlerPort` | Whole capability: one resource-sensitive fill/drain call in mB. Explicit selected windows: at most64 genuine indexed tank calls. Both preserve components and validate returned identity/amount; no whole-handler/refund fallback. A handler's internal work is external; performance evidence only covers supported representative handlers. |
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

Four TransferRecoveryTest cases capture interruption snapshots from inside fake
handlers after real fake-stock mutation. They cover extraction/delivery/return
interruption, no replay or manufactured refund, exact known partial recovery,
current authorization, completed-record inertness, wrong-backing rejection,
journal failure before mutation, bounded diagnostics and malformed/overflowed
snapshots. These are portable recovery tests, not world persistence evidence.

`PlatformResourceTest` uses actual NeoForge `ItemStackHandler`, `FluidTank` and
`EnergyStorage`, with real Minecraft registry/component codecs. It verifies
metadata equality/round trips, oversized identity rejection, selected slots,
full/partial capacity and FE rate limits. These tests do not replace world sided
capability, unload/reload or client integration checks in P2/P3/P6.

`ResourceLedger.simulateInsert(key, quantity)` reports the same capacity/catalog/
kind/offline/overflow admission as insertion without mutating stock, claims or
serialized size. `DeviceRegistry.simulateInsert` additionally checks current lease,
routing and global metadata budget without dirtying canonical storage. The
[scheduled world transfer service](WORLD_STORAGE_TRANSFERS.md) uses this advisory
admission and rechecks actual insertion through the canonical ports.


[Physical connector endpoints](CONNECTORS.md) now reacquire sided capabilities
through explicit backing groups and use these same ports through the shared
scheduler/staging service. Opaque provider aliases remain unsupported until an
identity adapter exists; no observed stock is copied into a ledger. Item windows
support any valid signed-int platform index with at most 64 visited slots and one
mutated slot. Negative handler slot counts reject before subtraction can overflow.
A sparse-window test moves exactly three units from the last valid int slot into
a three-unit ledger without scanning/allocating an enormous inventory. External
catalog/reservation/port-lease integration and restart context remain unfinished.

## Foreign claim prerequisite

Implemented portable `ExternalClaims` and `ExternalClaimPort` provide the next
shared-accounting prerequisite. They now persist in canonical world SavedData under schema six; principal-owned scheduled world holds and connector extraction now share them; crafting jobs and player APIs remain unwired. World APIs now expose principal-owned utility holds; aggregate catalogs, automatic selection and crafting job reservations remain unfinished.
DeviceSavedData supplies one canonical registry per world and persists its snapshot
beside owned ledgers/staging. Future jobs must share it and authenticate every operation before use.

`ExternalClaims.reserve(backing, owner, key, requested, observed)` creates an
all-or-nothing additive hold. Backing is the canonical persistent provider-group
UUID; owner is a trusted job/operation UUID, not a client-selected principal.
Key preserves exact resource identity. Requested/observed quantities use items,
mB or FE and must be nonnegative. Zero creates no entry. The caller must validate
fresh total stock for the complete configured resource window; advisory/stale
catalog pages cannot establish availability. SHORTAGE returns zero without a
change. New entries beyond 4,096 claims or 4 MiB conservative metadata return
RESERVATION_LIMIT. Duplicate provider views must share the same backing key.

`available(backing, owner, key, observed)` returns background availability when
owner is null; otherwise it returns only that owner's usable hold. If foreign
stock shrinks below all holds, other owners' quantities stay protected and the
result clamps to zero. Neither observations nor holds are resource ownership;
no contents map, foreign inventory or observed total is retained. `held` and
`reserved` report exact claim metadata. `consume` debits only known actual claimed
extraction and rejects an amount beyond the hold; `release` explicitly returns the
entire unused hold amount, without inserting or refunding resources.

`ExternalClaimPort(delegate, canonicalClaims, ownerOrNull, freshObservation)`
revalidates immediately before each extraction, caps the offered quantity to
usable stock and checks the actual returned amount. Known partial extraction
consumes only actual units; a throw/invalid outcome leaves the hold untouched for
canonical uncertain-operation quarantine. It must be nested inside the journaled
staged transfer and current world authorization/rate/provider guards. It supplies
no retry/refund, standalone fault recovery, permission or scheduler bypass.
Insertion delegates through normal capacity/rate paths and does not release holds.
The fresh observation must be bounded, nonmutating, not load chunks and report
exact total matching the canonical backing/window/key. Negative observations fail.

All access belongs to the registry's creating thread. The registry rejects
reentrant extraction/reserve/consume/release across every wrapper sharing it,
including observer/foreign callbacks, before calling a second handler. Actual
claim debit happens only after a known handler return. Immutable `snapshot()`
contains positive backing/owner/key/amount references only. Restore rejects
duplicates, sum overflow and metadata/count excess, never drops orphan holds or
infers foreign contents. Canonical [schema-six persistence](PERSISTENCE.md#canonical-foreign-claims--world-schema-six)
now preserves these snapshots; scheduled principal holds/provider enforcement and uncertain-release blocking now exist; persisted job reconciliation and full lifecycle evidence remain incomplete.

Example for the future authenticated, scheduled world adapter:

```java
var reservation = claims.reserve(backingId, jobId, exactKey, 32, freshlyObserved);
if (reservation.reason() == OperationResult.Reason.OK) {
  var input = new ExternalClaimPort(validatedProviderPort, claims, jobId,
      key -> boundedFreshTotal(key));
  // Pass input through the canonical journaled staged transfer, not direct UI I/O.
}
```

Six JVM tests cover competing/background holds, falling foreign stock, known
partial/unknown extraction, immutable restore/orphans, duplicate/overflow/count/
byte bounds, cross-wrapper reentrancy and 10,000 generated accounting operations
(replay seed `0xEC1A1`). These tests do not prove live external reservations.


The `(state, changed)` constructor supports the canonical owner’s bounded dirty callback. Restore validates without invoking it. Successful positive reserve and actual consume/release invoke it once; rejected/zero operations do not.


Bounded all-key queries now use a derived sorted key index alongside the existing
hash accounting map. Quantity-only updates retain hash lookup; type creation/last
extraction updates the index. No additional owned quantity or persistence schema
is introduced. ResourceLedger.catalog exposes at most sixteen exact rows/128 KiB
estimated metadata without taking a full snapshot. CatalogCounts incrementally
sums observed source pages, applies a backing/key's full hold once, and uses
BigInteger for global totals/available/reserved/known capacities. These advisory
results authenticate no caller and never replace current transfer preflight.
See the [storage search contract](STORAGE_SEARCH.md) for limits/lifecycle/examples.
