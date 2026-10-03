# Physical storage cells and drives

Initial P2 implementation supporting A02/A03/A13/A14 in part, with
[balance version one](../decisions/0006-initial-balance.md) and
[player instructions](../PLAYER_GUIDE.md). No network root, terminal or guest
endpoint is implemented in this slice. Physical installation/removal is outside
script grants under R04; ledger routing remains offline until future validated
topology/authorization integration.

## Components and lifecycle

`CellTier` is portable: resource kind, progression level, exact capacity and
infinite flag. Capacities use individual item units or mB. `upgrade(level)`
accepts only the next level within the same kind; all skips, downgrades, exhausted
tiers and infinite fluid upgrades throw before a change. Cell items stack to one.

`CellHandleData.read(stack)` returns null only for an absent lease field. Malformed
or unknown data throws; it never means a fresh empty cell. `write(stack, handle)`
merges a version-one `FactoryCoreCell` compound into vanilla `CUSTOM_DATA`:
integer `Schema=1`, UUID `World`, UUID `Backing`, positive long `Generation`.
It preserves unrelated components. Quantities/claims remain solely in
[DeviceSavedData](DEVICE_OWNERSHIP.md). The fixed lease SNBT protects the schema.

New items are empty capacity prototypes. First installation allocates an empty
backing, then attaches its lease to the exact dimension/block/slot. A bound item
must match the authoritative kind/capacity/infinite definition and have a portable
current lease. Attachment consumes the portable generation. Reload accepts only
the same current placed lease and holder; a portable lease in chunk data rejects
before attachment can consume it. Another live location quarantines the owner.

`CellOwnership` keeps these server-thread transitions shared by drive/item
actions. An upgrade first validates its current portable lease and resizes the
canonical backing, then produces the next tier item with preserved components.
The player's action replaces the offhand stack and consumes one main-hand module
after success. Crafting recipes produce empty starter prototypes and modules,
never consume populated cells. Current grants and shared routing/resource
scheduling must be added before exposing stock mutation to players/programs.

## Drive adapter contract

`StorageDriveEntity` is confined to the Minecraft server thread for mutations.
Its four slots contain defensive item references, not stock. `cell(slot)` returns
a copy; slots are 0 through 3. `installCell(singleCell)` uses the first empty slot,
returns its index and throws on full/unavailable/invalid ownership. It does not
consume the caller's input; the block interaction shrinks the original exactly
once after success, including creative mode. These are trusted platform methods,
not public client/guest commands.

`removeCell(slot)` detaches the exact current holder and returns its new portable
lease, clearing the slot after success. Empty slots return `ItemStack.EMPTY`.
`onLoad` rebinds references without reactivating routing. `setRemoved` marks known
references unavailable and preserves holder generations; it does not eject items.
Breaking/replacement calls `ejectForBreak`: healthy cells become portable; failed
removals deactivate and retain the exact lease in a labeled inert recovery item.
No copied ledger or speculative refund is manufactured.

Schema one stores integer `CellSchema=1` and compound list `CellSlots`, maximum
four entries: integer `Slot`, compound `Stack` using Minecraft's item codec.
Repeated slots, wrong types/counts, missing leases and incompatible schemas fail
closed. Unknown/malformed chunk data is preserved in `RejectedCellData`, separate
from Minecraft's block-entity ID/position/component metadata. Subsequent saves
retain that envelope. Breaking emits one recovery drive item with a
`FactoryCoreDriveRecovery` compound containing the rejected original. Its normal
casing loot is suppressed to avoid a second casing. Placing the recovery item
retains unrecognized data; it does not silently migrate/reset it. Recovery items
with a wrong-typed envelope are also preserved unavailable. Upstream Minecraft
NBT read/allocation limits still apply; this parser does not claim to bound an
already decoded malformed outer tag.

Registry/lease failures become action messages and leave inputs unconsumed.
Diagnostics disable drive actions; ownership reconciliation remains a later
explicit administrative operation. There is no migration from a released prior
drive format; version zero/future schemas retain their source for recovery.

Example: install a Starter Item Cell, obtain its exact slot lease, then remove
it. The old placed generation is stale; the returned current portable handle
keeps contents/claims but cannot serve transfers. Apply Expansion in the offhand,
then install into another drive. The backing UUID remains the same, the capacity
becomes 65,536 item units and revalidation must precede routing.

## Verification boundaries

Required headless Minecraft GameTests cover actual block placement/break drops,
four-slot limits, malformed input, player insertion/sneak removal, module-use
consumption, claims/components, finite fluid tiers, stale/foreign references,
copied block conflicts, block-entity remove/save/reload and unknown-schema
recovery. Recipe checks use the live recipe/advancement managers and compare
vanilla ingredients and exact costs with balance version one. Tests activate
registry availability directly only to seed/inspect ownership fixtures; this
does not prove a valid player-accessible network.

Block-entity lifecycle calls are not an actual chunk-unload experiment. Canonical
registry disk tests separately verify orderly nonempty SavedData recovery. Full
world restart with installed drives/player leases, interruption at independent
chunk/player saves, client visuals, multiplayer, survival progression and
administrative repair remain pending. The single registry prevents a second
ledger owner; abrupt save-order loss can strand references and is not claimed
as solved. Full A02/A03/A13/A14 and D03/D06 remain incomplete.
