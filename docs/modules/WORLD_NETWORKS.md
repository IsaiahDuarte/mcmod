# Live wired region coordination

Implemented P2 integration supporting A05/A10/A12/A13 in part. See
[ADR 0011](../decisions/0011-live-region-coordination.md),
[wire discovery](WIRE_DISCOVERY.md), [authority](NETWORK_AUTHORITY.md) and
[player instructions](../PLAYER_GUIDE.md). This provides automatic wired
membership and installed-cell availability, not a terminal or transfer endpoint.

## Lifecycle and authorization

`WorldNetworks.get(server)` is a trusted server-thread API after the Overworld
exists. One coordinator/server owns transient position, chunk and immutable
geometry indexes, per-dimension readers and the shared `FairScheduler`.
`changed(level, blockPosition)` ignores client/null levels. The position overload
uses `NodeRegistry.Position` dimension/packed-block units and checks thread
ownership. Block placement/removal/neighbor callbacks cover controllers, gateways,
drives and cables, including orientation changes. Structural commissioning,
lease rebinding and drive cell installation/removal also notify it. Block-entity
load/removal and chunk load/unload invalidate known membership. Server post-ticks
run scheduled work; server stop releases the static runtime reference.

Fresh-region seeding uses `NetworkCableBlock.linksTo`, the same eligibility rule
as physical cable arms. This includes isolated buffers, crafting devices,
Program Controllers and access points, in addition to structural/storage blocks.
An isolated access point therefore completes discovery with NO_ROOT instead of
remaining untracked/REBUILDING indefinitely. This grants no usable scope; normal
topology and current authority still gate mutations. Cosmetic lamp and drive-bay
updates do not invalidate topology (see [ADR 0058](../decisions/0058-device-presentation.md)).

An edit checks at most its own position and six neighbors. Affected publications
are cancelled immediately; their registry routing predicate denies stock use
before another tick. Repeated notifications coalesce until a scheduled replacement.
Indexed chunk callbacks invalidate known region references in the changed chunk
and its four horizontal neighbor chunks, including regions
whose original anchor was removed. No world scan or unknown-chunk load occurs.
Canonical root/gateway lease generation, conflict and position checks also run
on every valid membership query: copying a root outside its indexed region still
stops the original stock immediately. Grant/policy checks run when authority is
used, so queued requests cannot retain revoked permissions.

`membership(position)` returns current `NetworkTopology.Membership`, never partial
scope. Rebuilding/untracked positions have no usable scope. A fresh replacement
region identity makes old scopes unusable. Completed discovery alone is insufficient:
position indexes, root grant owners and at most four drive cells/position prepare
under budget, followed by a single region visibility flag. Invalid graph statuses
remain explicit (including NO_ROOT, MULTIPLE_ROOTS and GATEWAY_BYPASS).

`storage(actor, origin, exactResourceKey, operation)` returns the existing
`NetworkAuthority.StorageQuery` of permitted segment UUIDs, nearest/local first.
Actor identity/operator status and origin must come from trusted server integration.
VIEW/DEPOSIT/WITHDRAW/CRAFT retain their core permission semantics and typed errors.
The result enumerates segment scope, not resource endpoints or stock. It never
includes child-private segments. Future player/program endpoints must use this
current shared authority plus resource accounting/scheduling at execution.

```java
var networks = WorldNetworks.get(level.getServer());
var origin = new NodeRegistry.Position(level.dimension().location().toString(),
    drivePosition.asLong());
var view = networks.storage(serverActor, origin, exactItemKey,
    NetworkPermissions.Permission.VIEW);
// Only ALLOWED provides usable segments; no inventory write follows from a query.
```

## Ownership, bounds and failures

Region replacement seeds old positions and remaining source iterators incrementally,
including disconnected fragments. A newly connected region absorbs old owners
without copying resource/grant records or scanning their ledgers. Replacement links
resolve old index references until charged preparation updates/removes each position.
Known unloaded geometry is retained only as unavailable cached descriptors. The
runtime does not persist geometry: restart starts offline and reconstructs it from
loaded entities and subsequent edits. Existing registry schema three and local
lease formats do not change.

Admission ceilings/server: 65,536 indexed physical positions, 8,192 queued entries
(including retired entries awaiting charged removal), ranked ownership lookup at most 32 references.
Each scheduled step costs 32 credits and processes at most 32 visits; fair scheduler
global/network/program limits and deadline remain in force. A visit advances one
discovery/validation operation, skips one retired entry, creates one replacement,
or prepares one position. Discovery independently caps graph vertices at 4,096,
seed visits at 8,192, pending neighbors at 24,576 and probes at 32,768. Portable `DiscoverySeeds` now holds a flat, ownership-transferred queue capped at8,192 aggregate frames, including charged completion visits; the former iterator-depth32 limit is obsolete because traversal no longer nests. Region redirects now use union by rank, so repeated replacements do not create an ever-longer ownership chain. Each lookup has a hard 32-reference bound; a rank-n root requires at least 2^n merged references. Merging updates only two roots, preserves the selected replacement owner, and never scans known positions. Retired non-root references release their owner value. This state is server-thread-owned and ephemeral; canonical IDs, authority and persistence do not change.

Gateway nesting uses the world's `serverconfig/factorycore-server.toml` setting
`network.gatewayDepth` (default 8, range 1..32). Each gateway from root to leaf counts
once, including gateways connected through wireless pairs. A coordinator captures
the loaded setting once at creation and passes it to every region discovery.
NeoForge marks it as requiring a world restart; file edits apply after world reopen
or dedicated-server restart, preventing mixed limits among existing and rebuilt
regions. Over-depth components report `DEPTH_LIMIT` and remain unavailable without
changing resource ownership. Invalid numeric settings are bounded by NeoForge's
config correction; nonnumeric values return to the default.
Borrowing freezes a source against further appends and prevents sharing its mutable
cursor, excluding indirect cycles. Empty source transitions consume a seed visit.
Depth/queue rejection leaves old sources intact and marks the affected publication
unavailable; scheduled failures do not prevent other queued regions progressing. Increasing seed allowance from 4,096 lets a full
4,096-node rebuild include its repeated anchor/source transitions; it does not
increase graph capacity. Source callbacks must remain bounded and non-reentrant.

Quota, collision or rebuild failure keeps affected ownership unavailable, with a
bounded diagnostic; unrelated published regions remain available. Scheduler task
failure records diagnostics without invalidating independent geometry. Canonical
registry quarantine/unreadable files disable runtime admission while preserving
upstream recovery data; no empty registry is manufactured. Cleanup such as releasing
reservations remains available while routing is offline.

`diagnostic()` returns the last server diagnostic (up to 512 characters). Detailed
per-device quota/recovery UI is pending. Long chains/interrupted sources can reach
admission bounds; an edit/restart can retry but automatic quota reconciliation is
not implemented. Disconnected fragments may remain grouped in one affected region,
so their further edits can suspend its other components during validation. Cache
repartitioning/reclamation and actual chunk callback/rebuild latency need P7
workload measurements. These count bounds make no server latency guarantee.

## Local evidence and remaining verification

Five required WorldNetworksGameTests use production callbacks and server post-ticks,
without manually ticking their runtime. Four exercise production block/entity
events; a fifth invokes the typed chunk-load adapter on a deliberately loaded
fixture chunk to verify neighboring-index invalidation. That adapter test is
not an actual unload/reload experiment. They verify initial offline placement,
automatic activation, immediate cable-disconnection denial, detached NO_ROOT,
reconnection preserving exact stock/claims, 100 coalesced edit notifications,
multiple-root invalidation/recovery, independent regions, private inherited segment
views, queued grant revocation and original-root stock denial after a remote clone.
Existing storage ownership fixtures now build physical valid roots before stock
seeding, preserving their earlier ownership/recovery assertions.

Core regressions verify queued writes recheck the routing predicate, offline
reservation cleanup, explicit loaded flags, capture-listener admission failure
and full-size rediscovery with repeated seeds. Actual chunk unload/reload and full
world restart, interrupted independent save boundaries, client/multiplayer,
representative server measurements and complete A01–A17 release remain unverified.


[Storage-mode Interfaces](CONNECTORS.md) are leased storage nodes with five cable
faces, excluding the provider socket. All six orientations participate in bounded
world discovery and port eligibility. `authorize(actor, origin, permission)`
exposes the current shared authority for small server-side configuration actions;
connector edits do not bypass gateway policies. `connectors()` lazily returns one
server registry, and static invalidation cleans existing bindings without creating
services during recovery/shutdown. Connector rotations use normal topology
invalidation before accepting fresh endpoint handles.


The [native split-root experiment](../evidence/split-root-v1-2026-10-17.md) now
keeps a child gateway/drive/machine loaded while the upstream root actually
unloads. Storage/preset work stops, stock/holds persist, probes do not reload the
root, and the original preset resumes only after the original root revalidates.
It also covers the finite cable-run trigger for the flat retained-seed fix.
This does not complete staged-unload, independent-save or release recovery gates.


## Replacement-chain regression

A recovered graphical restart log repeatedly reported `Network replacement depth limit`
while the split-root child remained `NO_ROOT`. The old owner lookup followed a linear
chain capped at 32, so a position retained through enough rebuilds could lose its usable
owner. `RegionReferenceTest` exercises 4,096 successive replacements while querying the
oldest owner, preserves an unrelated region, and merges 1,024 equally sized groups to
check redirects in both rank directions. Integrated JVM and graphical rerun results
must be recorded separately; the other recorded restart-stage-4 failure remains
unexplained.
