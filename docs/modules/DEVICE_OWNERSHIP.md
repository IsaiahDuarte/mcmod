# Device ownership and world persistence

Implemented P2 registry and NeoForge SavedData adapter; storage blocks, portable
item components, topology/permissions and durable transfer/job staging remain
pending. [ADR 0005](../decisions/0005-persistence-ownership.md) owns the design.

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

Schema one: integer `Schema=1`, UUID `World`, compound `Devices` list. Each device
has checksummed `Ledger` bytes, positive long `Generation`, boolean byte `Conflict`,
and optional `Location` (string `Dimension`, long `Position`, integer `Slot`).
[Ledger schema/units](PERSISTENCE.md) remain separate. The committed SNBT fixture
protects the first registry schema; future changes require migration/version
evidence. Known typed/schema/ledger/owner failures retain original tags in a
read-only quarantined SavedData that rejects attempts to mark it dirty. If the upstream loader swallows outer NBT/I/O
failure, the adapter proves file absence before creation; existing/unreadable
files cannot become empty replacements. Unknown schemas are not silently migrated.

Jobs/staging and complete network state are not yet in this authority. Consistent
digital transfers require those owned records in the same save boundary. The
outer NBT reader and I/O error handling remain upstream-owned; inner admission
bounds do not preempt damaged outer files or guarantee recovery from disk failure.
Foreign-inventory crash atomicity remains unpromised.

## Verification

Seven DeviceRegistryTest cases cover move/replay, live-copy quarantine,
foreign/stale handles, offline reload, upgrades/claims, metadata reclamation,
duplicate owners, generation overflow, device/aggregate limits and 3,000 seeded
byte-accounting operations (`0xD031001`). Four DeviceSavedDataTest cases use actual
compressed disk save/reload, a fixed SNBT fixture, quarantined-tag preservation,
and swallowed corrupt-file rejection. These are core/adapter tests, not
block-placement, topology or multiplayer gameplay evidence.
