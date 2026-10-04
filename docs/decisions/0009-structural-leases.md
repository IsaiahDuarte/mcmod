# ADR 0009 — Physical controller and gateway leases

Status: accepted; selected before source and locally verified with core/disk/GameTests. Discovery/activation remain subsequent
work; this slice must provide real placement, port orientation and ownership.

## Decision

Add a bounded portable structural registry, separate from resource ledgers.
Controllers and gateways have world-scoped UUID/generation leases and one exact
dimension/block position. Controller UUID equals its existing canonical network
UUID; the owner/grants survive movement. Gateways have distinct allocated IDs
and canonical immutable gateway policies. Cables have no owned mutable data and
need no portable lease; world discovery may derive their ephemeral graph identity
from position under a fresh runtime region generation.

Extend the same SavedData authority to schema three with required `Nodes`,
preserving schema-one/two world identity, exact resource records and grants while
adding an empty node table and marking migration dirty. Controller allocation
preflights node count/position and network count before creating either owner.
Limits are 4,096 structural owners/world and one structural owner/position.
Controller records require corresponding grants; gateway IDs cannot collide with
network/resource IDs. Stored policy masks use fixed permission bits and explicit
ITEM=1, FLUID=2, ENERGY=4; empty restrictions are valid. Unknown fields/kinds/masks
or duplicate owners/positions preserve and quarantine the full original save.

Physical items/chunks carry references, never copied grant ownership or policy.
Portable placement consumes a checked generation; same-position reload is
idempotent; a current placed copy elsewhere quarantines the canonical owner.
Break consumes a generation and emits one portable reference, suppressing default
casing loot. Invalid or future local data is retained in a labeled unavailable
block/item. No absent/stale reference reconstructs an owner. Fresh controller
commissioning uses the actual server player's UUID, explicitly on placement or
use of an uncommissioned controller; a command-created root is uncommissioned.
Creative placement also consumes the physical reference. Operator status is not
part of the lease, and physical block access is not a claim-protection feature.

Gateway `FACING` denotes downstream; opposite is upstream. Only these two faces
connect, with distinct vanilla observer textures and inspection text. Other wired
roles/cables accept all six faces. No wrench/rotation UI is added in this slice.

## Alternatives and costs

An empty resource ledger would conflate structural identity with stock capacity
and quotas. Block-local grants would allow copied blocks to invent ownership.
Reallocating an owner on reload or every move would discard grants and widen
access. Canonical leases preserve explicit identity but conflicting copies remain
unavailable pending administrative recovery. Cables need neither copied owner
state nor permanent registry admission merely to express local adjacency.

Initial vanilla recipes: controller four iron, four redstone, one glass; gateway
four iron, two quartz, two redstone, one ender pearl; eight cables from six iron
and three redstone. These are delegated initial progression defaults, subject to
P6 survival validation. Routing/power behavior is not implied by these recipes.

## Verification and boundaries

Require real block/item placement, generation movement, cloned-block quarantine,
owner/grant preservation, port orientation, future data break/place preservation,
creative reference consumption, recipes and schema migration/disk fixtures.
Core tests cover duplicate positions/IDs, foreign/stale references, overflow,
admission and idempotent reload. Runtime discovery, configured policy edits,
validated device activation, actual chunk/restart/interruption and client/
multiplayer evidence remain separate required work. Orderly saves do not promise
atomicity across canonical SavedData, chunks, dropped entities or player inventory.
