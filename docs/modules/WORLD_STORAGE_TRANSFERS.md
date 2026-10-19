# Scheduled world storage transfers

Implemented server API: `WorldNetworks.get(server).transfers()` returns the shared
`WorldStorageTransfers` service. Digital cells/banks, explicit [Buffered Interface endpoints](BUFFERED_INTERFACES.md)
and [registered foreign connector endpoints](CONNECTORS.md) now share
this path. The physical [item terminal](STORAGE_TERMINAL.md) now provides bounded
authenticated menu commands; other tabs and general recovery dispatch remain unfinished;
automatic buffer import and original-owner cleanup controls are implemented below. Explicit scheduled recovery is implemented below.

## Contract and ownership

Every live endpoint mutation meters its path to/from the authenticated staging
origin through shared [wireless pair budgets](WIRELESS.md). Known partial calls
refund unused quota; uncertain calls retain their offer for that tick. Pure wired
paths retain existing device limits. Saved validated progress/return uses its
accepted origin; context-less legacy settlement is restoration of known owned
staging, with no guessed route. See [ADR0050](../decisions/0050-shared-wireless-throughput.md).

Call `submit(id, actor, origin, source, destination, key, amount, completion)` on
the server thread with a unique operation UUID, a server-authenticated actor,
origin dimension/packed block position, current endpoints and exact resource
identity. Frontends must derive origin from the server-validated physical
terminal/deployment context; it is not a client-selected scope override. Item quantities count items, fluids count mB and energy counts FE.
Negative quantities and null arguments fail; requests over 1,024 items, 16,000 mB or 32,000 FE fail before queueing.
Physical device tiers still enforce their smaller configured ceilings. Zero is legal. Split larger operations into bounded
requests through the same authority; these quanta are work bounds, not throughput
promises.

Admission returns `Submission`: an invalid/offline origin has null scheduler
admission and OFFLINE rejection. Queue/cost limits return a scheduler rejection
and TECHNICAL_LIMIT. ACCEPTED/REPLACED means queued, with OK rejection; only the
completion reports a resource outcome. Rejected submissions do not call completion.
Each new move costs 16 credits per begun base quantum (128 items or 1,000 mB/FE),
uses its network and actor UUID
for fairness, and occupies a unique `storage-transfer:<id>` key. The service
retains at most 256 requests and a conservative 2 MiB estimated payload budget
(1,536 bytes plus key component bytes and registry-ID characters per request).
Existing scheduler limits apply as well. Completion runs on the server thread;
it must be bounded and nonthrowing. Its exception goes to scheduler diagnostics
and the queue ticket is released in a finally block.

Execution rechecks both permissions (WITHDRAW at source, DEPOSIT at destination),
current origin/target wired memberships, shared visible segments, kind, handle
lease, loaded chunk and physical drive slot/bank identity. It never loads a chunk.
Owner/operator privileges do not expand segment visibility. Revocation,
disconnection or stale/removed cells fail DENIED without extraction or a new
transfer record. A duplicate durable operation ID also fails DENIED. Destination
simulation preserves stock, claims, metadata and bank rate budget; its zero
admission reports the original accounting reason. Cell extraction consumes only
unreserved quantities. Banks use the same identity-based FE rate budgets as every
capability face. There is no separate transfer inventory or energy counter.

Successful admission delegates to the canonical [transfer registry](TRANSFER_PERSISTENCE.md).
The completion includes its immutable snapshot, possibly partial or quarantined;
callers must retain the ID while staged resources or uncertainty exist and retire
only a completed record after consuming its result. The registry's 1,024-record
and 4 MiB budgets still apply. Completion does not imply that the full requested
quantity moved; use delivered/staged/returned counts. Execution-time registry-limit rejection reports TECHNICAL_LIMIT with no transfer
record and no source mutation. Registry admission is checked on the same thread
before start.

`cancelQueued(id, actor)` cancels only that actor's undispatched request and returns
false for missing/other-principal IDs. Call at the tick boundary, outside task
execution. Cancellation produces no resource mutation, durable record or callback.
Use this API to cancel an individual move without dropping unrelated actor work.
Direct scheduler program cancellation also prevents mutation; the next submission
reclaims cancelled tickets through at most 256 keyed queue checks.
A server restart drops queued work (no resources extracted). Durable staged
records remain under schema five. Explicit progress can rebind validated endpoints; automatic retries and real restart/interruption evidence remain unfinished.

## Example

```java
var service = WorldNetworks.get(server).transfers();
var id = UUID.randomUUID();
var admission = service.submit(id, authenticatedActor, controllerPosition,
    sourceCellHandle, destinationCellHandle, exactKey, 64, result -> {
      // Consume actual counts; retain partial/uncertain IDs for recovery.
      if (result.transfer() != null && result.transfer().state() == StagedTransfer.State.COMPLETE)
        DeviceSavedData.get(server).transfers().retire(id);
    });
```

## Verification scope

Core tests cover nonmutating insertion simulation, catalog/capacity/offline and
long-overflow limits, routing/claims/dirty-state preservation and cancellation of
one task without dropping another actor task. Seven headless world scenarios cover
partial unreserved moves, queued permission revocation, disconnected cable,
removed source cell, denied parent access to private child storage, queued
cancellation/admission reclamation and partial physical bank FE transfer. These do
not establish client, multiplayer, real chunk unload/restart, connector or full
A03/A12 acceptance evidence.


## Registered foreign endpoint extension

The existing digital-handle submit overload remains compatible. The endpoint
overload accepts `Digital(DeviceRegistry.Handle)` or `External(WorldConnectors.Handle)`
for either side. Foreign item/fluid identities must round-trip through the current
registry codec before admission; malformed keys return WRONG_RESOURCE without
queueing or faulting a provider. Same-backing moves reject DENIED before creating
a record. Pure rate admission precedes the foreign call; simulation is performed
inside the canonical staged transfer after record admission. Limits, actor/scope,
scheduling, quantities and callback rules above still apply. Foreign provider
policy/lifetime/ownership and alias contracts are in [CONNECTORS.md](CONNECTORS.md).

Backing invalidation unbinds affected canonical live transfers through a bounded
backing-ID index (at most two links per 1,024 records). Unbinding changes no owned
quantities or disk evidence and releases capability references. It may occur
inside a handler: extraction/delivery still records the actual returned quantity,
then leaves the record unbound for validated recovery. Quarantined records keep
UNCERTAIN even after capability unbinding. No global network scan is used for
this cleanup. Provider markers now give persistent backing IDs for supported containers.
Operation endpoint context persists under schema five. Explicit validated progress can rebind or return known staging; automatic recovery dispatch remains unfinished. Legacy transient IDs remain unbound.


Every newly started world move records `TransferContext` with accepted root/segment,
origin position and both canonical endpoint references. Queue tickets capture root
and segment at admission; changing either rejects before record creation/resource
mutation, including replacement roots owned by the same actor. Old scope is never
silently widened during rebuilding. Context is recorded before the first handler
call and remains attached across live-capability invalidation. Schema-five migration
preserves older records with null context. See [context/codec contract](TRANSFER_PERSISTENCE.md).
The context carries identity evidence, not reusable grants; recovery must reauthenticate/revalidate and must never replay an extraction.


## Explicit scheduled recovery

Call `progress(id, authenticatedActor, returning, completion)` on the server
thread. With `returning=false`, deliver only existing staged resources; with
`returning=true`, cancel and return only known staging to its original source.
Neither path extracts resources. Each step offers at most 128 items or 1,000
mB/FE and uses the same queue, credits, fairness and callback lifecycle as submit.
Partial outcomes retain their operation ID for a later explicit step.

Only the recorded principal may progress a record; the operator flag cannot adopt
another principal's resources. Missing/wrong-owner records reject DENIED. Pending
calls or quarantined records reject UNCERTAIN; legacy records without context
reject UNSUPPORTED. These admission rejections produce no callback or provider
call. Origin must be loaded/valid and match the accepted root and segment; an
invalid origin rejects OFFLINE and a changed scope rejects DENIED. Execution
rechecks scope, owner and immutable context, including retirement before dispatch.

Delivery rechecks current WITHDRAW/DEPOSIT grants before foreign endpoint probes,
then endpoint visibility and access. Cleanup may return owned staging after grant
revocation and does not require the old destination to be present. Both paths
require the exact original source identity, generation and resource kind. Digital
leases and foreign connector leases/configuration generations/backing markers
must still match; missing or stale endpoints produce OFFLINE without changing
staging. Recovery never loads chunks or guesses replacement endpoints. Source
access, capacity and shared device/provider rate budgets still bound returns.
A full/read-only source leaves the remainder staged. Uncertain mutations retain
the canonical journal evidence and are never retried or refunded automatically.

Eight headless recovery scenarios cover delivery without re-extraction, revoked
permission plus owned cleanup, stale source, missing destination, another
principal including operator flag, missing legacy context, multi-step item quanta and actual chest NBT reacquisition. These are API
checks, not full server restart, client UI or automatic recovery evidence.


Scheduled advisory foreign stock pages also use this service; see [observation contract](CONNECTORS.md#bounded-advisory-stock-pages). Their counts are never a reservation or transfer promise.

## Legacy settlement

`settleLegacy(id, authenticatedActor, completion)` finishes known staging of a record
saved without endpoint context ([ADR0047](../decisions/0047-legacy-staging-settlement.md)).
It runs on the server thread for the recorded principal only.

- **Target:** `LegacySettlement.of` chooses by canonical identity. A cell, bank or Buffered
  Interface source receives a return; otherwise a cell or bank destination receives a
  delivery; anything else rejects `UNSUPPORTED`.
- **Checks:**
  - Delivery needs current DEPOSIT access to the destination holder; a return needs no
    grant.
  - The holder must be loaded, unconflicted and on a VALID network, or the result is
    `OFFLINE`.
  - Pending or quarantined records reject `UNCERTAIN`; context-scoped or complete records
    reject `UNSUPPORTED`; another principal is `DENIED`.
- **Execution:**
  - Scheduled on the holder's current network.
  - At most one recovery quantum per step; the other end is an inert port, so nothing is
    extracted.
  - Admission conditions, the chosen target, its admitted network/segment and DEPOSIT
    are rechecked at execution; a full target keeps the remainder staged. If the holder
    moved into another network or segment, the old ticket is `DENIED` without mutation;
    an explicit retry schedules against its current scope.

`/factorycore recovery settle <id>` is the player command; it writes the schema-1
before-state audit first.

## Scheduled principal-owned foreign holds

`claim(id, authenticatedActor, origin, connectorHandle, key, amount, releasing,
completion)` queues a bounded reservation or release. Amount uses items/mB/FE,
is nonnegative and cannot exceed 1,024 items, 16,000 mB or 32,000 FE. Ticket IDs, admission,
16 credits, fair actor queues, 256 tickets/2 MiB payload, callback and cancellation
rules match ordinary moves. Endpoint kind/world mismatch or a malformed current
resource identity rejects WRONG_RESOURCE without queueing/probing. Invalid origin
rejects OFFLINE. Execution rejects changed admitted root/segment with DENIED.

With releasing=false, current WITHDRAW/access/visible-segment/lease/config/provider
identity is checked before and after fresh stock reads. Reservation is additive,
all or nothing, and holds stock for `actor.id()` in the canonical saved registry;
no client-specified owner ID is accepted. SHORTAGE and RESERVATION_LIMIT preserve
metadata/resources. Read faults quarantine with HANDLER_FAULT and no hold change.
Fresh totals inspect the complete configured item window (at most 64 positions),
at most64 selected fluid tanks (or64 whole-capability tanks), or one FE provider.
A larger whole fluid capability returns
UNSUPPORTED for reservation/protected extraction, without quarantining a valid
provider. Advisory paginated catalog reads still support larger tank catalogs.
No stock cache, chunk loads or retained foreign contents are created.

With releasing=true, only this principal's hold is reduced by min(amount, held).
Unused metadata may be released after grant revocation or source disappearance;
the currently valid admitted origin/root/segment is still required. No resource
handler is called and no quantity is inserted/refunded. A matching retained
pending/quarantined transfer returns UNCERTAIN and preserves the hold. The check
inspects at most the canonical 1,024 transfer records, including unbound disk
records; no world/network scan occurs. Missing own holds release zero with OK.
An operator cannot release another principal's holds through this API.

`submitClaimed(id, authenticatedActor, origin, externalSource, destination, key,
amount, completion)` uses only that principal's held stock. Ordinary submit and
all connector port extraction consume only unclaimed stock; claims are not ignored
by presets/direct trusted connector users. Execution-time fresh totals prevent
stale observations from spending another owner's hold. A zero usable quantity
reports RESERVED (if holds exist) or SHORTAGE before creating a transfer record.
Known actual extraction alone consumes the hold; partial handlers retain the
unused hold and owned staging exactly. Rate limits and digital/foreign destination
simulation remain shared. Unknown extraction leaves the entire hold plus pending
journal evidence intact and quarantines the provider. No replay/refund or uncertain
release occurs. Cleanup of known staging returns resources through normal source
capacity/rates and does not recreate a consumed claim automatically.

These are principal-owned utility holds, not persisted crafting-job claims or a
player-facing feature. Future job identities require authenticated ownership and
job reconciliation through this same canonical registry; callers cannot supply
arbitrary owners to the public world API. Automatic selection/presets, aggregate
catalogs, UI and actual restart/interruption evidence remain unfinished.

Example (authenticated server thread):

```java
service.claim(UUID.randomUUID(), actor, origin, connector, exactKey, 32, false,
    result -> {
      if (result.amount() == 32) {
        service.submitClaimed(UUID.randomUUID(), actor, origin, connector,
            new WorldStorageTransfers.Digital(destinationCell), exactKey, 32,
            transfer -> consumeActualCounts(transfer));
      }
    });
```

Five world scenarios exercise item competition/background protection/other actor
denial, metadata release after revocation plus denied new reservation, uncertain
extraction/release blocking, and exact mB/FE background plus claimed transfers.
A JVM regression preserves uncertainty lookup after disk restore/unbinding and
matches exact backing/owner/key. These are headless API checks, not complete
A03/A04/A12/A13 acceptance or crafting job recovery.

## Live exact-key storage catalog pages

`catalog(id, authenticatedActor, origin, exactKey, cursorOrNull, completion)` queues
one advisory page through the same authority, fairness, credits, ticket limits and
callback/cancellation lifecycle as moves. Call on the server thread; start with
null cursor and schedule each returned next cursor separately until null. Each
step inspects at most eight canonical digital or structural registry records.
Device/node metadata uses a stable UUID-ordered index, without copying full
ledger contents. Structural pages advance across all kinds; only authorized
connectors are discovered. Discovery checks VIEW and visible segments before
physical/provider probes or marker writes. It never loads a chunk.

A page carries OK and immutable rows, or DENIED with no rows/cursor when current
VIEW or admitted root/segment changes. Invalid origin rejects OFFLINE at admission;
unauthorized admission rejects DENIED without a callback. Server-only cursors
retain principal, root, segment and exact key; mismatched continuations reject
DENIED before queueing, including an owned replacement root. Raw cursors traverse
private registry metadata and must never be exposed as client or guest tokens.
Future frontends need bounded opaque server-owned query state and invalidation.

Rows contain canonical backing, current digital/external endpoint, exact observed
total/reserved/unclaimed available units, configured priority (digital default
zero) and a separate current withdrawable flag. VIEW does not grant WITHDRAW.
Foreign holds may exceed observed stock after outside mutation; availability
clamps to zero without deleting claims. Rows deduplicate backing IDs within the
page; canonical lease/provider overlap checks prevent separately registered aliases.
Rows are ordered by descending priority then stable backing UUID within each page.
This is not global priority distribution, round-robin or automatic selection.
Full ordering requires a bounded collected query and execution-time revalidation.

Only currently authorized loaded physical endpoints appear. This path retains no
offline cache or cross-page snapshot and does not aggregate counts into an
unchecked long. Endpoints can change between pages; a later query restarts at
null. Full-provider stock reads retain the 64-position fluid limitation; unavailable,
faulted, wrong-kind or unauthorized sources produce no numeric row. Connector
faults retain their existing inspectable diagnostics. Total/available values are
advisory, never a reservation or extraction promise. Final publication rechecks
VIEW/root/segment and filters current segment visibility after provider calls.
All later moves use the shared transfer API and fresh authority/accounting checks.

Example, on the authenticated server thread:

```java
service.catalog(UUID.randomUUID(), actor, origin, exactKey, null, page -> {
  if (page.reason() == OperationResult.Reason.OK) {
    page.rows().forEach(row -> System.out.println(row.backing() + ": " + row.total()));
    // Schedule page.next() separately if nonnull; never send the raw cursor to a client.
  }
});
```

Two JVM metadata-page tests cover stable complete bounded enumeration, immutable
handles, no dirty mutation and current generations after movement. World scenarios
cover mixed digital/foreign counts and holds across all pages, connector
reacquisition, other-root exclusion, parent/operator private-child nonenumeration
without provider marker creation, queued VIEW revocation and cursor actor/key/root
binding. [Metadata measurement](../performance/CATALOG_METADATA.md) records one
standalone baseline/page comparison; full world catalog performance remains pending.
All-key searchable catalog, offline listings, global selection/persisted rotation,
remaining export/stock/filter presets, terminal player routes and client/SDK query
lifecycle remain unfinished. Basic buffer Import is implemented below.


## Explicit global storage routes

`transfers().routes().priority(id, actor, origin, fixed, key, amount, direction,
completion)` discovers eligible storage across bounded catalog pages before
selecting descending priority, then stable backing UUID order. `TO_STORAGE`
withdraws from the fixed endpoint; `FROM_STORAGE` deposits into it. Fixed endpoint
and candidate permissions, accepted root/segment, loaded leases and exact identity
are revalidated through the existing shared transfer path. Catalog rows expose
separate `withdrawable` and `depositable` flags; VIEW alone never grants mutation.

Calls and completions belong to the server thread. Quantities use items/mB/FE,
with a maximum quantum of 1,024 items, 16,000 mB or 32,000 FE. Null/negative/oversized requests
fail before queueing; typed admission failures produce no completion. Callbacks
must be bounded and nonthrowing. At most 32 discovery requests share a 2-MiB
estimated metadata budget: 2,048 base bytes plus exact-key bytes/registry ID,
and 384 bytes per candidate. Each request collects at most 5,120 distinct backing
IDs over at most 640 eight-record pages. Every discovery page consumes 16 credits; actual moves pay proportional
credits per begun base quantum; collection does not scan all endpoints in one tick.
These are work/admission bounds, not measured throughput promises.

A request performs one actual move quantum. A preferred source may supply only
part of the requested amount; consume actual snapshot counts before requesting
more. Full/offline/denied/rate-limited candidates may be skipped only when no
resources moved and no staging or pending uncertainty exists. A clean empty
completed attempt is retired before another candidate is tried. Once extraction,
delivery, return, staging or uncertainty exists, the route returns that durable
move and never automatically tries a second backing.

The logical request UUID is ephemeral; `Result.move().id()` identifies the actual
canonical transfer record when a snapshot exists. Retain that actual ID for
recovery. Metadata-only terminal results have no snapshot. `cancel(id, actor)`
cancels only an owned undispatched route outside shared task execution, without a
callback. Restart loses unextracted discovery work; actual staging remains in the
canonical schema-nine registry. Priority mode stores no cursor. Automatic
remaining presets and the player terminal remain unfinished; buffer Import is implemented below.

```java
var routes = WorldNetworks.get(server).transfers().routes();
var admission = routes.priority(UUID.randomUUID(), authenticatedActor,
    controllerPosition, new WorldStorageTransfers.External(importConnector),
    exactKey, 64, WorldStorageRoutes.Direction.TO_STORAGE, result -> {
      var move = result.move();
      // Consume actual delivered counts; retain move.id() for staged recovery.
    });
```


### Persisted round-robin mode

`roundRobin(id, deployment, actor, origin, fixed, key, amount, direction, completion)`
uses the same lifecycle, resource quanta, failure rules and shared scheduler as
`priority`. The caller supplies a stable server-owned deployment/action UUID,
never a client-supplied scope or an identity regenerated for every tick. Candidate
priority is ignored in this explicit mode. Selection chooses the smallest eligible
backing UUID strictly after the stored last UUID, wrapping to the first if needed.
An absent/unloaded last target does not reset rotation or require loading it.
Each candidate is tried at most once per request.

The cursor is scoped to accepted network/segment, principal, deployment UUID,
fixed endpoint backing, resource kind and direction. Different filters under the
same deployment/kind/direction share rotation; no exact resource metadata is
stored. Cursor selection reads current canonical state after discovery, so queued
requests for the same deployment share the newest rotation point. It advances on
selection before move dispatch, including rejected/full/uncertain attempts; this
is distribution preference, not delivered-count evidence. Cancellation before
selection, rejected route admission and empty discovery create no cursor.
A crash may retain an older saved preference; it never authorizes stock replay.

At most 1,024 fixed-size cursor scopes are retained world-wide. Admission preflights
capacity and dispatch rechecks it before extracting; races for the final slot
return TECHNICAL_LIMIT. Existing scopes can continue at capacity. Canonical
`RouteCursors.forget(scope)` is a trusted server deployment-retirement primitive:
frontends must validate lifecycle ownership before using it. Changed cursor/forget
marks SavedData dirty; unchanged/no-op/rejected operations do not. Schemas one
through six migrate with an empty cursor table. Nested schema-one fields and
migration/recovery limits are documented in [persistence](PERSISTENCE.md).

```java
var admission = routes.roundRobin(UUID.randomUUID(), persistedDeploymentId,
    authenticatedActor, controllerPosition,
    new WorldStorageTransfers.External(importConnector), exactKey, 32,
    WorldStorageRoutes.Direction.TO_STORAGE, result -> {
      // Account for result.move().transfer() actual counts, retaining uncertainty.
    });
```


## Explicit buffered endpoints

`new Buffered(nodeHandle, configurationGeneration)` describes a server-selected
physical Buffered Interface. The handle must have BUFFERED_INTERFACE kind and
positive lease/configuration generations. Client IDs and saved reference
principals do not grant authorization: frontends must derive actor/origin/endpoint
from authenticated server-owned interaction or deployment state. Buffers are
explicit sources/targets, never rows in the shared storage catalog.

Buffered requests support exact item identities only; mB/FE reject WRONG_RESOURCE
before queueing. Native key decoding must round-trip before admission. Execution
requires the current loaded chunk/entity, canonical handle/token and matching
configuration, plus current origin/target network, visible segment and operation
WITHDRAW/DEPOSIT grants. Parent/operator privileges do not reveal child-private
buffer stock. Missing/changed/foreign references reject without extraction.
No chunk is loaded by validation. Priority/round-robin routes can use a fixed
buffer endpoint while discovering only ordinary digital/foreign storage targets.

Network operations share the configured input and a separate output allowance
per buffer backing ID/server tick, across all ports/requests. Installed tier caps
are 64/256/576 items for Base/Advanced/Endgame. The rate map holds
at most the 256 canonical buffers; movement/configuration cannot allocate a fresh
allowance for the same ID. Simulations do not spend it; known actual quantities
alone consume credits. These bounds do not cap direct local hopper mechanics or
promise measured throughput. Quanta remain at most 1,024 items per request; use
actual delivered counts when the buffer rate supplies a smaller move.

Destination slot/metadata/rate preflight happens before extraction and canonical
record creation. Full buffers return FULL, metadata expansion failure returns
TECHNICAL_LIMIT, exhausted network allowance returns RATE_LIMIT. Source shortage
and rate exhaustion return typed outcomes without phantom records. The canonical
staging registry still owns partial/uncertain outcomes and its usual limits.

World schema nine supports buffer endpoints with nested context schema two; old
digital/foreign contexts retain schema one and exact fields. Buffered references
persist device=backing UUID, structural generation and configuration generation;
no handler, actor grant or stock copy is stored in context. Type BUFFER requires
item staging. Schemas one through eight migrate without changing existing tables.
Unsupported union/schema/type data preserves the raw authority. Explicit progress
reacquires current buffer identity and native key support, then only delivers or
returns known staging under existing recovery rules, never extracting again.
Removal/reference reload unbinds indexed transfers only for the matching physical
binding owner, preserving quantities/journal evidence and releasing old entity
references. Automatic imports, deployment ownership and player controls remain
unfinished; saved item principals must not become preset authority.

```java
var endpoint = new WorldStorageTransfers.Buffered(buffer.handle(),
    buffer.reference().configuration());
var admission = service.submit(UUID.randomUUID(), authenticatedActor,
    serverValidatedOrigin, endpoint, new WorldStorageTransfers.Digital(cell),
    exactKey, 64, result -> {
      // Consume actual counts and retain any staged/uncertain operation ID.
    });
```


Automatic buffer imports now use guarded priority routes and owned progress.
The server coordinator rechecks the canonical deployer's current DEPLOY grant,
exact local/canonical revision, physical lease and accepted root/segment at catalog
callbacks, dispatch and pre-mutation authorization. Its fresh-start guard separately
rejects unresolved work; that guard does not mistake the operation's own newly
journaled staging for a competing transfer. Existing explicit callers retain their
current authorization contracts. No fresh extraction occurs while indexed
canonical staging/uncertainty is unresolved, including after restore/unbind.

Explicit original-owner buffer returns may rebind the holder's current preset
configuration, preserving exact original world/backing/structural generation and
accepted origin/root/segment. This only inserts existing staging and does not use
a new actor or resource permission to extract/deliver. Pausing/replacing a preset
therefore cannot strand otherwise known same-lease staging. Normal delivery still
requires its accepted configuration/grants. Unknown pending calls, changed leases
and incompatible scopes retain owned data; no blind replay/refund is introduced.


The [all-key discovery and search service](STORAGE_SEARCH.md) now shares existing
query admission, queue/byte bounds and sixteen-credit work rather than creating
a parallel scheduler. One task reads one authorized ledger/provider source page;
raw source cursors stay server-side. Transient registry-text search sessions
aggregate exact BigInteger counts with one reservation contribution per backing/
key, and invalidate on actor/grant/full-topology changes. Source observations are
advisory; player transfers must still use the existing fresh shared route/staging
path. Terminal/menu/payload frontends remain the next prerequisite.


[Authenticated player inventory endpoints](PLAYER_INVENTORY_TRANSFERS.md) now join
the same scheduled ResourcePort/accounting path. They use an exact live PlayerList
body/connection, original principal, same dimension/local range and selected main
slots, with shared 128-item input/output allowances. Local player ownership does
not add a network WITHDRAW requirement to depositing own items; network targets
still require DEPOSIT and withdrawals still require WITHDRAW. Player backings
never enter catalogs/claim registration, and unresolved canonical evidence blocks
fresh operations. Terminal menus/client packet boundaries remain missing.

Actual accepted known/uncertain staging now has a required [two-JVM dedicated recovery record](../evidence/staged-server-v1-2026-10-17.md). The graphical variant remains separately unverified while no display is active; independent-save/interruption/administration gates remain open.
