# ADR 0011 — Live region coordination and routing availability

Status: accepted; selected before source. Local core and production post-tick
GameTests verify initial integration; actual chunk/restart/performance remain pending.

## Decision

Coordinate affected physical regions on the server thread using the common fair
scheduler. Indexed position/chunk geometry identifies affected owners without a
world scan. An edit cancels current publication immediately. Replacements seed
previous known positions incrementally so detached fragments and unloaded known
membership are not silently forgotten. Connected jobs absorb prior regions under
budget; inactive/disconnected geometry can share one bounded validation region.
Repeated callbacks coalesce until charged replacement creation; region links
preserve unfinished source iterators across edits.
Admission/rebuild failure keeps affected resource ownership offline.

Prepare position membership, permission owners and drive activation incrementally
before one final visibility flag. Resource registry queries/mutations combine the
transient loaded flag with a trusted routing predicate; cancellation therefore
makes all affected cells offline immediately without a synchronous ledger scan.
Current structural root/gateway leases are checked before routing access, including
canonical copy conflicts. Grant checks remain execution-time shared authority.

Register block/neighbor, block-entity load/removal and chunk lifecycle callbacks;
server post-ticks dispatch bounded shared work. Use nonloading chunk lookups and
retain cached unloaded descriptors. Cables must notify edits despite having no
block entity. Runtime geometry is reconstructed after restart; no save schema or
resource ownership changes. Remove runtime references on server stop.

## Bounds and limitations

Initial runtime caps: 65,536 known physical positions/server, 8,192 queued regions,
replacement and retained-source depth 32, 8,192 queued source iterators/container,
256 discovery visits/advance; scheduled steps use 32 visits.
Discovery permits 8,192 seed visits and 32,768 probes while retaining its
4,096 graph-vertex limit, so full-size replacement seeds plus repeated anchors
fit without weakening tick budgets. Position edits inspect at most seven indexed positions. Chunk invalidation checks the changed chunk and four horizontal neighbor indexes,
so loading a previously unindexed chunk cannot leave an adjacent publication current.
It visits only those indexed region references, capped by admitted runtime geometry;
rebuild, source traversal, indexing, permission registration and activation are
charged shared work. Chunk callback cost and full rebuild latency must be measured
in P7; these ceilings do not establish server latency or autosave guarantees.

Known disconnected fragments can remain in one affected region. Repartitioning
and cache reclamation require representative P7 evidence; do not hide admission
failures or widen scope to reduce rebuilding. Untracked new wires create no
routing scope. Quota diagnostics stay explicit, unrelated published regions stay available, and canonical resources survive
all runtime failures. Runtime restart starts offline and revalidates.

## Alternatives and verification

An immediate full-region ledger deactivation loop violates bounded mutation work.
Keeping ledger availability true without an execution-time routing guard allows
queued writes after disconnection. One global world epoch unnecessarily suspends
independent networks; indexed affected regions retain separate validity. Loading
unknown chunks invents availability and is prohibited.

Require real server tick publication, immediate disconnection denial, reconnection,
invalid roots/gateway bypass, private child storage, queued revocation, chunk/
owner callbacks, interrupted rebuilds and independent-region behavior. Existing
ownership GameTests must use valid physical root fixtures when seeding live stock;
standalone core accounting tests retain their explicit fake availability contract.
Full release/multiplayer/interruption/performance gates remain required.

Local evidence: five WorldNetworksGameTests use production events/post-ticks and
cover activation/disconnection/reconnection with exact claims, coalescing,
multiple roots/independent regions, private views/queued revocation and structural
copy conflicts. Core tests cover routing guard and listener failures/full-size
replacement seeds. See [runtime contract](../modules/WORLD_NETWORKS.md).

Interrupted-source review added a portable bounded seed iterator: borrowing freezes
source growth and forbids a second borrower. Depth/queue rejection preserves old
cursors; four core regressions verify admission, empty transitions, cycles/sharing
and retained positions. Replacement failures are caught within scheduled dispatch,
so unrelated queued regions continue. No source/schema/verification gate is weakened.


## Flat retained-source amendment — 2026-10-17

A native split-root fixture placing48 cable blocks exposed an indefinitely
rebuilding valid network with `Discovery source depth limit`. Repeated finite
region cancellations/merges nested retained iterators past32 even though discovery
size and tick work were within their existing bounds. That guard prevented stack
recursion but rejected ordinary finite edits and could leave publication offline.

Replace nested DiscoverySeeds wrappers with an exclusively owned linked queue of
bounded iterator frames. Transfer splices the remaining chain after checking the
combined8,192-frame ceiling, empties/freezes the previous producer, and adds one
completion frame preserving the former wrapper's charged visit. Each frame keeps
its original anchor. Append/transfer/hasNext do constant metadata work; next
inspects one iterator and removes at most one completed frame. Iterators still
must be trusted bounded callbacks. No source traversal or position copying occurs
inside transfer. Admission failure preserves both producers and all pending seeds.

The former iterator-depth32 guard no longer applies because there is no nested
DiscoverySeeds traversal. This is a representation change with a stronger
aggregate source bound, not an increased depth/queue/tick allowance. The separate
region replacement-depth32 and WiredDiscovery seed/probe/vertex/visit budgets are
unchanged. Raising the recursive depth limit would merely postpone the defect;
flattening eagerly by scanning every retained source would violate charged work.
No persistence/resource schema or permission policy changes.

Portable regressions preserve charged anchors, traverse512 successive transfers,
reject combined over-capacity without callback execution or cursor loss, and keep
single cursor ownership. Native split-root verification exercises the actual
finite-edit trigger; its run outcome is recorded in the implementation plan.
No measured tick-latency or heap guarantee follows from this algorithmic bound.
