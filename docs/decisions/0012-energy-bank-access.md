# ADR 0012 — Finite energy banks and identity-based rate accounting

Status: accepted; selected before implementation. Portable core locally verified;
physical integration remains pending P2 work supporting A04/A13/A14.

## Decision

Use the existing canonical DeviceRegistry ENERGY ledger, ownership generations,
current routing predicate and reservations. Balance version one from ADR 0006
selects Starter/Expanded/Advanced capacities 100,000/1,000,000/10,000,000 FE and
separate input/output ceilings 1,000/4,000/16,000 FE per tick. No infinite energy.
Expansion and Advanced upgrades must preserve backing identity, contents and
claims; unsupported/downgrade requests reject before mutation.

Keep transient rate budgets in one owner-thread access service per world, keyed
by backing UUID rather than block entity, side or lease generation. Moving/rebinding
or visiting multiple faces cannot reset a bank's already spent tick budget.
A trusted monotonic nonnegative tick supplier defines the server epoch; backward
clock movement fails before mutation. All banks share this source. World restart
creates new transient budgets, while canonical energy/claims remain offline until
revalidation. Rate entries are bounded by the existing 1,024 device identities.

Simulation reads current loaded stock, capacity, reservations and remaining rate
without spending budget or dirtying ownership. It is advisory: global metadata
admission and later mutations may still reject actual acceptance. Actual operations
use canonical insert/extract and charge only accepted energy. Partial amounts keep
precise reasons, including an explicit RATE_LIMIT when rate alone limits the call.
No fake refund, energy creation, guest endpoint or external transfer atomicity is
introduced by this service.

## Physical integration and alternatives

The physical bank will expose bounded NeoForge energy ports on all six faces and
validate its current placed lease on every call. Direct foreign pipes are physical
access outside script grants under R04; network/player/program operations must
also use current shared authorization and scheduling. Root loss still makes the
bank offline. Block/item references carry schema-versioned leases only; canonical
stock must not be copied into block NBT or loot.

Per-face budgets multiply throughput. Per-entity budgets reset on movement.
Independent block energy copies break resource ownership. A shared per-identity
service avoids these errors and preserves the existing canonical save boundary.

Required evidence: partial/full admission, combined sides/calls, simulation,
reservations, unload/reload and stale leases, same-tick movement, tier changes,
clock failure and generated conservation. Real capability/block/upgrade/recovery
GameTests, chunk/full-world restart, client/progression and representative
performance remain required before release. Portable access alone does not finish
an energy bank or A04/A13/A14. Physical integration follows this prerequisite.
