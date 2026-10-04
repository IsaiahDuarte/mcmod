# Physical controllers, gateways and cables

Implemented P2 physical ownership/ports for parts of A05/A12/A13/A14. These blocks
do not yet discover or activate a network. Bounded world discovery, routing,
gateway policy editing, permission screens and terminals remain pending.
[ADR 0009](../decisions/0009-structural-leases.md) records the design.

## Canonical structural ownership

`NodeRegistry` owns controller/gateway records on its constructing server thread.
It stores no stock or copied permission owner. `Handle(world, id, generation,
kind)` is a reference with positive checked generation; `Position(dimension,
packedBlockPosition)` has a namespaced dimension ID of at most 256 characters.
Controller ID equals its canonical network UUID; gateway IDs are disjoint from
network and resource backing IDs. Gateway policy is immutable canonical state.

`DeviceSavedData.createController(authenticatedOwner, position)` preflights
structural position/count and network count before allocating grants or a node.
`createGateway(position)` allocates a new gateway with open policy, granting no
network permission or scope. `nodes()` is a trusted lower-level authority; it is
not exposed to clients/guests. Core `create(id, kind, position)` requires an
allocator maintaining the external network/backing namespace invariants.

Limits: 4,096 structural owners/world, one owner/position; existing network and
resource ceilings remain independent. Admission checks use indexed positions.
Unknown IDs never create replacements. New commissioning and successful
transitions notify trusted, nonthrowing dirty callbacks. A network permission
UUID/generation is distinct from the physical lease and topology scope.

| Operation | Result and behavior |
| --- | --- |
| `admission(position)` | OK, OCCUPIED or LIMIT, without mutation. |
| `create(id, kind, position)` | New placed generation-one owner or typed admission failure; duplicate UUID throws. |
| `validate(handle)` | OK, UNKNOWN, FOREIGN_WORLD, STALE or CONFLICT. Kind mismatch is stale. |
| `definition(handle)` | Immutable current stored record; throws on invalid reference. |
| `attach(portable, position)` | Increment generation, consume portable reference, occupy position. |
| `attach(currentPlaced, samePosition)` | Idempotent reload; no generation/dirty change. |
| `attach(currentPlaced, otherPosition)` | Persistently quarantine the canonical owner; no second owner. |
| `detach(currentPlaced, exactPosition)` | Increment generation, vacate position, preserve ID/policy, return portable reference. |
| `snapshot()` / constructing from records | Immutable sorted state / reject duplicate UUIDs, positions and invalid bounds. |

Overflow rejects before mutation. Conflicts survive reload and remain unavailable
pending administrative reconciliation. Stale copies cannot move or reset a newer
owner. Known chunk references cannot attach a portable lease: only explicit
physical placement may consume it. `NetworkNodeEntity.validatePlaced()` checks current canonical kind/generation/
position before inspection, so a copied owner is also unavailable on its original
block. Structural validation alone grants no routing;
world operations must additionally validate loaded membership and current scopes.

## Local references and player path

Chunks and items store `FactoryCoreNode`, local schema one compound with integer
`Schema=1` and optional `Lease`: UUID `World`, UUID `Id`, positive long `Generation`,
string `Kind` (CONTROLLER or GATEWAY). Missing local data means uncommissioned;
malformed/future data is preserved verbatim, including wrong-typed envelopes.
Unknown local fields reject. A kind mismatch remains unavailable. Local data
contains neither grant ownership nor gateway policy.

A controller placed with a fresh item commissions the actual server placer as
owner. A command-created controller remains uncommissioned; explicit use by a
player commissions a new private network. Reload never invents an owner.
A fresh gateway commissions its structural identity on placement/use, but no
network grants. Move a controller/gateway by breaking and placing its single
portable reference; controller grants and owner survive. Creative placement also
consumes the reference. Invalid/future references break into a labeled recovery
casing and remain unavailable on replacement; do not discard recovery items.
Default controller/gateway loot is suppressed because removal emits that one
reference. Physical access remains outside script grants, as required by R04.

Gateway FACING is downstream; opposite is upstream. Its red observer output face
is downstream; the face with eyes is upstream. Only those two faces connect;
`NetworkContent.connects(state, face)` provides local port eligibility for the
registered controller/gateway/cable/drive roles, rejecting ordinary vanilla blocks.
It checks no load/scope permission; inspection reports both gateway directions. Controllers and cables accept all six faces.
Placement selects the face opposite the player's nearest look direction. Cables
have no owned mutable state or registry lease. Current assets reference vanilla
textures; real client visuals and survival playtesting remain unverified.

## Schema-three save boundary

Canonical `factorycore_devices.dat` now requires integer `Schema=3`, UUID `World`,
and compound `Devices`, `Networks` and `Nodes` lists. Each node has UUID `Id`,
string `Kind`, positive long `Generation`, boolean byte `Conflict`, optional
compound `Position` (string `Dimension`, long `Block`). Gateways require compound
`Policy`: integer `Branch` and `Inherited` fixed permission masks (0–127), and
integer `Kinds` (ITEM=1, FLUID=2, ENERGY=4; 0–7). Empty restrictions are valid.
Controllers must have a corresponding network entry and no policy. Gateways
cannot overlap resource/network IDs. Duplicate node/position and unknown fields,
kinds, masks/types quarantine the complete original authority without overwriting.

Recognized schema one/two migrates exact resource records and any existing grants
into schema three, adding an empty node list and marking dirty. No earlier schema
had structural nodes. Binary ledger and local cell/reference schemas are unchanged.
Committed fixtures protect exact migrations and schema-three policy meaning.

Four NodeRegistryTest cases verify leases, copies, reload, limits and overflow;
four NodePersistenceTest cases verify the fixed fixture, compressed disk movement,
no orphan grants on rejected admission and preserved malformed ownership.
Four required NetworkNodeGameTests exercise actual creative item placement/move,
idempotent lifecycle reload and clone quarantine, future reference break/place,
portable chunk rejection, gateway faces, vanilla recipes and explicit commissioning.
They do not prove world discovery, active storage, actual chunk unload/full restart,
client/multiplayer interactions or abrupt save ordering. Independent chunk/player/
entity writes and foreign handlers still have no crash-atomicity guarantee.
