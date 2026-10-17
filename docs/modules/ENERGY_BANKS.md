# Finite energy-bank accounting

Implemented P2 bank integration supporting A04/A13/A14: physical tier blocks,
NeoForge capabilities, recipe, portable upgrades and player inspection. Complete
energy routing, powered progression and release verification remain unfinished.
[ADR 0012](../decisions/0012-energy-bank-access.md) records the design; capacities
and rates follow [balance version one](../decisions/0006-initial-balance.md).

## Tiers and ownership

`EnergyBankTier` has STARTER/EXPANDED/ADVANCED: 100,000/1,000,000/10,000,000 FE
capacity and 1,000/4,000/16,000 FE/tick independently in each direction.
`upgrade(targetLevel)` accepts only the next level (0/1/2); it throws for skips,
downgrades or an infinite level. It returns the target definition, not a new
ledger. Platform upgrades must resize the same canonical portable backing before
replacing the physical tier item. Tier selection cannot manufacture energy.

All energy lives in DeviceRegistry's ENERGY ledger using ResourceKey.ENERGY.
Owner generations, exact FE quantities, loaded routing and reservations remain
subject to [device ownership](DEVICE_OWNERSHIP.md). The access layer has no
independent resource copy, permission grants or serialization format.

## Access contract

Construct one `EnergyBankAccess(registry, tickSupplier)` on the registry owner
thread for the world lifecycle. The supplier is trusted, bounded, nonnegative and
monotonic; callers cannot supply individual tick values. Negative or backwards
ticks throw before stock mutation. Reads and operations check thread ownership.

`receive(handle, tier, amountFE, simulate)` and `extract(...)` use nonnegative
signed-long quantities and return OperationResult. Invalid/stale/conflicting/foreign
leases return DENIED; wrong resource kind returns WRONG_RESOURCE. Tier capacity
must match the canonical finite ENERGY definition or the call throws. Offline
routing returns OFFLINE, preserving exact totals and claims. Extraction here is
unreserved physical access; it cannot consume another owner's reservations.
Client/guest/crafting endpoints must add current shared authorization/scheduling
and their owned-claim contract before using this layer.

Input and output spending are separate. Budgets are keyed by backing UUID, shared
across all callers/faces and preserved across movement and lease generations.
An upgrade changes the ceiling without erasing spending earlier in the same tick.
A new tick resets that identity lazily; no idle scan or background task exists.
There are at most 1,024 budget entries because only current identities from the
bounded registry can enter the map. State is transient: a new world service starts
new rate budgets, while restored ledgers still start offline.

Simulation reports the amount currently permitted by capacity/available stock
and remaining rate, without changing resources, claims, rate spending or persistent
ownership. Observing time still advances the monotonic clock guard. Simulation is
advisory: metadata admission and later world changes may deny actual acceptance.
Actual calls use canonical insert/extract and charge only the accepted amount;
rejected energy remains the caller's property.

Results report OK when the full request fits. Partial/zero requests report FULL,
SHORTAGE or RESERVED when capacity/stock constrains them, or RATE_LIMIT when the
rate alone constrains them. Canonical technical-admission failures retain their
existing reasons. RATE_LIMIT is appended to OperationResult.Reason; no save or
wire ABI encodes this enum. There is no implicit retry, refund or rollback claim.

Trusted fake-world example (production must validate actual world membership):

```java
var tier = EnergyBankTier.STARTER;
var portable = registry.create(ResourceKind.ENERGY, tier.capacity(), false, 8, 64).handle();
var handle = registry.attach(portable, location).handle();
registry.availability(handle, location, true);
var banks = new EnergyBankAccess(registry, () -> currentServerTick);
var accepted = banks.receive(handle, tier, 1500, false);
// 1000 FE accepted, RATE_LIMIT for the remainder; a second face shares this budget.
```

## Evidence and remaining integration

`EnergyBankOwnership` supplies the owner-thread transitions for the upcoming
physical adapter. `place(portable, tier, location)` accepts a null reference only
for a new empty bank, checks location admission before creation, and consumes a
portable lease by returning its next generation. `reload(placed, tier, location)`
accepts only placed references; it never activates routing. A copied placed lease
at a different location quarantines the canonical identity. `remove` returns the
next offline portable generation. `upgrade(portable, tier, targetLevel)` requires
sequential portable upgrades and resizes the same backing; null represents an
uninitialized empty item. Every supplied reference must match ENERGY kind, finite
capacity and tier. Expected rejection throws before the adapter consumes an item
or module; adapters must preserve rejected references for recovery. These methods
do not supply player permissions, scheduling, item serialization or world hooks.

Four EnergyBankOwnershipTest cases cover populated movement/upgrades with claims,
shared same-tick rate spending, placed-copy quarantine, stale/foreign/tier/occupied
rejection, and offline canonical snapshot reload. This is portable transition
evidence, not physical block or full-world restart evidence.

Six EnergyBankAccessTest cases cover combined calls, separate directions,
simulation, full/partial capacity, reservations, offline routing, clock reversal,
stale/moved leases, wrong resources/malformed requests, sequential finite tiers and exact offline state restoration.
A 4,000-operation generated sequence uses seed `0xE0B001` and checks conservation
and both tick ceilings after every operation. Restoration here uses the canonical
in-memory snapshot; it is not a physical bank/disk/world restart experiment.

Seven EnergyBankGameTests cover actual creative placement and break drops, all-face
capability rates/simulation/claims, immediate disconnection and reconnection,
physical break/upgrade/movement with stale-capability rejection,
populated module upgrades/components, lifecycle reload and copied-block quarantine,
future/malformed raw-data preservation, and loaded vanilla recipe/unlock costs.
Two EnergyBankHandleDataTest cases use the committed schema-one SNBT fixture and
reject future/wrong-type/extra-field input without rewriting it.

## Physical integration and persistence

`EnergyBankContent` registers the three blocks, single-stack items, shared block
entity type and all-face `Capabilities.EnergyStorage.BLOCK`. Item placement consumes
the lease even in creative. Break/replacement emits one casing via `onRemove`;
normal loot is empty, preventing duplicate bank drops. Stock remains exclusively
in canonical SavedData (now schema four with transfer records); bank ledger and
lease schemas are unchanged.
Local `FactoryCoreEnergyBank` schema one contains only Schema/World/Backing/Generation.
Unknown or malformed tags are retained exactly in the block and recovery item.
No automatic reconciliation or reset is offered.

One lazily created `EnergyBankAccess` belongs to each running server's canonical
SavedData lifecycle, using unsigned server tick count. It is not per dimension,
face or entity. Tick wrap after 2^32 ticks fails closed through the monotonic guard;
orderly server restart resets transient budgets. All world operations stay on the
server thread. Discovery recognizes bank storage vertices; budgeted region
publication activates their leases. Removal/unload deactivates them and current
routing denies access immediately on rewiring. No new per-tick bank scan exists.

Every capability call validates the live entity, tier and canonical location.
Unavailable/malformed/stale entities accept/extract zero. Negative FE requests
throw. `getEnergyStored` reports observable canonical total even when routing is
offline; unavailable recovery data reports zero with inspection diagnostics.
Capacities fit the NeoForge integer API; simulation spends neither stock nor rate.
Reservations constrain extraction. Foreign pipes remain physical access; future
player/program endpoints must also use shared authorization and scheduling.

Right-click inspection and tooltips use translation keys for quantities/rates,
network states and recovery next actions. Empty command-created banks initialize
only on explicit player inspection. Bank models use native JSON geometry with
inset panels, lightning symbols and one/two/three tier bars; see the asset inventory.
Client appearance/interaction, actual chunk/full-world restart, independent-save
interruption, multiplayer and representative performance remain unverified. Physical
foreign pipes remain outside script grants, but root availability still governs
stock use. Player/program endpoints, powered progression and performance remain
pending in the full release goal.


## Orderly restart evidence added 2026-10-17

The [separate-JVM world experiment](../evidence/world-restart-v1-2026-10-17.md)
now verifies a small nonempty integrated-server save/reopen: complete canonical
state, physical/provider identity, mixed fluid stock and a retained400mB hold,
500FE and resumed item Stock without redeployment. This narrows earlier restart
limitations for that fixture only. Independent save-order injection, forced chunk
unload, interrupted calls, dedicated-server/multiplayer and full acceptance remain open.
