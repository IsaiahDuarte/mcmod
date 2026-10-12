# Buffered Interfaces

P3 prerequisite component: `ItemBuffer` and `BufferedItemHandler` implement the
selected nine-slot owned inventory and its NeoForge automation view. The physical
block, canonical world table/lease, import/export presets, configuration UI and
hopper gameplay path are not implemented yet. A working handler alone is not A01.
See [ownership decision](../decisions/0014-buffered-inventory.md).

## Owned slots and bounded transfer port

`ItemBuffer` owns nine immutable slot descriptions. Each contains exact item
identity/components, count and the admitted maximum stack size (1–64). An empty
slot has null key, zero count and zero limit. Non-item, malformed/overfull slots
and snapshots of any other length reject before restore. The adapter resolves the
current platform stack limit; the inventory additionally caps every slot at 64.
Nonstackable items therefore occupy one item per slot, and no buffer can contain
more than 576 items. This is physical capacity, not a digital type-count limit.

All calls belong to the constructing server/owner thread. `insertSlot(index, key,
offered, simulate)` returns accepted item units, leaving the remainder with the
caller. `extractSlot(index, requested, simulate)` returns an immutable exact item
description. Slot indices must be 0–8; negative quantities and invalid current
stack limits throw. Quantities are exact longs at the transfer boundary; accepted
slot counts fit ints. Null inputs reject. Wrong-kind inserts accept zero.

As a `ResourcePort`, `insert`, `extract` and `simulateInsert` access these same
slots; there is no mirrored digital ledger. Bulk insertion fills matching stacks
before empty slots; extraction uses ascending slot order. Each operation examines
at most nine slots (two bounded passes for insertion) and resolves the current
stack limit once. Simulations do not reserve, mutate slots or dirty data. Positive
actual mutations call the dirty callback once, no-op operations never do. Mutation
from inside a dirty callback rejects; a callback exception after mutation is an
uncertain resource-call outcome, not grounds for replay.

Changing a current platform stack limit cannot grow or silently truncate existing
stock. An old admitted stack remains extractable; further insertion uses the
smaller current/admitted limit. `snapshot()` copies the slot list and retains
immutable keys; restoring it neither dirties data nor validates a physical owner.
The caller must establish one canonical instance per backing ID before binding.
This component snapshot is not yet a disk schema or a physical ownership lease.

```java
var buffer = new ItemBuffer(ItemBuffer.empty(serverOwnedBackingId),
    key -> codec.itemStack(key, 1).getMaxStackSize(), markCanonicalDataDirty);
long accepted = buffer.insertSlot(0, exactKey, 64, false);
// Scheduled transfers use this same buffer as their ResourcePort.
```

## NeoForge automation view

`BufferedItemHandler(buffer, codec, usable)` exposes nine `IItemHandler` slots.
The borrowed predicate must revalidate the loaded canonical physical lease on the
server thread. It cannot be an authorization flag supplied by a client. Invalid
slots/negative extraction throw; empty insertion is a no-op. Reads and returned
remainders are detached Minecraft stacks; modifying them cannot mutate inventory.
Insertion/extraction simulation leaves both caller stacks and buffer unchanged.
Unsupported exact keys exceeding metadata bounds return the original stack as a
remainder without mutation. Stale/unloaded handlers show empty slots, reject
insertion with the full remainder, reject extraction and return false for item
validity. This neither erases owned contents nor loads a chunk.

Local mechanical inventory access must remain separate from a program/player
request to withdraw network stock: the latter still requires shared scheduling,
authentication, permission and accepted segment checks. This adapter is not yet
registered as a block capability or a globally available network storage source.

## Evidence and remaining integration

`ItemBufferTest` covers stack/component identity, nine-slot backpressure,
nonstackables, current-limit changes, no-op/simulation/dirty behavior, immutable
snapshot reload, partial staging cleanup blocked by a full buffer and exact
conservation. A 5,000-operation generated sequence mixes slot/bulk mutations,
simulation and restore, replay seed `0xB0FFE901`.
`BufferedItemHandlerTest` exercises actual Minecraft stacks/NeoForge handler calls,
component-preserving partial admission, detached stack reads, transfer-port/shared
stock, unsupported metadata and stale capability denial/revalidation.
These are portable/platform JVM checks, not hopper/world/restart/client evidence.
