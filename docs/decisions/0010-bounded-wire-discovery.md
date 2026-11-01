# ADR 0010 — Budgeted physical wire discovery

Status: accepted; selected before source, with local core/world producer evidence. Runtime
region coordination, event invalidation and device activation were subsequently
integrated in ADR 0011; this producer does not independently activate resource owners.

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

ADR 0011 extends the seed/probe admission to 8,192/32,768 for full-size region
replacement sources. Graph limits and per-visit work remain unchanged; the bounds
below record the original producer decision.

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

## Amendment — coloured cables (2026-10-31, ergonomics plan Q29, QD5)

The cable block has a `color` state: `none` or one of the 16 dyes (`CableColor`; the names are
save format).

- **Links:** two cables are linked only when their colours join: either is `none`, or both are
  the same.
- **Discovery:** `WorldWireReader` drops a cable's port toward a loaded neighbour cable whose
  colour does not join. The neighbour drops its port too, and discovery links only reciprocal
  ports, so the link is absent from both sides. An unloaded neighbour is decided by its own
  (cached) reading, so no chunk is loaded.
- **Arms:** `NetworkCableBlock.linksTo(self, neighbour, side)` applies the same rule, so arms
  match ports. `CableColorGameTests` checks this.
- **Other devices:** devices have no colour and link to any cable.
- **Recolouring:** a recolour is a block-state change. `onPlace` and the neighbours'
  `neighborChanged` mark both positions changed, so the network rebuilds like any placement.
- **Items:** the colour travels on the item as vanilla `block_state` and comes back through the
  loot table (`copy_state`, only when coloured) and Pick Block. An uncoloured cable keeps no
  component, so it stacks with crafted cable.
- **Crafting:** the grid recipe is one special recipe, `factorycore:cable_dye`, like vanilla
  armour dyeing. It has no recipe-book entry and makes no new cable.

**Alternatives:**
- **Sixteen cable blocks:** rejected. They would need sixteen items, recipes and loot tables,
  and would be harder to recolour in place.
- **A per-cable block entity:** rejected. Cables have no entity, and keeping it that way keeps
  wide cable runs cheap.
- **A cable anchor that blocks one side (M93):** not added here (Q96). A per-face property on
  the cable would multiply its states by 64. AE2's anchor is a part on a multipart cable, which
  Factory Core does not have.

## Amendment — facades (2026-10-31, ergonomics plan Q30, QD6)

The cable block also has a `facade` state.
- **Entity:** while `facade` is true, the cable has a `FacadeEntity` holding the block it shows.
  This is the only time a cable has an entity, because `newBlockEntity` returns null otherwise.
  Wide plain runs stay entity-free.
- **Links:** a facade changes nothing about links. The wire reader ignores the state, and
  `cosmeticOnly` treats a facade going on or off as no topology change.
- **Shape:** a faced cable is a full block.
- **Allowed blocks:** `FacadeItem.allowed` accepts a full, opaque, model-rendered block with no
  entity and a block item, from outside Factory Core. It is read from the default state, so client
  and server agree.
- **Saved data:** `FactoryCoreFacade` holds schema 1 and the block id. An unknown or disallowed
  block, or another schema, loads as no facade. The cable and its links are unaffected.
- **Breaking:** a player's break (`onDestroyedByPlayer`) takes the facade off, keeps the cable,
  and drops the facade unless the player is in creative. A removal by any other means (explosion,
  Wrench) drops the facade through `dropsOnBreak` alongside the cable's loot.
- **Rendering:** a block-entity renderer draws the block 0.2% oversize, so the cable's arm ends
  never show through. The facade item uses a built-in entity model drawn as its block.
- **Recipe:** the special recipe `factorycore:facade` is the facade's one survival source.

**Alternatives:**
- **A baked model that wraps the shown block's model:** this would render in the chunk mesh
  instead of per frame. It was deferred: it needs model-data plumbing, and the renderer is enough
  for a few facades per base. Revisit if a profile shows facades in frame time.
- **A separate facade block:** rejected. Discovery and colour already key on the cable block, and
  a second block would need both.
