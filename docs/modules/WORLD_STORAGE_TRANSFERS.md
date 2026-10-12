# Scheduled world storage transfers

Implemented server API: `WorldNetworks.get(server).transfers()` returns the shared
`WorldStorageTransfers` service. Digital and [registered foreign connector endpoints](CONNECTORS.md) now share
this path. Client packets, terminal/preset frontends and automatic recovery dispatch remain unfinished. Explicit scheduled recovery is implemented below.

## Contract and ownership

Call `submit(id, actor, origin, source, destination, key, amount, completion)` on
the server thread with a unique operation UUID, a server-authenticated actor,
origin dimension/packed block position, current endpoints and exact resource
identity. Frontends must derive origin from the server-validated physical
terminal/deployment context; it is not a client-selected scope override. Item quantities count items, fluids count mB and energy counts FE.
Negative quantities and null arguments fail; requests over 128 items or 1,000
mB/FE fail before queueing. Zero is legal. Split larger operations into bounded
requests through the same authority; these quanta are work bounds, not throughput
promises.

Admission returns `Submission`: an invalid/offline origin has null scheduler
admission and OFFLINE rejection. Queue/cost limits return a scheduler rejection
and TECHNICAL_LIMIT. ACCEPTED/REPLACED means queued, with OK rejection; only the
completion reports a resource outcome. Rejected submissions do not call completion.
Each request costs 16 shared scheduler credits, uses its network and actor UUID
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

## Scheduled principal-owned foreign holds

`claim(id, authenticatedActor, origin, connectorHandle, key, amount, releasing,
completion)` queues a bounded reservation or release. Amount uses items/mB/FE,
is nonnegative and cannot exceed 128 items or 1,000 mB/FE. Ticket IDs, admission,
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
at most 64 fluid tanks, or one FE provider. More than 64 fluid tanks returns
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
automatic presets, player routes and client/SDK query lifecycle remain unfinished.
