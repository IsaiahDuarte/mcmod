# ADR 0007 — Wired topology and current authority

Status: selected for the P2 portable topology/authority slice before source.
Portable implementation is locally verified. [ADR 0008](0008-network-persistence.md)
adds canonical grant persistence. World discovery, block ports and full multiplayer
evidence remain subsequent implementation work.

## Decision

Represent an affected wired region as a bounded graph of stable device/cable/root
IDs. Ordinary edges form undirected segments; explicit directed gateway edges
connect upstream/downstream segments. Same-segment loops are valid. Collapse
ordinary connectivity, then validate the gateway graph: one root per connected
network, no bypass/cycle/multiple parents, root upstream of all branches, depth
at most the selected configured limit (default eight, hard maximum 32).

Mutations increment a region generation immediately, invalidating all old scope
handles before queued operations execute. Incremental validation publishes only a
complete result for the unchanged generation. Changed/rebuilding regions grant
no access. A loaded gateway's child segment is named by its stable gateway ID;
root segments use their root ID. Disconnected uncommissioned regions stay offline.
Data ownership is independent and inherited stock is never copied to a branch.

Unloaded devices are unavailable. An unloaded root/cable suspends its segment;
an unloaded gateway suspends its downstream segment. Suspension propagates to
descendants, including local automation, while unaffected upstream/sibling
segments retain authority. The graph retains known unloaded membership for
revalidation; it never requests chunk loading. Invalid structural components
suspend entirely. World adapters must invalidate all affected regions on edits.

Permissions are private by default and checked at execution using current grants.
Owner grants are implicit; operators may explicitly administer grants/transfer
ownership, but have no implicit storage or script-scope bypass. Gateway branch
permissions intersect along the complete ancestry. Inherited storage operations
add per-edge operation/resource-kind restrictions. Local machine label lookup is
case-sensitive, paginated and confined to the caller's segment, including owners.
Parent views never enumerate child stock or machines.

## Bounds and alternatives

Per region: 4,096 vertices, total degree at most eight per vertex, at most 4,096
gateways. Validation advances by at most 256 charged visits per call, with no
recursive graph walk; permission paths have at most 32 gateways. Machine pages
contain at most 64 results. Per network: 256 explicit principal grant entries.
These technical admission defaults are bounds, not measured release performance.
World discovery must use the shared fair scheduler, bounded neighbor probing and
coalesced invalidation rather than rebuilding every tick.

A single unconstrained flood fill cannot safely bound rebuild work. Nearest-root
selection or automatic root merging would invent authority and widen private
scopes. Retaining partially rebuilt views could authorize stale mutations.
Conservative generation invalidation and complete publication trade brief pauses
for explicit scope safety. No platform types enter the portable core.

## Verification

Require ordinary loops, independent roots, nested policy intersections, reused
labels, stale/foreign handles, gateway bypass/cycles/multiple parents, depth,
structural/device unload and recovery, revocation before queued execution,
private defaults and explicit ownership transfer. Verify per-call work/admission
bounds and changed-generation publication rejection. World ports, chunk behavior,
network persistence and end-to-end scheduling remain separate mandatory evidence.

## Amendment — Relay (2026-11-01, ergonomics plan Q31, QD7)

Redstone may now change topology, through one block: the Relay (`RelayBlock`).
- **Ports:** the Relay's wired faces, and so its ports, exist only while its `powered` state is
  true. `WorldWireReader` reads it as a cable joint, so this authority stays in the wire reader.
- **Applying a change:** a neighbour change schedules a block tick. The tick reads the signal
  and, if it changed, sets `powered`, which marks the network changed through the usual
  `onPlace`/`neighborChanged` path.
- **Rate limit:** after applying a change, the Relay schedules its next read `COOLDOWN` (20)
  ticks later, and ignores neighbour changes while that tick is pending. So a Relay causes at
  most one rebuild per second, and settles on the last signal.
- **State:** it has no entity and saves only `powered`. Placement reads the current signal.

**Alternatives:**
- **A redstone mode on the existing Gateway:** rejected. Gateways have policy and lease state,
  and an on/off switch is a simpler, separate idea.
- **Applying every change at once:** rejected by QD7. A clock on the Relay would rebuild the
  network every tick.
