# Device ownership and world persistence

Implemented P2 registry, NeoForge SavedData adapter and [physical cells/drives](STORAGE_CELLS.md). Portable topology/current grants and canonical network metadata are implemented; [live wired availability](WORLD_NETWORKS.md) is implemented; canonical transfer staging is implemented; job staging and full world recovery remain pending. [ADR 0005](../decisions/0005-persistence-ownership.md) owns the design.

## Ownership contract

`DeviceRegistry` is confined to its constructing server thread. It owns one
mutable ledger per backing UUID, without exposing mutable ledger references.
`Handle(world, backing, generation)` is a world-scoped lease, not a permission
grant. Adapters must validate current grants/topology immediately before mutation;
this initial registry exposes no client or guest endpoint.

`create(kind, capacity, infinite, catalogLimit, reservationLimit)` creates empty
stock/claims with a new backing UUID and generation one. Capacity uses item units,
mB or FE; only items support infinite. Configurations must fit the ledger codec.
Stock enters through deposits or validated world restoration, never by creating
another device from a copied ledger. UUID collision throws without replacing an
owner or looping. `Location(dimension, packedBlockPosition, slot)` identifies one
holder slot, with a bounded namespaced dimension ID and slot 0 through 63.

`definition(handle)` returns immutable kind, exact capacity, infinite flag and
current location (null for portable), throwing for an invalid lease. Ledger
capacity/infinite accessors check their owner thread. `vacant(location)` tests
holder occupancy in O(1), rejects null, and grants no authority to mutate. The
physical adapter uses these checks before allocation and tier changes. `world()`
returns the namespace UUID and `containsBacking(id)` checks all known owners,
including offline/conflicted devices, in O(1) on the owner thread. These grant
no mutation authority and avoid allocating a full snapshot for UUID checks.

| Operation | Invariant |
| --- | --- |
| `attach(portableHandle, location)` | Increment generation, consume the old handle, occupy slot, remain unavailable pending validation. |
| `attach(currentPlacedHandle, sameLocation)` | Idempotent reload; preserve generation and availability without reactivating offline routing. |
| `attach(currentPlacedHandle, otherLocation)` | Quarantine the owner and stop access; copied references create no stock. |
| `detach(currentPlacedHandle, exactLocation)` | Increment generation, vacate slot, preserve stock/claims, return portable unavailable handle. |
| `availability(handle, exactLocation, active)` | Change transient availability after world/root validation; unload/disconnection passes false. |
| `resize(handle, capacity, infinite)` | Preserve backing/generation/claims and reject unsafe downgrade. |
| `snapshot()` / `restore(state, changed, identifiers)` | Immutable owner records / offline ledgers; duplicate IDs/holder slots and excessive state reject. |

Generation overflow rejects before transition. Foreign, unknown, stale and
conflicted handles cannot mutate stock. Different devices cannot occupy one slot.
Conflicts survive reload and need an explicit administrative recovery operation
in a later slice; no automatic reassignment/refund exists. Typed lease failures:
`UNKNOWN`, `FOREIGN_WORLD`, `STALE`, `CONFLICT`, `OCCUPIED`, `OFFLINE`, `LIMIT`.
Success returns `OK` and the current handle. `stock` throws for invalid leases.

Insert/extract/reserve/release return `OperationResult`; invalid leases deny,
portable/inactive stores report offline. Reservation release may run as owned
cleanup while offline and never changes stock. Amounts remain nonnegative exact
longs. Malformed locations/configurations throw; all core access checks its thread.

`routing(predicate)` installs a trusted, transient owner-thread location predicate.
Stock reads and insert/extract/reserve check it at execution. False yields offline
stock (exact total/reserved retained, available zero) and OFFLINE mutation results.
A true predicate does not reactivate an explicitly offline ledger. Release, resize
and ownership cleanup remain permitted under their existing lease checks. The
predicate must perform bounded, non-reentrant work; exceptions propagate before
stock mutation. Null rejects. Standalone registries default to true for explicit
fake/adapter availability; the world coordinator installs current valid membership.
Predicate changes do not dirty/save state; restored ledgers still begin offline.

```java
registry.routing(location -> currentRouting(location));
// A later disconnection changes currentRouting before a queued operation runs.
var result = registry.extract(placedLease, exactKey, 10, reservationOwner);
// OFFLINE preserves contents and claims; owned reservation release still works.
```

## Admission and persistence

Stock/claim changes and ownership/capacity transitions notify the trusted `changed`
callback, supplied as `SavedData::setDirty`. Availability is transient. Ledger
byte/entry accounting updates in O(1) per mutation; generated tests compare it
with real encoding. Ceilings are 1,024 devices/world, 8 MiB/device, 64 MiB aggregate
ledger envelopes and 65,536 aggregate stock/claim records. Location metadata is
separately bounded by 1,024 holders with 256-character dimension IDs. New entries
preflight full metadata cost before insertion/reservation; removing the last
stock/claim entry releases budget. These are technical limits, not cell tiers or
a measured heap guarantee. Player/admin UI must expose them in a later slice.

`DeviceSavedData.get(server)` resolves the Overworld authority on the server
thread. A server-start listener initializes/validates it. Minecraft's
`DimensionDataStorage` and NeoForge's orderly SavedData path write
`world/data/factorycore_devices.dat`, with no independent chunk copies. Startup
logs unavailable-registry diagnostics and preserves its file; access remains
denied while the world may run.

Current schema four: integer `Schema=4`, UUID `World`, compound `Devices` list,
and required `Networks`, `Nodes` and `Transfers` lists; see [network persistence](NETWORK_PERSISTENCE.md). Each device
has checksummed `Ledger` bytes, positive long `Generation`, boolean byte `Conflict`,
and optional `Location` (string `Dimension`, long `Position`, integer `Slot`).
[Ledger schema/units](PERSISTENCE.md) remain separate. The committed SNBT fixture
protects exact migration from schemas one/two/three into schema four with empty
new tables and a dirty save. Network grants have their own schema-two fixture. Known typed/schema/ledger/owner failures retain original tags in a
read-only quarantined SavedData that rejects attempts to mark it dirty. If the upstream loader swallows outer NBT/I/O
failure, the adapter proves file absence before creation; existing/unreadable
files cannot become empty replacements. Unknown schemas are not silently migrated.

Structural leases/policies now share this authority; see
[physical nodes](STRUCTURAL_NODES.md). [Transfer staging](TRANSFER_PERSISTENCE.md) now shares this authority and save
boundary; job state and world endpoint integration remain pending. The
outer NBT reader and I/O error handling remain upstream-owned; inner admission
bounds do not preempt damaged outer files or guarantee recovery from disk failure.
Foreign-inventory crash atomicity remains unpromised.

## Verification

Eight DeviceRegistryTest cases cover move/replay, live-copy quarantine,
foreign/stale handles, offline reload, execution-time routing/queued denial,
offline claim cleanup, upgrades/claims, metadata reclamation,
duplicate owners, generation overflow, device/aggregate limits and 3,000 seeded
byte-accounting operations (`0xD031001`). Four DeviceSavedDataTest cases use actual
compressed disk save/reload, a fixed SNBT fixture, quarantined-tag preservation,
and swallowed corrupt-file rejection. These are core/adapter tests, not
block-placement, topology or multiplayer gameplay evidence.

## Metadata-only handle pages

`DeviceRegistry.handles(afterOrNull, limit)` returns immutable current-generation
handles in stable UUID order with an exclusive continuation UUID or null at end.
Limit must be 1–16; no stock/claims maps are copied and no dirty change occurs.
`NodeRegistry.handles` follows the same contract for every structural kind.
Both run on their owning thread. Lifecycle changes update the canonical tree
entries; pages are advisory rather than a snapshot across calls. These raw
metadata APIs do not authorize a player/guest or filter private/offline records.
The shared world catalog checks current scope/visibility/physical leases before
publishing rows and binds its server-only cursor to actor/root/segment/exact key.
Do not send raw registry cursors to clients. Persistence now uses schema eight with
unchanged ownership/component contracts; full snapshots are still save operations.
