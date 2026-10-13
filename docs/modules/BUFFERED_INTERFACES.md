# Buffered Interfaces

P3 prerequisite component: `ItemBuffer` and `BufferedItemHandler` implement the
selected nine-slot owned inventory and its NeoForge automation view. The canonical world table/structural lease now exists. The physical block and hopper inventory path are implemented with headless world
evidence. Automatic filtered Import and Paused controls are implemented; export/stock and full settings/inventory screens remain missing;
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
preserves and quarantines the whole authority. A physical block/capability is now registered below; its explicit shared world-transfer endpoint and automatic Import mode are implemented.
Real chunk/world restart, interrupted independent files and client evidence
remain unverified; hopper flow and physical break/drop have headless evidence.


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
complete player terminal/all-key search and remaining presets.


Explicit `WorldStorageTransfers.Buffered(handle, configuration)` item endpoints
now use current root/segment/grants and loaded reference checks. Network input and
output each share 64 item units per backing ID/tick; direct mechanical hopper
access remains real local inventory. ItemBuffer.admitInsert returns typed FULL or
TECHNICAL_LIMIT before consuming input. Buffered context schema two persists
accepted identity/generations under world schema nine, preserving old context-one
fields and owned tables. Unbinding releases indexed transfer references only when
handle/token ownership matches, including conflicted originals awaiting teardown;
stale copies cannot invalidate a replacement holder. Native key incompatibility
rejects recovery before insertion, retaining staging. Background Import is implemented
below; saved reference principals never authorize it.


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
revocation and replacement. Background dispatch and player controls now use this
authority, as described below.


## Active automatic import and player controls

WorldBufferImports is server-owned and registers only currently bound holders,
with 256 maximum runtime entries. Canonical deployments remain the sole authority.
Fresh/legacy references without a deployment stay paused. Positive actual buffer
mutations and configuration changes wake the matching entry; simulations/no-ops
never wake it. Empty inventories leave the active queue. Teardown unregisters only
the same physical owner; old queued closures cannot mutate a replacement.

Tick-boundary work visits at most eight active metadata entries and admits one
coalesced sixteen-credit preparation per buffer into the shared FairScheduler.
Preparation examines at most nine slots and picks exact keys in rotating slot
order. Each key offers at most 64 items to the existing paged priority route,
with one in-flight logical request per buffer. Existing shared queue/page/candidate/
metadata limits and 64-item identity/tick budgets still apply. This upper bound is
not a promised throughput; scheduling and backpressure can reduce it. Blocked
rules retry after at least twenty server ticks and bounded active-queue visits;
no one-thread-per-buffer or unbounded topology/provider scan is introduced.

Each preparation, catalog callback, transfer dispatch and pre-mutation authorization
checks the exact canonical deployment, local revision, structural generation,
accepted root/segment, loaded holder and deployer's current DEPLOY/VIEW/DEPOSIT/
WITHDRAW. Background actors never retain operator privilege. Replacement/pause,
revocation, disconnection, stale local saves and movement reject queued work.
Reconnection under the same accepted identity/scope resumes; movement/scope/revision
changes require explicit reconfiguration. Canonical records never adopt chunk
provenance. Work requests/queues are transient; no second owned save table exists.

TransferRegistry maintains a bounded canonical unresolved-backing index (at most
two references per each of 1,024 records), including restored/unbound/pending-call
records. Any unresolved work for a buffer blocks fresh automatic extraction.
The current deployment can retry known matching source staging through shared
progress, with exact original actor/origin/scope/lease/configuration and no
re-extraction. Unknown/pending intent stays UNCERTAIN. Other principals, prior
revisions or incompatible accepted contexts require explicit recovery. Known
completed callbacks retire records and reclaim admission; indexed lookup neither
loads chunks nor enumerates native inventories.

Normal empty-main-hand use requires VIEW and reports counts/mode/typed status.
Crouch-use with both hands empty toggles Import/Paused through authenticated
configuration. Crouch-use with offhand redstone requests return of the original
actor's known buffered-source staging; the control consumes no material and exposes
no other principal's stock. `returnStaging(actor, completion)` returns scheduler
admission/rejection immediately and, when admitted, later supplies actual snapshot
status. No owned unresolved operation or a different owner rejects DENIED;
uncertainty rejects UNCERTAIN. Full/offline/stale scope retains staging.

Owned return may use the holder's current configuration after pause/replacement:
only insertion of already owned staging into the exact original world/backing/
structural generation is permitted. Accepted operation context is retained rather
than rewritten. Current origin/root/segment and loaded unconflicted holder remain
mandatory. No target operation, source extraction, fresh transfer, actor reassignment
or changed-lease adoption is permitted. It does not require revoked resource
permissions, consistent with the shared owned-cleanup contract. Normal delivery
continues to require the exact saved configuration and current grants. Completed
cleanup retires its known record; partial cleanup remains bounded and retryable.
Pause before cleanup to avoid an enabled rule moving those returned items again.

Twelve BufferImportGameTests cover real component-aware hopper-to-cell movement,
full cell/buffer/hopper retention and resumption, selective queued DEPLOY revocation,
pause/resume, disconnection/reconnection, known staging without re-extraction,
uncertainty/non-replay, old local saves, empty sleep/actual wake, native player
controls, queued removal/movement and owned return after pause/grant revocation.
These are headless live world checks, not actual chunks/server restart, independent
file interruption or client/multiplayer evidence. All-key search, terminal player
operations, remaining presets and inventory screens are the next player-path work.

## Filtered Import and network rate policy

[ADR 0018](../decisions/0018-buffer-filter-policy.md) adds canonical BufferPolicy to
one existing deployment. It contains an item [ResourceFilter](RESOURCE_FILTERS.md)
and a1–64 items/tick network rate, separately for insertion/extraction. Default
ALL/64 remains byte-compatible buffers-v2. Any nondefault policy writes buffers-v3;
each deployment then has Policy `{Schema:1, Rate:int, Mode:string, Terms:list}`.
Terms are strict REGISTRY/TAG `{Type,Id}` or EXACT `{Type,Id,Components:byte-array}`.
Kind is implicitly ITEM. Missing/extra/type-invalid/future fields reject and retain
the full raw canonical world tag. Buffers-v1/v2 supply the explicit default; all
world-ten/physical-reference-one ownership and prior slots remain unchanged.

Policy metadata shares the existing16MiB budget with slots. Nondefaults charge
128+filter.bytes(); default fixed values fit the existing owner overhead. Restore
and replacement preflight both charges; rejection leaves old authority/revision/
dirty state intact. Mode-only edits preserve selection/rate. Movement does not
renew saved deployment authority, and accepted endpoint context remains immutable.

Automatic preparation still visits at most eight entries and scans nine owned
slots. Registry selection accepts component variants without merging their keys;
exact selection preserves all components. Current native tag membership is read
on the owner thread and rechecked in queued route/delivery guards. Tag rebinding
wakes only loaded entries, bounded by256. Empty/no-match rules sleep until activity,
configuration or tag wake; excluded items remain buffered and can cause backpressure.
Priority routing and all existing permission/scope/queue/staging checks remain.

Every network buffer port shares configured per-backing/per-actual-tick input and
output counters, including explicit moves and known return. Configuration changes
never reset spent amounts; lowering the rate clamps remaining allowance to zero.
A partial return preserves owned staging for another tick. Automatic filter changes
cannot adopt/re-extract old staging; uncertainty never replays. Native hopper/
IItemHandler admission remains ordinary physical access and is not filtered/rate-
limited by this network policy. Independently authorized explicit moves do not
inherit automatic selection filters, but do share the network rate.

Basic controls use an empty main hand and authenticated native ServerPlayer:

| Offhand / action | Result |
| --- | --- |
| Item sample / use | Allow that registry type, ignoring components. |
| Item sample / sneak-use | Allow exact components; clock/paper/redstone special controls take precedence. |
| Renamed name tag `minecraft:iron_ingot` or `#minecraft:logs` / use | Allow that ID or current tag. |
| Renamed name tag / sneak-use | Exclude that ID/tag. |
| Clock / sneak-use | Cycle64→32→16→8→4→1→64 items/tick. |
| Paper / sneak-use | Clear to ALL, retaining rate. |
| Empty offhand / sneak-use | Existing Import/Paused toggle, retaining policy. |
| Redstone / sneak-use | Existing original-owner known-staging cleanup. |

Tools/samples are not consumed. Edits retain mode and need current EDIT/DEPLOY;
active Import additionally needs VIEW/WITHDRAW/DEPOSIT. Inspection reports policy
mode/rule count/rate; a full multi-rule editor and richer rule inspection remain
pending. Tag/ID syntax is validated, missing tags match nothing, and malformed
samples leave configuration unchanged. Physical client interaction/tooltip wrapping
has not yet been verified for these new controls.

Evidence: two BufferRegistryTest cases verify shared policy quota, pause/restore/
reclamation and failure without authority/revision/dirty mutation. Two policy-codec
cases freeze policy-v1 SNBT and strict term/type/version bounds, buffers-v2 equality
and actual compressed buffers-v3 round trips. BufferPersistenceTest requires world-
ten compressed policy/owned-slot reload and full invalid policy raw preservation.
Seven BufferPolicyGameTests exercise actual registry/exact imports, rejected stock,
queued replacement/revocation, shared configured rate, real sample/clock/paper/tag
controls, native tag rebinding wake and known partial cleanup after exclusion.
The isolated test item avoids changing vanilla membership. Full restart/independent
files, client/multiplayer/performance and export/stock/settings screens remain open.

The public server-thread overload `configure(actor, mode, policy)` returns the new
immutable deployment after authenticating current EDIT/DEPLOY and active resource
grants. It accepts IMPORT/PAUSED and a validated item BufferPolicy; the mode-only
overload retains the existing policy. Offline/stale holders, denial and shared
metadata exhaustion throw IllegalStateException without new authority; invalid
policy values fail construction, and revision overflow throws ArithmeticException.
No configuration call consumes samples or moves resources. Native callers must
derive actors from authenticated bodies; portable provenance supplies no grants.
