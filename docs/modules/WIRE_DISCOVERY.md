# Budgeted physical wire discovery

Implemented P2 discovery producer and nonloading world adapter supporting A05/A10
in part. Automatic runtime region coordination, block/chunk/ownership event
invalidation and storage activation remain pending. This producer does not
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

Source epoch changes are checked during advance and publication access, including
completed jobs. `cancel()` immediately invalidates issued scopes. The runtime
coordinator must call cancellation/invalidation before edits and before queued
mutations can use an already retained topology reference; an epoch supplier is
not a world event listener. Constructor/reader/supplier callbacks belong to the
trusted owner thread and must have bounded work. A stale/failed/cancelled job
cannot publish. Quantity/resource/grant state remains untouched.

Limits/job: 4,096 seed visits, 4,096 graph vertices, 24,576 pending neighbor requests
and 28,672 memoized probes. Zero or over-limit advance arguments throw before
advancing. Descriptor/graph/reader failures produce FAILED with up to 512 diagnostic
characters. These count ceilings are not measured memory/server latency claims.

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

`DiscoveryTask` submits one coalesced job to the common `FairScheduler` with
explicit network/program UUIDs and key. Its cost equals the configured visit
budget (1–256). It resubmits unfinished work; no timer, worker or idle scan exists.
Queue/cost admission rejection cancels the discovery and records its reason.
Read diagnostics on the owner thread; scheduler limits still govern dispatch.
Runtime ownership of region jobs and event invalidation is subsequent work.

Example in trusted server integration:

```java
var reader = new WorldWireReader(level, knownGeometry::get);
var job = new WiredDiscovery(UUID.randomUUID(), 8, seeds.iterator(), reader,
    () -> regionEpoch);
var task = new DiscoveryTask(sharedScheduler, workNetwork, workProgram,
    "discover", job, 64);
task.submit();
// On world edits, call job.cancel() immediately before scheduling a replacement.
// After completion, inspect job.publishedTopology(); valid scope is still required.
```

## Evidence and remaining integration

Six WiredDiscoveryTest cases cover reciprocal edges/private segments, unloaded
root/gateway behavior, removed geometry, physical bypass, collision, stale/cancelled
publication, a 4,096-node one-visit chain, over-limit nodes/seeds and real scheduler
rescheduling/rejected admission. Two required WireDiscoveryGameTests capture real
controller/gateway/drive placements through the scheduler, inspect private inherited
views, explicitly cancel before a world edit, reject detached-root invention,
prove a remote chunk stays absent after probing, and reject loaded-air cache reuse.
They use explicit test-owned scheduling/cancellation, not automatic production
world event hooks. Actual event coordination, storage activation, chunk lifecycle/
restart/multiplayer and measured server performance remain required P2/P7 evidence.
