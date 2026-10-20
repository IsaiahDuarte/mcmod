# Budgeted physical wire discovery

Implemented P2 discovery producer and nonloading world adapter supporting A05/A10
in part. [Live region coordination](WORLD_NETWORKS.md) now connects automatic
block/chunk/owner callbacks and storage activation. This producer does not
activate resource owners or expose client/guest operations. See
[ADR 0010](../decisions/0010-bounded-wire-discovery.md) and the existing
[topology/authority contract](NETWORK_AUTHORITY.md).

## Descriptor and traversal contract

`WiredDiscovery` belongs to its constructing server thread. Construct it with a
fresh region UUID, configured gateway depth (1–32), an iterator of seed positions,
a bounded physical reader and a current source-epoch supplier. Positions use
`NodeRegistry.Position` dimension/packed-block units. Reader null means no known
wire. Neither seeds nor discovered topology grant ownership.

A `Wire` is immutable: one ordinary vertex or two gateway virtual vertices, at
most six `Port(neighborPosition, localVertexUUID)` descriptors, and an optional
directed gateway edge. Neighbor positions must be unique in a descriptor;
vertices/gateway IDs must be globally disjoint in the captured region. A port is
eligible only when the neighboring descriptor has a reciprocal port. Unsupported
gateway sides do not join the graph. Two virtual gateway vertices preserve
upstream/downstream segmentation. Ordinary cable loops remain valid.

`advance(limit)` accepts 1–256 charged visits and reports work, state, distinct
probes/captured physical descriptors and a bounded diagnostic. A discovery visit
consumes one seed or one neighbor request, probes at most one position, and does
at most six fixed port/neighbor operations. Private topology insertion is bounded
by two vertices and one gateway per descriptor. Validation uses the existing
one-visit incremental graph validator. Repeated physical probes are memoized;
neighbor requests remain bounded. Publication is complete, never partial.

| State | Meaning |
| --- | --- |
| DISCOVERING | Capturing physical adjacency under budget. |
| VALIDATING | Checking the private complete graph under budget. |
| PUBLISHED | Complete current topology, including explicit invalid-component statuses. |
| CANCELLED | Source epoch changed, explicit cancellation or rejected scheduling. |
| FAILED | Reader, collision or admission failure; no usable publication. |

A published graph can contain NO_ROOT, MULTIPLE_ROOTS, GATEWAY_BYPASS and other
existing topology failures; publication itself does not imply valid routing.
`publishedTopology()` is a trusted integration API, requires current PUBLISHED
state and returns the existing owner-thread topology. Callers must use current
scope/authority checks. `captured(position)` returns one immutable descriptor
without copying a full region; it is geometry, including private failed/cancelled
capture, and grants no access.

The optional sixth constructor argument is a trusted owner-thread
`BiConsumer<Position, Wire>` capture listener. It receives each joined descriptor
once, after private capture; merely probed nonreciprocal neighbors are excluded.
It may index immutable geometry with bounded work, but must not reenter/mutate the
producer or grant authority. Listener admission exceptions cause FAILED and prevent
publication; retained indexes remain provisional. The five-argument overload uses
an empty listener. No complete-capture copy or uncharged enumeration is needed.

Source epoch changes are checked during advance and publication access, including
completed jobs. `cancel()` immediately invalidates issued scopes. The runtime
coordinator must call cancellation/invalidation before edits and before queued
mutations can use an already retained topology reference; an epoch supplier is
not a world event listener. Constructor/reader/supplier callbacks belong to the
trusted owner thread and must have bounded work. A stale/failed/cancelled job
cannot publish. Quantity/resource/grant state remains untouched.

Limits/job: 8,192 seed visits, 4,096 graph vertices, 24,576 pending neighbor requests
and 32,768 memoized probes. Zero or over-limit advance arguments throw before
advancing. Descriptor/graph/reader failures produce FAILED with up to 512 diagnostic
characters. These count ceilings are not measured memory/server latency claims.

## Retained replacement seeds

`DiscoverySeeds(anchor)` is an owner-thread iterator for trusted replacement
sources. `appendPositions(iterator)` retains a bounded callback source without
copying it; null rejects. `append(oldSources)` transfers a flat queue only after
the old producer is cancelled. The previous producer becomes empty and cannot
append or transfer again, preventing mutable-cursor sharing and indirect cycles. Each
`next()` inspects only the head iterator: an empty-source transition removes one
source and returns the anchor to charge a visit. `hasNext()` does not scan sources.
Exhaustion throws NoSuchElementException. Iterator callbacks must be bounded.

The aggregate flat queue admits at most8,192 frames, including completion visits
for adopted queues. Splicing checks the combined count before changing either
owner; it neither polls iterators nor copies positions. Each frame retains its
original completion anchor. Traversal has no nested DiscoverySeeds recursion, so
repeated interruptions no longer hit the obsolete depth32 iterator limit. Region
ranked region-ownership lookup and independent WiredDiscovery seed/probe budgets remain bounded (see [world coordinator](WORLD_NETWORKS.md)).
Sources are transient and grant no resource or permission authority. Five
DiscoverySeedsTest cases cover charged transitions,512 successive transfers,
combined rejection without callback execution or cursor loss, cycles/sharing and
queue limits.

```java
var oldSeeds = new DiscoverySeeds(anchor);
oldSeeds.appendPositions(previousPositions.iterator());
var nextSeeds = new DiscoverySeeds(anchor);
nextSeeds.append(oldSeeds); // old producer is cancelled; stop appending to oldSeeds.
// Pass nextSeeds to a fresh WiredDiscovery; each transition remains charged.
```

## Actual world reader and common scheduling

`WorldWireReader(serverLevel, cachedGeometryLookup)` reads on the server thread.
Foreign dimensions reject. It uses `getChunkNow`, never a chunk-loading getter.
Absent chunks return only known cached descriptors with unavailable physical
nodes; unknown positions return null. Cached gateways retain their virtual
anchors and mark the gateway itself unloaded, suspending descendants while
leaving upstream/sibling segments usable. Loaded air discards cached geometry.

Loaded controller/gateway references validate canonical kind/generation/position.
Invalid/uncommissioned owners remain unavailable; neither geometry nor copied
NBT can manufacture grants. Gateway policies come from canonical SavedData only.
Drive geometry is loaded only with a live, diagnostically usable drive entity;
missing entities are unavailable. Position-derived UUIDs for cables/drives and
virtual anchors are world/dimension/role scoped and collision-checked by discovery.
They are descriptors under the fresh region identity, not persistent resource
backing IDs. This reader reads no cell stock and changes no lease/resource state.

`WorldNetworks` owns scheduling: one shared, bounded scheduler step advances active regions in
turn, with no timer, worker or idle scan. Runtime ownership and event invalidation are documented
in the [live region contract](WORLD_NETWORKS.md).

Example in trusted server integration:

```java
var reader = new WorldWireReader(level, knownGeometry::get);
var job = new WiredDiscovery(UUID.randomUUID(), 8, seeds.iterator(), reader,
    () -> regionEpoch);
// Advance in bounded steps from the owner's scheduled work, e.g. job.advance(16).
// On world edits, call job.cancel() immediately before scheduling a replacement.
// After completion, inspect job.publishedTopology(); valid scope is still required.
```

## Evidence and remaining integration

Seven WiredDiscoveryTest cases cover reciprocal edges/private segments, unloaded
root/gateway behavior, removed geometry, physical bypass, collision, stale/cancelled
publication, a 4,096-node one-visit chain, over-limit nodes/seeds and real scheduler
rescheduling/rejected admission. Two required WireDiscoveryGameTests capture real
controller/gateway/drive placements through the scheduler, inspect private inherited
views, explicitly cancel before a world edit, reject detached-root invention,
prove a remote chunk stays absent after probing, and reject loaded-air cache reuse.
Those two tests use test-owned scheduling/cancellation. Five additional
WorldNetworksGameTests exercise production post-ticks/events and automatic cell
activation; actual chunk lifecycle/restart/multiplayer and measured server
performance remain required P2/P7 evidence.
