# Canonical network identity and grants

Implemented P2 authority metadata for A12/A13; physical controller leases and gateway policies now have
[structural ownership](STRUCTURAL_NODES.md); world discovery and player permission screens remain pending.
[ADR 0008](../decisions/0008-network-persistence.md) records the save decision.

## Ownership and lifecycle

`DeviceSavedData` owns network grants in the same Overworld SavedData as
[resource backing ownership](DEVICE_OWNERSHIP.md). All network access and
mutation require the constructing server thread. `createNetwork(ownerUUID)`
is trusted commissioning: it allocates a private network UUID and generation
one, marks the save dirty, and creates no stock, physical lease or topology scope.
Network and resource backing UUIDs cannot collide. Allocation collision throws
without overwriting an owner; it does not retry in an unbounded loop.

`network(id)` returns the current portable `NetworkPermissions` owner. Null or
missing IDs reject; missing references never reconstruct ownership. Quarantined
world data denies registry access, lookup and commissioning. Player operations
must use [scope-checked authority](NETWORK_AUTHORITY.md), including permission
edits; this trusted lower-level lookup is not a client endpoint. Current server
operator status is supplied at execution and never persisted or client-selected.

Example inside trusted server code:

```java
var saved = DeviceSavedData.get(server);
var id = saved.createNetwork(player.getUUID());
var owner = new NetworkPermissions.Actor(player.getUUID(), false);
saved.network(id).setGrants(owner, collaboratorUUID,
    Set.of(NetworkPermissions.Permission.VIEW));
// World SavedData saves this metadata. No device becomes active from this call.
```

Grant changes, revocation and explicit ownership transfer preserve the network
UUID and increment its checked permission generation. No-ops do not increment
or mark dirty. Overflow throws before ownership, grants or budget changes.
The new owner has implicit rights, so transfer removes any explicit entry for
that principal; the old owner's implicit rights disappear. This is distinct
from physical block-lease and topology-scope generations.

## Schema and migration

Canonical `world/data/factorycore_devices.dat` now has integer `Schema=3`, UUID
`World`, the existing compound `Devices` list, and required compound `Networks`
list, plus the required [structural Nodes table](STRUCTURAL_NODES.md). The
checksummed ledger binary schema remains one. Each network contains:

| Field | Type and meaning |
| --- | --- |
| `Id` | UUID, allocated network identity. |
| `Owner` | UUID, principal with implicit full rights. |
| `Generation` | Positive long permission generation. |
| `Grants` | Compound list of explicit principal bindings. |
| Grant `Principal` | UUID, unique in that network, different from owner. |
| Grant `Permissions` | Integer nonempty mask with fixed meanings below. |

| Permission | Bit |
| --- | --- |
| VIEW | 1 |
| DEPOSIT | 2 |
| WITHDRAW | 4 |
| CRAFT | 8 |
| EDIT | 16 |
| DEPLOY | 32 |
| MANAGE | 64 |

Bits do not use Java enum ordinals. Unknown bits, zero masks, duplicate networks/
principals, explicit owner grants, malformed UUIDs/types/generations and backing
ID collisions reject. Unknown root, device, location, network or grant fields
also reject rather than disappear on the next save. Rejection preserves the
original tag and prohibits marking it dirty, keeping recovery data read-only.
An upstream swallowed read failure still cannot replace an existing file.

Recognized schema one/two migrates with exact world identity, ledger bytes, claims,
physical lease generations, conflict flags and locations preserved. Schema one adds an
empty network list; both add an empty node list and mark the migration dirty. Previous schema-one builds
had no network records. Unknown schemas do not migrate. Committed schema-one
and schema-two SNBT fixtures exercise both compatibility paths.

## Limits and recovery boundary

Admission permits 1,024 networks/world, 256 explicit principals/network and
65,536 explicit network/principal bindings/world. These are technical ceilings,
not gameplay tiers or measured heap/autosave guarantees. `principalBindings()`
returns the current world count. Creating beyond the network ceiling throws;
grant admission returns `LIMIT` without changing state. Revocation/ownership
transfer reclaim bindings. Updating the permissions of an existing binding
requires no extra space. World admission and accounting use O(1) deltas per
change; canonical saving is bounded by the admitted metadata.

The `NetworkPermissions` budget constructor accepts a side-effect-free admission
predicate and trusted, nonthrowing accounting/dirty callbacks on its owner thread.
Deltas count explicit principal bindings, not bits, and restored bindings must
already be accounted by the enclosing authority. Denial and generation overflow
run no accounting callback. Standalone core constructors retain their per-network
ceiling without inventing a world budget.

Orderly compressed SavedData save/reload is verified. Metadata commissioning is
distinct from the [schema-three structural leases](STRUCTURAL_NODES.md). World
activation must still validate current physical ownership and topology. Durable
staging/jobs, actual world restart/chunk behavior, abrupt save-order recovery,
client/multiplayer checks and full A12/A13 remain pending. Outer NBT allocation,
disk errors and independent chunk/player/foreign-handler writes remain outside
this component's atomicity boundary.
