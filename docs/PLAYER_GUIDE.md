# Factory Core player guide

This development build provides Storage Drives, item/fluid cells and upgrade
modules on Minecraft 1.21.1 / NeoForge 21.1.252. Network Controllers, Gateways and Cables now have physical placement and
ownership. Routing, terminals, automation and wireless access are still pending. Installed cells
remain offline; the current build has no player deposit/withdrawal endpoint.

## Cells and drives

Craft a Storage Drive, then use a cell on it to install the cell in its first
empty slot. A drive holds four cells. Sneak-use the drive with an empty main
hand to remove its first occupied cell into your hand. Breaking the drive drops
its cells as portable references and drops the casing with the normal loot rules.
Creative installation also consumes the cell reference to preserve one physical
holder. Full drives and invalid cells reject without consuming your input.

| Cell tier | Items | Fluids |
| --- | --- | --- |
| Starter | 8,192 item units | 16,000 mB |
| Expanded | 65,536 item units | 256,000 mB |
| Advanced | 1,048,576 item units | 4,096,000 mB |
| Infinite | No gameplay capacity limit | Not available |

Item quantities count individual items, not stacks. Fluid quantities use mB
(1,000 mB is one ordinary bucket). Item cells hold only items; fluid cells hold
only fluids. Infinite holds only deposited resources and retains signed-long
and metadata limits. The detailed administrative quota UI is pending.

## Crafting and upgrades

All recipes use vanilla materials. The recipe book unlocks each recipe when
you obtain its relevant material. Cells and modules are also in the Factory Core
creative tab. Initial icons reference vanilla textures.

| Recipe | Materials |
| --- | --- |
| Starter Item Cell | 4 iron ingots, 4 redstone dust, 1 chest |
| Starter Fluid Cell | 7 glass blocks, 1 empty bucket, 1 redstone dust |
| Storage Drive | 4 iron ingots, 4 redstone dust, 1 hopper |
| Expansion Module | 4 gold ingots, 2 redstone dust, 1 diamond |
| Advanced Module | 4 diamonds, 2 redstone dust, 1 netherite ingot |
| Infinite Module | 8 netherite ingots, 1 Nether star |

Remove a cell from its drive. Hold it in your offhand and use an upgrade module
from your main hand. Apply Expansion, Advanced and then Infinite in that order.
Fluids stop at Advanced. A successful survival upgrade consumes one module and
preserves the cell's backing identity, stored contents, reservations and custom
components. Creative use retains the module. Invalid, placed or copied stale
references reject the upgrade without consuming ingredients.

## Ownership and recovery

A bound cell carries a reference to data in its original world's registry.
It does not carry a second copy of its contents. Moving it to another world,
copying it or using an old reference cannot create a second stock owner. A
conflicting live block copy disables its backing for recovery.

Cells are unavailable while portable, unloaded or disconnected. Unknown drive
schemas remain unavailable and break into a labeled recovery casing containing
the original data. Placing that casing preserves the recovery state. A failed
cell removal keeps its exact reference in a labeled recovery item. Automatic
repair and administrative reconciliation are pending; keep these items and
back up the world rather than discarding them.

The canonical world registry now uses schema three. Existing schema-one/two worlds
migrate while preserving cell identities, contents and reservations. Unknown
or malformed registry data remains unavailable with its original data preserved;
keep `world/data/factorycore_devices.dat` for recovery. Network grant metadata
saves with this registry, but active routing and permission screens are still pending.

Orderly registry save/reload and block lifecycle behavior are tested. Abrupt
crash recovery across independently saved chunks/player inventory, actual chunk
unload/reload and multiplayer/client interaction remain unverified. This is not
the first playable release; see [implementation evidence](IMPLEMENTATION.md).

## Controllers, gateways and cables

Craft a Network Controller from four iron, four redstone and one glass. Placement
creates a private network owned by the server placer. Break and move the reference
to preserve its owner/grants; creative placement also consumes it. Command-created
controllers have no owner until a player explicitly uses one. Copies cannot
create a second owner; conflicting references need recovery.

Eight Cables use six iron and three redstone. A Gateway uses four iron, two
quartz, two redstone and one ender pearl. Its red output face is downstream and
its eye face is upstream; only those two faces connect. Use it to inspect the
directions. Controllers/cables connect on all six faces. The current textures
use vanilla assets. These blocks do not yet activate cell routing.
