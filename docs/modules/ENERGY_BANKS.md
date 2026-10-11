# Finite energy-bank accounting

Implemented portable P2 prerequisite for A04/A13/A14. Physical bank blocks,
NeoForge capabilities, recipes, upgrades and player interaction are still pending.
This service does not add a playable bank or finish energy transfer acceptance.
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

Six EnergyBankAccessTest cases cover combined calls, separate directions,
simulation, full/partial capacity, reservations, offline routing, clock reversal,
stale/moved leases, wrong resources/malformed requests, sequential finite tiers and exact offline state restoration.
A 4,000-operation generated sequence uses seed `0xE0B001` and checks conservation
and both tick ceilings after every operation. Restoration here uses the canonical
in-memory snapshot; it is not a physical bank/disk/world restart experiment.

Required next integration includes actual bank placement, consumed portable leases,
creative/copy safety, capability calls on multiple faces, upgrades, malformed/future
local-data recovery, recipes/models and chunk/full-world restart checks. Physical
foreign pipes remain outside script grants, but root availability still governs
stock use. Player/program endpoints, powered progression and performance remain
pending in the full release goal.
