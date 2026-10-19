# Physical storage connectors

Implemented P2/P3 server primitive: a sided connector registers a foreign item,
fluid or FE provider and participates in the shared scheduled transfer path.
Bounded observations and principal-owned saved foreign holds now share world scheduling. No aggregate catalog, automatic preset, terminal frontend
or restart recovery driver exists yet. Accepted transfer endpoint/origin context
now persists with canonical staging under schema five. Provider
component identity markers now survive supported block-entity save/load. Full
A03/A04/A12/A13/A14 acceptance and client evidence remain incomplete.
[ADR 0013](../decisions/0013-external-connectors.md) owns the identity boundary.

## Placement and player configuration

Craft with four copper ingots, four redstone and one hopper (CRC / RHR / CRC).
Place against a provider so the copper socket touches it; attach cables to the
other five faces. The queried provider side is the face opposite the socket's
facing. Native JSON geometry and collision/selection shapes cover all six
orientations. The connector holds no foreign item, fluid or energy copy.

Use with an empty main hand to inspect type, access, selected provider side,
priority, configured rate and registration state in chat. Chat wraps the complete
inspection rather than putting the long status in the action bar. With both
hands empty, crouch-use cycles Items -> Fluids -> Energy, resetting to that type's
defaults. Normal empty-main-hand use with redstone in the offhand cycles access;
with iron/gold sets positive/negative priority to the stack count. Nothing is
consumed. Item tooltips explain these controls. Every configuration change
requires current EDIT authority at the connector; current gateway branch policy
still applies. Permission revocation rejects later changes.

READ_WRITE allows both directions, READ_ONLY withdraws from the provider, and
WRITE_ONLY deposits into it. Default rate is 64 items or 1,000 mB/FE per tick in
each direction. Advanced item-window/rate settings are currently a trusted server
API; a player settings screen and automatic priority distribution are unfinished.
Priority is retained and displayed; explicit endpoint moves do not select by it.
There is no hidden default import/export rule.

A leased casing retains policy during movement. Its old placed/portable generations
cannot claim again. Creative placement consumes a reference. Invalid/future data
is retained in an inert recovery casing instead of creating another owner.
Fault diagnostics must be preserved for later authorized reconciliation; this
slice provides no reset, speculative refund or replay command.

## Registration and backing identity

`WorldNetworks.get(server).connectors()` is the shared server-thread registry.
`bind(physicalConnector)` returns `Registration(reason, handle)`. A handle contains
canonical structural lease, local configuration generation, provider lifetime
token, persistent group backing UUID and resource kind. OK has a handle. OFFLINE,
DENIED, OVERLAP, UNSUPPORTED and TECHNICAL_LIMIT return none. Missing capabilities
and unsupported provider sides are unavailable. Every backing chunk must already
be loaded; no chunk is loaded by the registry. Capability cache invalidation
releases only the matching physical owner's registration; clones cannot release
the original. Reload/future-data replacement also releases old registrations.

Vanilla item wrappers must refer to the actual container or normalized combined
chest. Double-chest halves and different faces cannot become independent stock.
All kinds of a backing group are independent resources, but same-kind registrations
claim the whole group even if their item windows are disjoint, unless explicit
fixed indexed partitions are declared below. Shared handler
instances reject overlap too. Canonical banks/drives use digital handles, not
foreign aliases. Opaque mod providers need a backing adapter; capability presence
alone never proves independent inventory ownership.

A non-vanilla block entity implements the small platform integration contract
`ExternalBackingProvider.factoryCoreBackingPositions(kind)`. Return a nonempty,
complete immutable list of at most 64 same-dimension physical blocks sharing that
kind's inventory, including this block. Aliases must return the same list. Metadata
must be nonblocking and must not mutate inventories or reenter registration.
The registry copies at most 64 positions, sorts packed positions, rejects duplicates
and checks all chunks before the capability getter. Unknown/incomplete aliases
are unsupported until an integration supplies this evidence; no Mekanism-specific
adapter or chemical support is claimed. It never caches observed foreign stock
as authoritative ledger contents.

Registry memory is bounded by 4,096 bindings (within the shared structural-node
quota), 16,384 claimed physical positions, and per-tick maps of 4,096 provider
and 12,288 connector/kind budgets. Both rate maps reset on a new server tick;
settings changes and provider-group rebindings do not reset used units. Actual
calls reserve offered units before invocation and release only known unaccepted
units. Simulations charge no units. Faults conservatively retain the offered
charge and quarantine the connector; recursive port calls reject before reentry.
These are workload bounds, not representative full-server timing measurements.

## Public server API and transfer example

`ConnectorSettings(kind, access, priority, firstSlot, slotCount, rate)` validates
non-null type/access, priority -1,000..1,000, nonnegative platform item index,
1..64 selected slots and positive rate up to 128 items or 1,000 mB/FE. The item
window means up to `slotCount` slots starting at `firstSlot`, clamped to current
handler size. Energy requires firstSlot=0 and slotCount=64. Fluid0/64 retains whole-handler
semantics; other bounded tank windows require explicit indexed mutation support.
An unavailable item window rejects. Negative handler slot counts cannot overflow
admission. Quantity requests count whole items, mB (1,000/bucket), or FE.

`connector.configure(authenticatedActor, settings)` revalidates the placed lease
and shared EDIT authorization, then increments its positive configuration
generation, invalidates its binding/cache and persists policy. Expected denied
permission is a NetworkAuthority decision. Null/invalid parameters or generation
overflow fail before replacement. `allowed(actor, origin, handle, key, permission)`
checks current grants, wired scope/segment visibility, type and access policy
before resolving foreign capabilities. Operator status never widens storage scope.

```java
var runtime = WorldNetworks.get(server);
var registration = runtime.connectors().bind(connector);
if (registration.reason() == OperationResult.Reason.OK) {
  var id = UUID.randomUUID();
  var admission = runtime.transfers().submit(id, authenticatedActor, rootPosition,
      new WorldStorageTransfers.External(registration.handle()),
      new WorldStorageTransfers.Digital(currentCellHandle), exactItemKey, 64,
      result -> {
        // Use delivered/staged/returned quantities, never the requested amount.
        if (result.transfer() != null && result.transfer().state() == StagedTransfer.State.COMPLETE)
          DeviceSavedData.get(server).transfers().retire(id);
        // Retain incomplete/uncertain IDs for owned recovery; never start another owner.
      });
}
```

The shared service validates exact item/fluid round trips before queueing foreign
requests; malformed identities return WRONG_RESOURCE without touching a provider.
Source=destination is denied before record admission. It rechecks both permissions,
current handles/physical ownership, rate admission and visible segments when queued
work executes. Cell extraction uses unreserved stock; principal-owned foreign holds now protect port
leases are not yet implemented. Rejected requests mutate no resource. Foreign
simulation occurs inside the canonical staged operation after bounded record
admission. Actual extraction/delivery/return intents and actual known quantities
persist under [world transfer schema five](TRANSFER_PERSISTENCE.md). Faults after possible
mutation return UNCERTAIN and retain evidence without retry/refund.

The lower-level `port(handle)` and `limit(handle, insertion, quantity)` are trusted
adapter hooks, not frontend authorization. `limit` is nonmutating rate admission;
`port` revalidates physical generation/type/access and enforces rate/quantity/reentry
bounds. Frontends must use WorldStorageTransfers, which owns authorization,
scheduling and staging. See [shared endpoint API](WORLD_STORAGE_TRANSFERS.md).

## Persistence, recovery and current limits

Canonical structural CONNECTOR leases use existing NodeRegistry/reference fields
and schema-five SavedData string kinds; they create no NetworkPermissions entry.
Local `FactoryCoreConnector` schema one stores Node reference, Kind, Access,
Priority, FirstSlot, SlotCount, Rate, positive Generation and Fault (up to 512
characters). Unknown fields/types/versions preserve the full raw tag. Old
controller/gateway/ledger/transfer fixtures continue to require exact migration.
The committed connector-v1 SNBT fixture freezes local field/type semantics.

Provider lifetime tokens are transient; group backing UUIDs now derive from
persistent provider component markers. Foreign contents remain owned and saved
by their provider. An orderly reload keeps connector leases/policy and
canonical staging, but automatic foreign endpoint rebind/continuation is not yet
implemented. Old runtime handles fail closed. Do not infer saved stock from a
registration, replay unknown calls, fabricate refunds or discard an uncertain
record. Persist source/destination/origin context,
then prove normal chunks/restart and interrupted save behavior before calling
P2/P3 complete. External catalog queries/shared reservations, priority/rotating
selection, advanced settings UI, automatic presets and terminal routes remain
next dependencies. Client, multiplayer and representative mod compatibility/
performance checks are pending; tests below do not substitute for them.

## Evidence

Nineteen ConnectorGameTests cover actual container/handler moves, partial counts
and components, unreserved cell exports, mB/FE, side denial, current permissions,
private branches, overlap/double chests, rate/config/backing-description changes,
rotation/revalidation, movement/stale copies, future-data preservation, opaque
provider rejection and recursive post-mutation faults. ConnectorDataTest covers
strict codecs and limits; NodePersistenceTest adds compressed lease movement/reload;
the sparse adapter regression proves last-int slot behavior and negative-slot
rejection without allocating/scanning a huge inventory. No fixture provider is
packaged with the production mod. Recipes, models and language keys load in the
headless tests; actual client rendering/onboarding remains pending.


Provider marker schema one: `factorycore:external_identity` in the provider's
NeoForge persistent data contains exactly Schema=1, World UUID and Id UUID.
All described parts must be persistent block entities; at most 64 markers are
read/copied by a binding. Validate existing markers before writing missing ones;
mark initialized blocks changed. Kind + world + sorted component IDs derive the
stable group backing UUID. No inventory or quantity is mirrored. Reload/order
changes keep identity; unmarked replacement or changed group/kind do not.
Future/malformed/foreign markers retain their complete bytes and deny registration;
the connector records bounded recovery diagnostics. Concurrent copied-marker
registrations reject overlap. Existing live handles still expire on capability,
configuration or topology changes. Provider rate usage follows persistent backing
IDs; connector usage independently prevents rebinding/group changes from resetting
rates. Old transient transfer IDs are not automatically migrated to guessed markers.

Two ExternalProviderIdentityTest cases cover strict/future/foreign marker handling,
order/kind/world/group identity and copied/oversized member lists. Three additional
world cases cover compressed real-chest NBT identity/content reload, actual
replacement/lifetime invalidation, preserved future markers and copied marker
registration at distinct coordinates. These do not prove full world restart or
operation continuation. Explicit scheduled recovery now validates persisted context, with actual chest NBT reacquisition evidence. External stock/claims, automatic dispatch and player routes remain unfinished.

### Saved endpoint reacquisition

`WorldConnectors.recover(connectorLease, configurationGeneration, backingId)` is a
server-thread identity primitive used by scheduled saved-staging progress. Pass
the accepted canonical connector handle, positive configuration generation and
persistent provider backing UUID. It checks the current lease, physical loaded
connector, configuration and stable provider markers, returning `Registration`
with a current runtime handle only when all identities match. It never loads a
chunk. Unavailable dimensions/portable locations return OFFLINE; invalid leases,
changed configuration, absent physical connectors or mismatched backing return
DENIED. Provider binding retains its normal bounded probing and alias rules.
This primitive grants no resource authority: use the shared transfer recovery API
for authenticated delivery/cleanup. A successful reacquisition must still pass
current access, scope, resource-kind and rate checks before mutation.

### Bounded advisory stock pages

The shared service queues `observe(id, authenticatedActor, origin, handle, cursor,
completion)` on the server thread. Use a unique ticket UUID and nonnegative cursor;
start at zero and request the returned `next` index until -1. Admission and queue
cancellation follow `WorldStorageTransfers`; each page costs 16 shared credits,
occupies one of its 256 tickets and is pinned to the admitted root/segment.
Rejected admission produces no callback. VIEW is checked at admission and again
at execution before provider probing and after reads before publication; visible-segment rules still apply, even to
owners/operators. Scope changes, revoked grants or stale handles return no page.
The frontend must use authenticated, server-validated origin/handles.

`WorldConnectors.observe` is the synchronous server-thread primitive for that
scheduled path. It returns `Observation(reason, page)`; only OK carries a page.
Provider read failures quarantine the connector and report HANDLER_FAULT without
partial rows. Reentrant reads/mutations reject. Rebinding follows existing
identity/alias rules and never loads chunks. Reading does not use insertion or
extraction simulation, change resource counts, consume provider transfer rates,
create a reservation, or write an observed-stock cache.

Each immutable `ExternalStockPage` inspects at most 16 slot/tank positions and
contains at most 16 immutable entries. Item indexes are relative to the configured
window (at most 64 slots); fluid indexes are tank indexes. Empty item/fluid
positions are omitted but still advance the cursor. A cursor beyond a catalog
that shrank returns an empty final page. Entry keys preserve exact components;
stored units are items, mB or FE. Item capacity is null because per-resource
insertion capacity cannot be inferred from slot limits. Nonempty fluid rows
include observed tank capacity; energy exposes its canonical key plus reported
stored/capacity, including zero. Invalid negative/contradictory values fault.
Empty tank capacity is not advertised as capacity for an arbitrary fluid.

The page is an advisory observation, not a snapshot across pages or an extraction
promise. Foreign pipes may change contents immediately; do not reserve from stale
pages or sum pages from different backing identities. Higher-level shared claims,
unique-provider aggregation, sorting/selection, offline cached listings and
terminal pagination remain unfinished. A page's maximum exact-key payload is
16 times the existing 65,536-byte component and 256-character registry-ID bounds;
results exist only during the bounded completion callback and are not retained by
this service. Representative catalog performance remains unmeasured.

Example (server thread):

```java
service.observe(UUID.randomUUID(), authenticatedActor, controllerPosition,
    registeredConnectorHandle, 0, observation -> {
      if (observation.reason() == OperationResult.Reason.OK) {
        var entries = observation.page().entries();
        // Consume advisory counts; schedule the next page separately if next >= 0.
      }
    });
```


### Shared hold enforcement

All connector extraction now respects canonical foreign holds. Background ports
can spend only freshly validated unclaimed stock when holds exist; `claimedPort`
is a trusted server primitive for the scheduled principal-owned route. `stock`
reads a bounded complete configured total and retains nothing. Neither primitive
authenticates a frontend; use [scheduled hold/move operations](WORLD_STORAGE_TRANSFERS.md#scheduled-principal-owned-foreign-holds)
with server-derived actor/origin/handles. More than 64 fluid tanks is unsupported
for holds/protected extraction; ordinary unheld transfers retain their bounded
single drain call and advisory catalog pagination remains available. If a held
provider expands past this bound, holds remain and extraction pauses. Rates,
access, configuration, stable backing and failure quarantine remain shared.


## Audited conflict repair

Operator `/factorycore recovery port list [after]`, `show <id>` and
`repair <id> <expected-generation>` select connectors/Machine Interfaces only. Pages
examine at most16 structural records with an exclusive cursor. Show reads canonical
metadata and machine deployment/fence only; it calls no provider and loads no chunk.

The trusted server-thread `ConnectorEntity.repairConflict(expected)` requires the
loaded original holder with exact raw/parsed lease, kind, generation and position,
an empty persisted fault and no unresolved record naming the node as backing or
device. A Machine Interface also requires a positive canonical fence equal to its
physical configuration. `WorldConnectors.recoveryRegistration(owner)` returns the
remembered registration only when connector, configuration, provider token, kind
and face still match; it is metadata and never authorizes resource access. With it,
unresolved records naming its backing refuse; without it,
`TransferRegistry.hasUnscopedUncertain(DeviceSavedData::canonicalIdentity)` (at most1024
records, read-only) refuses any uncertain legacy record lacking context that names a
backing that is not a Factory Core identity, since that backing cannot be proven unrelated.
Known legacy staging no longer blocks; its owner settles it into canonical holders
([ADR0047](../decisions/0047-legacy-staging-settlement.md)).

`DeviceRecoveryAudit.preservePort(worldPath, namespace, owner, deploymentOrNull,
fence, registrationOrNull, blockState, blockEntity)` writes schema5 before mutation;
IllegalArgumentException means mismatched evidence and IOException means failed/
oversized/quota/corrupt output, both with no state change. Success rotates only
the structural lease, invalidates old registrations/ports/cache/topology and
issues a new provider token, preserving settings, configuration, label and face.
Normal revalidation reacquires the same provider backing. Accepted machine presets
and fences are unchanged and cannot run on the new lease until an authorized
player explicitly redeploys. See [ADR0026](../decisions/0026-foreign-port-repair.md).


## Independent-save adoption

Operator `/factorycore recovery port adopt <id> <expected-generation> <x y z>`
selects a loaded stale connector or Machine Interface in the source dimension.
The canonical lease must be placed at a different loaded position with no holder
referencing that ID. The target must preserve its world, ID and casing kind with a
different generation and no persisted provider fault. Unresolved node operations
or unscoped uncertain foreign calls refuse adoption; known legacy staging retains
its existing settlement path. No chunk is loaded and no foreign capability is read.

`ConnectorEntity.adoptPort(expected)` runs only on the server thread. It writes
schema-7 `FOREIGN_PORT_ADOPTION` evidence before moving the single canonical lease
and advancing its generation. Evidence contains both the canonical metadata and
stale physical holder, including settings, label and face. IOException or invalid
preconditions leave ownership unchanged. Machine configuration advances beyond
both physical and canonical fences; the accepted deployment is paused and requires
explicit redeployment with current grants. Caches and old handles are invalidated;
foreign inventories, claims, rates and operation ownership are unchanged.
See [ADR0046](../decisions/0046-independent-save-adoption.md).

Portable removal and native holder-state replacement unregister connector and machine
runtime ownership before changing the parsed lease. This prevents a detached or replaced
holder from retaining a preset registration that conflicts with the current placement.


## Selected tanks and fixed provider partitions

[ADR0053](../decisions/0053-selected-tanks-and-fixed-machine-ports.md) defines the
indexed integration boundary. `IndexedFluidHandler` extends `IFluidHandler` with
`fillTank(index, exactStack, action)` returning accepted mB and
`drainTank(index, exactStack, action)` returning the actual exact-component fluid.
They may touch only the named tank, and SIMULATE must not mutate. Indexes match
`getFluidInTank`. The adapter visits at most64 selected tanks, checks quantities
and returned identities, and propagates handler faults into existing uncertain
staging/quarantine. No whole-handler/refund fallback is allowed.

For example, after current EDIT authorization,
`machine.configure(actor, new ConnectorSettings(ResourceKind.FLUID,
ConnectorSettings.Access.READ_WRITE, 0, 1, 1, 500))` selects tank1 at500mB/tick.
Bind/discover and submit through `WorldStorageTransfers` as in the existing
compiled examples; observe/Stock/holds use only that tank. Defaults0/64 retain
whole-capability compatibility. Unsupported indexed mutation reports UNSUPPORTED.

Providers may additionally override `factoryCorePorts(kind)` to return immutable
`PortWindow(first,count)` values, at most64 nonoverlapping fixed partitions, each
with nonnegative global first index/count1–64. Empty keeps legacy exclusive
backing accounting. Every alias/face must declare the same permanent catalog and
expose identical global indexes, including through sided wrappers. Nonempty
catalogs admit only an exact declared window. Do not return partitions for FE.

For a provider with separate global input/output item slots, return
`List.of(new PortWindow(0,1), new PortWindow(1,1))`; two interfaces configured0/1
and1/1 may bind concurrently. Their stable derived backing IDs keep foreign
holds/staging distinct. Duplicate/overlapping/undeclared windows refuse; concurrent
partitions share the existing physical-group128 item or1000mB per-direction tick
ceiling plus each connector's configured rate. Different resource kinds keep
independent budgets. Closing/reconfiguring one binding removes only its own
claims, never another port registration or resource hold.

The fixed catalog must never change for an existing provider backing identity.
Integration authors must explicitly migrate identity/pending ownership when
changing physical partitions; changing ranges silently can bypass saved holds.
Factory Core never infers this guarantee from arbitrary NeoForge wrappers.
Provider contents remain foreign-owned; local connector/machine and world
schemas are unchanged. Existing adapters default to exclusive behavior.

`IndexedMachinePortsGameTests` exercises real sided item/fluid/FE capabilities,
selected same-fluid isolation, shared rates, rebind with retained holds and
duplicate/union rejection. `SelectedFluidPortTest` adds exact components, partial
insertion, nonmutating simulation, bounds and malformed/unsupported adapter cases.
Run evidence belongs in the implementation plan; third-party compatibility and
full processing-job/script lease interoperability are not established by this slice.


`processingAdmission(targetPosition)` is a trusted server-thread metadata query
used only for processing acquisition. It reads already loaded component markers
and fixed partition declarations for item/fluid/energy kinds, checks canonical
foreign holds and unresolved transfer indexes, and returns BUSY for an existing processing lease on any backing member,
RESERVED for foreign ownership or unavailable/invalid identity metadata, and OK
when neither retains the provider. It never initializes markers, resolves capabilities,
reads resource quantities or loads chunks. A non-OK result blocks dispatch before
any ingredient is pulled. The ordinary permission/segment checks still authorize
the job; this query grants no resource or cross-segment access. Existing dispatched
jobs fence shared connector mutations through the reciprocal physical-target
lease check, including when the processing interface itself is offline.


`processingIdentity(targetPosition, initialize)` returns an immutable complete
item/fluid/energy kind→UUID fingerprint map, or an empty map when loaded identity
cannot be proven. It uses the same normalized physical groups and persistent
provider component markers; the fingerprint also includes member positions and
registered block-entity types. It reads at most3×64 already loaded components and
never resolves capabilities or observes quantities. `initialize=true` is reserved
for newly authorized processing capture before ingredient extraction: all existing
markers/groups validate before any missing marker is written. Resumed work and
cleanup must pass false, which writes nothing and cannot adopt a replacement.
This server-thread metadata hook grants no resource or segment authority. The
compiled replacement/reload examples are in `ProcessingJobGameTests`.
