# Buffer-mode Interfaces

> Since U11 ([ADR 0059](../decisions/0059-unified-interface.md)) this device is the **Buffer** mode of the single `factorycore:interface` block (`InterfaceBlock`, `kind=buffer`); the old standalone block and item ids are gone. The entity, lease kind, persisted schema and recovery tools described below are unchanged.

P3 prerequisite component: `ItemBuffer` and `BufferedItemHandler` implement the
selected nine-slot owned inventory and its NeoForge automation view. The canonical world table/structural lease now exists. The physical block and hopper inventory path are implemented with headless world
evidence. Automatic filtered Import/Export/Stock and Paused controls are implemented; native settings/inventory screens are implemented with headless authority evidence; actual client interaction remains pending;
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

The Buffer-mode Interface block exposes the same nine owned slots on all six faces
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
output each share the configured rate per backing ID/tick (tier caps 64/256/576); direct mechanical hopper
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
References without a deployment stay paused, except the U6 placement default:
`BufferedInterfaceBlock.setPlacedBy` asks the entity to deploy IMPORT under the
live placing ServerPlayer. A scheduled block tick retries every 5 ticks (at most
20 times) until the network is VALID, then calls the normal `configure` path with
`Actors.of(player)`. Missing grants, a placer who logged out, or an existing
deployment leave it paused. The pending placer is transient and is not saved. Positive actual buffer
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

Empty-main-hand use requires VIEW and opens the settings screen; held items and
sneaking change nothing (U16). The screen's Mode/Apply path configures Import/Paused
through authenticated configuration, and its **Return** action
(`BufferSettingsPackets.RETURN`) requests return of the original actor's known
buffered-source staging, exposing no other principal's stock. `returnStaging(actor, completion)` returns scheduler
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

Paid Throughput/Overdrive installation raises the physical network ceiling to
256/576 items per tick, using the same server-thread configure path. It preserves
the nine owned slots, stable identity, mode/filter/goal and existing staging
ownership. Current Edit/Deploy and active-mode resource grants are revalidated;
failed installation consumes no item. Portable reference schema2 stores the tier;
base references retain schema1 bytes. Canonical slot/policy schemas and world13
are unchanged. Configuration above the installed tier refuses before publishing.
See [ADR 0054](../decisions/0054-throughput-upgrades.md) for lifecycle and fair costs.

[ADR 0018](../decisions/0018-buffer-filter-policy.md) adds canonical BufferPolicy to
one existing deployment. It contains an item [ResourceFilter](RESOURCE_FILTERS.md)
and a 1–576 items/tick absolute network rate, separately for insertion/extraction.
The physical tier caps accepted configuration at 64/256/576 items/tick. Default
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

Controls are on the settings screen (`BufferSettingsPackets`), sent by an
authenticated native ServerPlayer: **Item ID** (registry rule), **Tag**, **Sample**
(exact components, nothing consumed), **Filter** (All / allow / block), **Clear**
(All, retaining rate), **Rate**, **Goal**, **Mode** and **Apply**. U16 removed the
older offhand-item shortcuts. Edits retain mode and need current EDIT/DEPLOY;
active Import additionally needs VIEW/WITHDRAW/DEPOSIT. Tag/ID syntax is
validated, missing tags match nothing, and malformed samples leave configuration
unchanged. `BufferPolicyGameTests` and `BufferImportGameTests` drive these screen
commands.

Evidence: two BufferRegistryTest cases verify shared policy quota, pause/restore/
reclamation and failure without authority/revision/dirty mutation. Two policy-codec
cases freeze policy-v1 SNBT and strict term/type/version bounds, buffers-v2 equality
and actual compressed buffers-v3 round trips. BufferPersistenceTest requires world-
ten compressed policy/owned-slot reload and full invalid policy raw preservation.
Seven BufferPolicyGameTests exercise actual registry/exact imports, rejected stock,
queued replacement/revocation, shared configured rate, real sample/clock/paper/tag
controls, native tag rebinding wake and known partial cleanup after exclusion.
The isolated test item avoids changing vanilla membership. Full restart/independent
files, client/multiplayer/performance and full settings screens remain open.

The public server-thread overload `configure(actor, mode, policy)` returns the new
immutable deployment after authenticating current EDIT/DEPLOY and active resource
grants. It accepts IMPORT/EXPORT/STOCK/PAUSED and a validated item BufferPolicy; the mode-only
overload retains the existing policy. Offline/stale holders, denial and shared
metadata exhaustion throw IllegalStateException without new authority; invalid
policy values fail construction, and revision overflow throws ArithmeticException.
No configuration call consumes samples or moves resources. Native callers must
derive actors from authenticated bodies; portable provenance supplies no grants.

## Automatic Export and Stock

The same deployment now supports EXPORT (storage→buffer) and STOCK (maintain a
best-effort physical goal). Import/Paused policies retain buffers-v2/v3 and
policy-v1 bytes when the goal is the legacy64. Export/Stock or a nondefault goal
requires buffers-v4 with policy-v2 `{Schema:2,Rate,Mode,Terms,Target:int}`. Target
is0–576 individual matching items across the nine slots, independent of exact
component variants selected by broad rules. Kind remains ITEM. Old tables cannot
encode the new modes; table/policy versions must agree. Unknown/future/invalid
unions preserve raw canonical evidence. World schema ten/physical reference one
are unchanged; older goal-less policies supply64 without changing prior fields.

Export discovers one bounded authorized all-key page through the shared scheduler,
then routes one exact selected key through existing global priority withdrawal.
Private sources remain unreadable, buffer inventories remain outside stock
catalogs, and current reservations/capacity/rates/leases/grants are revalidated.
Page-local reserved observations are not subtracted to infer complete backing
availability: exact routing performs the authoritative check. Candidate rotation
and page cursors progress without a whole network snapshot. At most256 loaded
entries retain cursors, sharing a2MiB transient cursor charge and32768-page scan
limit. Each discovery/route is queued; no work loads chunks or scans unboundedly.
Pause/reconfiguration/unregister/tag wake releases stale cursor state, and so does a refused
submission: a cursor names the network scope it was read in, so after a rebuild it is refused at
once, and the next attempt starts from the first page (M145; before, the buffer stayed DENIED). Source
shortage/full/goals retry after20 ticks; local physical activity wakes immediately.
These are bounds, not measured throughput or a representative performance pass.

Stock uses the shared StockTarget deficit model against the current sum of matching
resident slots. Known unresolved inbound blocks fresh extraction and is progressed
under original ownership instead; uncertainty freezes. Matching network insertion
is capped against live resident stock at each native call, including independently
authorized explicit moves, so late arrivals can leave partial owned staging. Native
hopper/IItemHandler input remains physical access and can exceed a best-effort goal;
Stock never voids/removes that excess. Nonmatching explicit network insertion stays
ordinary inventory admission. Original-owner buffer-source returns are exempt from
the goal but still share rate/capacity, so configuring Stock cannot prevent known
cleanup solely because a goal is full. Original export-source cleanup returns known
staging to its saved digital/foreign source, never re-extracts it.

The screen's **Mode** button cycles Paused→Import→Export→Stock→Paused, retaining
selection/rate/goal, and **Goal** sets 0–576. The initial goal is 64. All active
modes require current EDIT/DEPLOY/VIEW/WITHDRAW/DEPOSIT. Rate/filter edits retain
the goal.

Seven BufferExportGameTests add real hopper export, selected/excluded source
conservation, exact Stock stop/replenish, full-target preflight/vacancy wake,
live deficit caps, saved known inbound without re-extracting refill, queued pause/
zero-goal behavior and competing goals with reserved supply. Policy codec and
canonical persistence tests add policy-v2/table-v4 compressed round trips and
strict old/new unions/future raw preservation. Screen-command tests
(`BufferPolicyGameTests`) cover the mode cycle and goal. Actual multi-rule settings/client interactions, external provider/version restart/chunks,
independent saves, multiplayer and representative performance remain required.


## Authenticated settings and inventory menu

Empty-main-hand use opens a two-tab native menu for an actual authenticated nearby
player with VIEW permission, whatever is in the offhand (U16 removed the offhand
shortcuts). Each opening captures an opaque nonce, structural lease and
full segment scope. Closing, moving out of range, losing VIEW or changing that
scope invalidates the menu; passive native initialization never closes the prior
inventory menu.

Settings edits are server-owned drafts: mode, ALL/INCLUDE/EXCLUDE, up to sixteen
registry/tag/exact rules, network rate1–64 items/tick and Stock goal0–576 items.
EDIT and DEPLOY are checked per edit; Apply uses the canonical configure path
and its additional active-mode permissions. Reset discards the draft. External
configuration changes invalidate the draft before publication. Exact sampling
hashes the server's actual own-inventory stack and copies its identity without
consuming it. Invalid input or metadata admission leaves canonical state intact.

The inventory tab projects nine bounded display rows, not virtual container
stacks. Take1/Take64 and own-inventory shift deposits use shared guarded scheduled
BUFFER/PLAYER transfers, current authority and exact server selections. Only one
operation is admitted per menu; unresolved ownership blocks fresh moves. Return
can progress known original-owner staging, never extract again. Close cancels
queued work while preserving owned staging. Ordinary own-inventory cursor moves
remain vanilla. Client commands carry menu/session/revision, bounded fields and
a32-byte identity digest; they carry no root, principal or resource components.
Rules and display names are bounded to128/96 characters. Inventory changes
advance the displayed selection revision. Polling is at most once per five ticks,
commands at most once per two ticks.

Four native settings GameTests cover draft/apply/reset/external invalidation,
nonce rejection/non-consuming samples/stale empty selections and conserved
scheduled withdrawal/deposit and out-of-range apply/closure. Three packet tests cover bounded round trips,
detached digests and malformed/oversized inputs. Compilation/headless evidence
is not graphical, multiplayer or restart evidence; those checks remain open.


### Partial inventory feedback

The menu projection now includes nonnegative exact moved units and a requested
quantum0–64. Completion compares actual delivered units with the accepted menu
request; an otherwise successful short move reports Partial transfer and Moved
actual/requested. A full-target preflight reports0/requested and leaves the source
owned. Configuration edits/reset clear the old operation result. Known cleanup
uses returned units with request0, retaining its separate lifecycle. This advances the buffer payload negotiation version to2, rejecting older
layouts before decoding; it does not change persistence/schema or transfer
semantics. Client and server ship together.

A fifth native settings test reproduces Take64 under rate8 with nine physical
items: player8/buffer1 and explicit8/64, a Stock goal1 rejects another deposit
before extraction (0/1), and Paused deposit returns8/8 for exact nine-unit
conservation. Packet round trips include the added counts. The extended graphical
fixture is compiled but its launch is [blocked before execution](../evidence/buffer-client-attempt-2026-10-13.md).

## Audited canonical conflict repair

Operator `/factorycore recovery buffer list [after]`, `show <backing>` and
`repair <backing> <expected-generation>` support only the loaded original canonical
Buffer-mode Interface with its exact raw/parsed reference. List examines at most16
structural handles and filters buffers; follow a nonempty cursor even after zero
matching rows. Inspection reads only canonical metadata/nine owned slots and no
providers. Show includes original accepted deployer/mode/revision/lease/rate/target.

`BufferRegistry.evidence(id)` returns detached nine-slot state, including conflicts
and unbound owners; unknown IDs throw IllegalArgumentException. The trusted
`repairConflict(expected, retainedPosition)` delegates structural lease repair and
removes the old transient binding on success. Inventory, accepted deployment and
byte charge remain unchanged. The physical `BufferedInterfaceEntity.repairConflict`
checks the actual loaded holder/raw reference, canonical conflict/generation/location
and absence of unresolved transfers, preserves the complete before-state archive,
then installs the new lease and binds its inventory. Both require the owner thread.

The accepted deployment retains its old lease generation; the repaired holder
cannot execute it. Old queued work/cached handlers reject. Its original principal,
scope, policy, revision and mode remain evidence, not authority for the new lease.
A currently authorized player opens Settings and reapplies through normal current
EDIT/DEPLOY/transfer-grant checks to publish a new accepted deployment. The repair
operator does not become the deployer. Local hopper access to the repaired owned
slots retains existing physical ownership behavior, independent of active presets.

World12/reference1/canonical buffer schemas are unchanged. Audit schema3 retains
one structural record, exact owned slots/admitted limits/components, optional full
deployment and native block state/reference NBT in the shared device archive.
[ADR0024](../decisions/0024-buffer-conflict-repair.md) documents fields, bounded
9MiB/file/512-file/32MiB quotas, shared20-tick repair throttle and alternatives.
IO failure changes neither lease, binding, deployment nor inventory. Unsupported,
stale/missing holders, different survivor locations and unresolved transfers remain
unavailable for investigation; no hard-crash or independent-save atomicity is claimed.
