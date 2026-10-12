# Buffered Interfaces

P3 prerequisite component: `ItemBuffer` and `BufferedItemHandler` implement the
selected nine-slot owned inventory and its NeoForge automation view. The canonical world table/structural lease now exists. The physical block and hopper inventory path are implemented with headless world
evidence. Network import/export presets and the inventory screen remain missing;
the hopper-to-storage-to-terminal loop is not yet A01.
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
Canonical persistence and lease validation are described below; a snapshot alone
is not a usable physical owner.

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
authentication, permission and accepted segment checks. The physical block registers this adapter as a capability; it is excluded from
globally available network storage sources.

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


## Canonical inventory ownership — world schema eight

`DeviceSavedData.buffers()` owns one `ItemBuffer` per BUFFERED_INTERFACE structural
lease. Slot quantities live only in the canonical world authority, never in chunk
or portable reference NBT. At most 256 buffers share a 16-MiB estimated metadata
budget (256 bytes per buffer, plus 256 bytes + three registry-ID characters +
component bytes per occupied slot). Both simulations and actual insertions
preflight proposed slot state before mutation. A rejected metadata expansion
returns zero acceptance; cleanup/extraction remains able to reclaim capacity.
Every positive mutation updates accounting and marks canonical data dirty.

`createBuffer(position)` preflights both structural placement/count and inventory
budgets before allocating either owner. It creates no permission network or live
capability. World restore requires exactly one buffer record for every buffer
lease, with no duplicate/unknown/nonbuffer owner. Existing cell/bank/network
identity collision rules still apply. These fixed bounds are technical admission
limits, not gameplay type limits or measured throughput promises.

`bind(handle, position, ownerToken)` belongs to the server thread and requires the
current unconflicted lease at that exact position. A different active owner token
cannot borrow its inventory. `unbind` releases only a matching handle/token;
`usable` revalidates generation/kind/world/position/conflict. All restored records
start unbound, preserving stock while offline. Movement increments structural
generation; old handle/token pairs cannot bind the moved inventory. A copied
placed lease quarantines access without dropping contents. The borrowed mutable
`inventory(handle, token)` is for trusted adapters; each capability/transfer call
must recheck its captured handle/token through `usable`, as the hopper adapter's
guard does. Raw core references do not authenticate a player or script.

Install a bounded native stack-limit resolver before item insertion. Normal
`DeviceSavedData.get(server)` installs the registry-aware codec resolver;
platform-independent tests can supply deterministic limits. Restore validates
owned slot structure without inventing availability or loading native providers.
Unknown native identities stay owned until supported decode/recovery exists.

Schemas one through seven migrate with an empty buffer table; current schema-eight
saves include nested schema one and the BUFFERED_INTERFACE structural kind.
The fixed schema-seven world fixture contains ledgers/grants/nodes/staging/foreign
holds/rotation, and migration removes only the new empty table/version change to
compare exact original data. Missing/wrong-type/future/duplicate/over-limit data
preserves and quarantines the whole authority. A physical block/capability is now registered below; its explicit shared world-transfer endpoint is now implemented; automatic presets
remain missing; real break/drop, chunk/world restart, interrupted
independent files, client and hopper flow remain unverified.


## Physical holder and hopper path

The Buffered Interface block exposes the same nine owned slots on all six faces
through the registered IItemHandler capability. Mechanical access requires the
current loaded block entity, exact lease, owner token and unconflicted state;
retained capabilities reject while removed, detached, unloaded/replaced or copied.
Chunk checks use getChunkNow/CHECK and never load chunks. It joins the wired graph
as a storage-role structural vertex, without being enumerated as shared stock.
Foreign connector registration rejects direct or declared overlaps with owned
buffers, so no second alias can spend or double-count their inventory.

Fresh player placement commissions canonical inventory and records the server
placer principal, configuration generation one, world/ID/lease generation in
local BufferData schema one. These six typed fields are the entire reference;
no item contents are saved in chunk NBT or portable stacks. The principal is
provenance only, not a saved grant/operator override. Upcoming presets must not
authorize work from an item reference principal; they need server-owned deployment
ownership and current grant checks. Local
schema-one fields are strict; malformed/future payloads stay raw and unavailable,
including across break/drop. Chunk loading rejects portable references instead
of silently placing them. Break validates the current holder, unbinds and detaches
its lease, then drops one reference with the new generation. Creative placement
also consumes one reference; copied references cannot allocate replacement stock.

Inspection with an empty hand requires current wired VIEW permission before
reading exact item/occupied-slot counts. Local mechanical input/output does not
withdraw network stock and remains available when disconnected. Starter recipe:
four iron, two glass, two redstone, one hopper; obtaining a hopper unlocks it.
Nine raised top markers, recessed tray, native item/block model and translated
messages are wired; client appearance and interactions are not yet verified.

Seven BufferedInterfaceGameTests exercise actual vanilla hopper insertion and
full-buffer backpressure/resumption, six shared faces, live wired membership,
creative placement/break/movement/stale capabilities, lifecycle reload/copy
quarantine, future raw preservation, declared foreign overlap rejection and exact
recipe/unlock. These do not prove actual chunk/full-server restart, cross-file
interruption, multiplayer or complete player automation. The distinct buffer endpoint now uses shared authorization, network rate accounting
and explicit staging recovery. Next add canonical deployment ownership and
scheduled network imports, then terminal/all-key search.


Explicit `WorldStorageTransfers.Buffered(handle, configuration)` item endpoints
now use current root/segment/grants and loaded reference checks. Network input and
output each share 64 item units per backing ID/tick; direct mechanical hopper
access remains real local inventory. ItemBuffer.admitInsert returns typed FULL or
TECHNICAL_LIMIT before consuming input. Buffered context schema two persists
accepted identity/generations under world schema nine, preserving old context-one
fields and owned tables. Unbinding releases indexed transfer references only when
handle/token ownership matches, including conflicted originals awaiting teardown;
stale copies cannot invalidate a replacement holder. Native key incompatibility
rejects recovery before insertion, retaining staging. No background import or
saved-principal authorization is claimed yet.


## Canonical preset deployment authority

`BufferRegistry.Deployment` records backing UUID, positive configuration revision,
accepted structural lease generation, authenticated principal UUID, accepted root
network/segment and PAUSED or IMPORT mode. At most one record exists per owned
buffer (256 total); records contain fixed-size fields and no item metadata. Their
maximum additional estimated storage is 64 KiB. Canonical slot quantities remain
unchanged. Restored deployments do not bind a holder or authenticate an actor.
An older accepted lease after movement remains stored but cannot authorize a
future automation run; movement requires explicit authorized reconfiguration.

`BufferedInterfaceEntity.configure(actor, mode)` is the trusted server control
path. It validates the loaded holder and wired membership, requires current EDIT
and DEPLOY, and additionally VIEW/DEPOSIT/WITHDRAW to enable IMPORT. It derives
network/segment from current server topology, records the authenticated actor ID
without operator privilege, and increments revision beyond both canonical and
local reference counters. Denied/offline/overflow requests preserve the prior
record/reference. Authorized replacement keeps one record and invalidates old
transfer bindings without erasing owned staging. Local reference principal remains
placement provenance. Calling the lower-level registry method without these
checks is unsupported outside trusted server adapters.

```java
// actor is constructed from the authenticated server player, never packet flags.
var deployment = buffer.configure(actor, BufferRegistry.Mode.IMPORT);
// Pause through the same EDIT/DEPLOY path; no transfer grants are required to pause.
buffer.configure(actor, BufferRegistry.Mode.PAUSED);
```

Nested buffer schema two adds a required Deployments list with strict fixed fields,
duplicate/unknown owner checks, known root/structural segment checks and no future
lease generations. Unknown modes/types/schemas retain the raw quarantined world
authority. Buffer schema one remains byte-for-byte unchanged when no deployment
exists; legacy records have no automation authority. World schema nine owns both
slots and deployments. Rejected schema-two data is never silently converted to
paused empty stock. Independent canonical/chunk saves may leave configuration
revisions mismatched; future execution must require an exact match and offer
explicit reconfiguration rather than adopt a reference's actor/revision.

Registry tests prove replacement, revision overflow, stale owner denial, offline
restore and movement. Fixed buffers-v2 and compressed round-trip tests prove
exact slots/deployment persistence and malformed/future/unowned quarantine. The
world permission case exercises provenance/operator denial, missing import grants,
revocation and replacement. Background import dispatch and player controls are
still missing; configuring IMPORT currently stores intent without moving stock.
