# Canonical transfer staging and recovery

Implemented P2/P3 prerequisite for A03/A13. `DeviceSavedData` owns one portable
`TransferRegistry` beside canonical ledgers, grants and structural leases. Physical
connectors, terminal/preset scheduling and interrupted world-save tests remain
unfinished. This does not establish universal foreign-inventory crash atomicity.

## Ownership and API

All access belongs to the registry's constructing server thread. Record IDs are
separate stable UUIDs from the transfer's job/principal owner. Multiple records
may share an owner; each ID admits exactly one mutable runtime instance. Public
methods expose immutable snapshots, never a mutable StagedTransfer object.

`start(id, owner, key, source, destination, requested, capacity, authorized)`
preflights the record and metadata budget before extraction. Quantities are exact
nonnegative long item units, mB or FE. The caller supplies real staging capacity,
current endpoint ownership/sides and current shared authority under the scheduler.
The registry itself grants no scope, permissions or throughput. The operation
performs bounded simulation/extraction and one delivery attempt through the
existing transfer contract. Duplicate IDs and quotas throw before handler mutation.

A world may own at most 1,024 records and a conservative 4 MiB record budget,
separate from the 64 MiB ledger-envelope budget. Admission reserves 2,048 bytes
plus three bytes per registry-ID character and component bytes per record,
covering the maximum 512-character diagnostic before any extraction. Later
accounting/uncertain outcomes need no further admission. Completed records still
consume quota until explicitly retired. Constructor admission rejects duplicates,
count and byte overflow; no record is silently dropped.

`get(id)` and `snapshot()` return immutable accounting/evidence. Snapshot order is
stable by record UUID. Restored records have no world capabilities: deliver/cancel
returns OFFLINE until `bind(id, source, destination, authorized)` validates exact
backing IDs and installs one runtime owner. Binding performs no resource calls.
Pending external intents bind quarantined and report UNCERTAIN forever until a
future authorized reconciliation workflow; they never replay or manufacture a
refund. Known staged remainder can deliver after fresh authorization or return
as owned cleanup. `unbind(id)` drops only transient capabilities. `retire(id)`
accepts only COMPLETE records with no pending intent; live staging and uncertain
evidence cannot release admission. Unknown IDs throw IllegalArgumentException;
wrong thread, duplicate live binding and reentrant mutations throw
IllegalStateException. Snapshot reads during journal notifications are allowed.

## Save format and migration

Canonical world schema six requires Schema/World/Devices/Networks/Nodes/Transfers/ExternalClaims.
It adds nested accepted-operation context; component formats are described below.
Schemas one, two and three retain their existing owned data and migrate by adding
an empty transfer table, marking SavedData dirty. Earlier field requirements and
strict unknown-field rejection still apply. Ledger/component and physical lease
schemas are unchanged. Schema six/future or incompatible data is quarantined in
full, with original tags retained and dirty/write replacement prohibited.

Each Transfers entry has local Schema=2, UUID Id/Owner/Source/Destination,
string Kind/Registry, byte-array Components, long Staged/Delivered/Returned,
string State/Reason/Diagnostic/Pending, long PendingAmount, and optional Context.
Schema-one entries are accepted only during legacy world migration. Enum names are
explicit persisted codes; renaming them requires migration. Wrong types, missing
or extra fields, unknown codes, negative/overflowed/inconsistent quantities,
oversized identities/diagnostics and duplicate IDs reject the entire canonical
load while preserving evidence. The committed registry-v4 SNBT fixture includes
an interrupted DELIVER with exact identity, accounting and intent.

Before external mutation the manager records pending call and offered quantity;
after a valid returned amount it records known accounting and clears pending.
All owned ledgers and staging appear in the same server-thread SavedData snapshot.
A save/reload restores owned debit/credit/staging together. A record still pending
on recovery has unknown external outcome and cannot retire, deliver or refund.
The in-memory journal marks SavedData dirty; it does not fsync before foreign
calls or transact with independently saved third-party inventories. Hard crashes
may leave earlier snapshots and unknown foreign outcomes under ADR 0005. Full
world save ordering, actual restart/chunks and injected interruptions remain
mandatory evidence before release.

## Example

After the scheduler validates actor/scope, current handles and real capacity:

```java
var transfers = DeviceSavedData.get(server).transfers();
var id = UUID.randomUUID();
var result = transfers.start(id, jobOwner, exactKey, sourcePort, destinationPort,
    64, 64, currentAuthority);
// Keep this ID while result has staging or uncertainty. Never reconstruct a second owner.
// After reload, reacquire validated endpoints and bind once before scheduled progress.
```

## Evidence

Four TransferRegistryTest cases cover canonical admission, unload/rebind,
partial-remainder conservation, current authority, pending intent quarantine,
record retirement, count/byte/duplicate limits, owner thread and reentrant denial.
Three TransferPersistenceTest cases cover real compressed SavedData disk reload
with canonical source/destination ledgers plus known staging, fixed schema-four
intent evidence, and full-save preservation on malformed/future/duplicate/overflow
records. Existing schema-one/two/three fixtures continue to require exact ledger,
grant and structural data preservation while now checking empty transfer migration.
These are core/disk-adapter checks; the scheduled digital-world path has separate
[world endpoint evidence](WORLD_STORAGE_TRANSFERS.md). Third-party inventory,
client and actual restart/interruption evidence is still pending.


`TransferRegistry.canStart(id, key)` is advisory, side-effect-free owner-thread
admission for a unique ID within the record/byte budgets; `start` repeats these
checks before extracting. `contains(id)` tests canonical record existence and
never exposes a mutable staging owner. World callers report typed execution-time
quota denial before mutation through the scheduled service.


`unbindBacking(backingId)` removes live references for only the indexed matching
records and returns their count. Its owner-thread metadata cleanup is permitted
during a handler call; it changes no staged/delivered/returned quantity and never
removes the saved record. At most 1,024 records/two links per record are indexed;
there is no world/network scan. Initial start/rebind is indexed before foreign
calls so invalidation cannot miss an in-progress owner. Mutators retain a local
owner until the call returns, journal known results and leave the entry unbound.
Quarantined snapshots return UNCERTAIN with or without a live binding. An unbound
legacy pending intent still reports OFFLINE until bind performs its quarantine
transition; the fixed schema-four evidence remains unchanged. The regression
covers invalidation during extraction/delivery, exact known staging, rebind,
unrelated-record isolation and reclaimed index entries.


## Accepted operation context and schema-five migration

`TransferContext(world, network, segment, origin, source, destination)` is immutable
portable metadata, never persisted authority. Origin is dimension + packed block
position; source/destination references contain Type (DIGITAL/EXTERNAL), Device UUID,
Backing UUID, positive lease Generation and Configuration. DIGITAL requires device
== backing and configuration=0; EXTERNAL requires positive configuration generation.
Both backings must differ and exactly match the owned snapshot. Snapshot owner is
the authenticated principal; no operator flag is saved. Context world/network must
exist in the canonical authority when decoding. Device availability, generations,
current scope/grants and policy still require fresh world validation.

The `TransferRegistry.start(..., authorized, context)` overload validates context
before admission/mutation, attaches it to the canonical owner before handler calls,
and preserves it through snapshots, binding and unbinding. `context(id)` returns
it or null for legacy records. Existing start/Stored constructors remain compatible
and explicitly produce null context. Byte admission retains 2,048 fixed bytes plus
three registry-ID bytes/character and component bytes; context costs an additional
1,024 estimated bytes. `canStart` conservatively budgets that context allowance.
Count/byte limits remain 1,024 records / 4 MiB; retirement reclaims the same charge.

Global schemas five and six write transfer entry schema two, optionally containing Context
schema one. Context fields/types are strict; unknown schemas/fields, mismatched
snapshot IDs or unknown world/network quarantine and preserve the entire world
file. Global versions one/two/three still add missing tables. Version four migrates
its entry-schema-one records into schema two without changing a single quantity,
owner, key, endpoint ID, diagnostic or pending intent. No legacy context is guessed.
Versions five/six/seven use entry schema two; schema six adds canonical foreign claims and seven adds routing preferences. Global schema eight/future versions reject.
Old binaries reject the new format, so downgrade without a matching backup is
unsupported. Fixed schema-one/two/three/four fixtures continue to assert exact
owned-data preservation after stripping only the declared format-version changes.

World submissions capture accepted network and segment when queued, deny if either
changes before execution, then save current accepted references in the same staging
owner. A replacement root cannot inherit queued work merely because the actor owns
both networks. The committed transfer-context-v1 SNBT fixture freezes local types;
TransferContextTest verifies strict identity/reference fields, mismatch rejection
before mutation, compressed canonical context/count/grant reload and full malformed/
foreign-context preservation. World tests require attached context and root-change
denial. Automatic recovery is still unfinished: retained context is a prerequisite,
not permission to replay extraction or continue a pending/uncertain call.

Explicit world progress is implemented by [WorldStorageTransfers](WORLD_STORAGE_TRANSFERS.md#explicit-scheduled-recovery).
It rebinds exact saved endpoints only on the server thread and schedules bounded
known-staging delivery or owned cleanup. It never resumes extraction, guesses
legacy context, adopts another principal's operation, or retries uncertain calls.
Automatic dispatch and full world restart/interruption evidence remain pending.
