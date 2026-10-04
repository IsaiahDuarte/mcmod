# ADR 0010 — Budgeted physical wire discovery

Status: accepted; selected before source, with local core/world producer evidence. Runtime
region coordination, event invalidation and device activation remain subsequent
integration; this producer must not independently activate resource owners.

## Decision

Capture physical wire descriptors on the owner thread under a charged visit
budget, then run the existing incremental topology validator under the same
budget. Descriptors contain bounded nodes, reciprocal physical ports and an
optional directed gateway edge. A gateway has separate upstream/downstream
virtual vertices so ordinary adjacency cannot erase its boundary. Connect only
reciprocal ports; a neighbor on a gateway's side is not connected.

Use nonloading `ServerChunkCache.getChunkNow`; an absent chunk contributes only
known cached descriptors marked unloaded, never a fabricated root or new edge.
Cache entries are immutable references, not resource/grant copies. Loaded air
removes stale cached geometry. Canonical structural ownership validates loaded
controllers/gateways; invalid owners are represented unavailable, not reassigned.
Position-derived cable/drive/virtual IDs remain descriptors under fresh runtime
region UUIDs, with explicit collision rejection.

Each job has a captured source epoch and constructing thread. An epoch change or
explicit cancellation prevents publication and invalidates published scopes.
The future runtime coordinator must invalidate immediately on block/chunk/owner
changes before scheduling bounded rediscovery. Existing valid views must not be
used while rebuilding. Failed/over-limit discovery remains unavailable and keeps
resource ownership intact. No observer/activation behavior is implied by this
producer's existence.

## Bounds and alternatives

At most 4,096 seed visits, 4,096 graph vertices, six ports/physical descriptor,
two vertices/descriptor, 24,576 queued neighbor requests and 28,672 memoized
physical probes/job. Advance takes 1–256 visits. One discovery visit probes one
position and performs at most six fixed port/neighbor operations; topology work
uses the existing charged validator. Reader errors/collisions/bounds fail closed.
Actual server latency remains a P7 measurement, not a consequence of these caps.

An unbounded synchronous flood fill violates tick requirements. Connecting a
gateway as one ordinary vertex bypasses segmentation. Reading chunks through
loading APIs violates R04; dropping cached unloaded cables can falsely leave a
loaded fragment active. Complete generation-checked publication avoids these
problems and supports deterministic fake probes and real world tests. It costs
bounded rebuild latency and retained geometry for known unloaded membership.

## Verification

Require reciprocal ports, gateway isolation/bypass, unloaded cached root/cable/
gateway suspension, removed geometry, changed/cancelled jobs, collisions/limits,
seed and neighbor admission, one-visit progress and shared FairScheduler dispatch.
Use actual world placement/ports and nonloading chunk behavior for the adapter.
Full world-event coordination, activation, multiplayer/restart and representative
server performance remain mandatory owning-stage work.
