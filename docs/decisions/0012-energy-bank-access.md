# ADR 0012 — Finite energy banks and identity-based rate accounting

Status: accepted; selected before implementation. Portable core locally verified;
physical integration implemented with local headless evidence supporting
A04/A13/A14; real chunks/full restart, client and complete release checks pending.

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

The physical bank exposes bounded NeoForge energy ports on all six faces and
validate its current placed lease on every call. Direct foreign pipes are physical
access outside script grants under R04; network/player/program operations must
also use current shared authorization and scheduling. Root loss still makes the
bank offline. Block/item references carry schema-versioned leases only; canonical
stock must not be copied into block NBT or loot.

Per-face budgets multiply throughput. Per-entity budgets reset on movement.
Independent block energy copies break resource ownership. A shared per-identity
service avoids these errors and preserves the existing canonical save boundary.

The portable `EnergyBankOwnership` transition service validates finite tier
identity before place/reload/remove/upgrade. Reload cannot consume a portable
reference; only placement advances it. Conflicting placed copies use the existing
registry quarantine transition. Movement and sequential upgrades preserve the
same backing and claims, so the access service retains its tick spending. Physical
items must replace/consume references only after a successful transition.

Required evidence: partial/full admission, combined sides/calls, simulation,
reservations, unload/reload and stale leases, same-tick movement, tier changes,
clock failure and generated conservation. Real capability/block/upgrade/recovery
GameTests, chunk/full-world restart, client/progression and representative
performance remain required before release. Portable access alone does not finish
an energy bank or A04/A13/A14. Physical integration follows this prerequisite.

## Amendment — screen and in-place upgrade (2026-10-31, ergonomics plan Q20)

A placed bank can be upgraded without breaking it. `EnergyBankEntity.upgradeInPlace` detaches the
lease (`EnergyBankOwnership.remove`), resizes it as the portable path does
(`EnergyBankOwnership.upgrade`, which still accepts only portable leases) and places it in the
next tier's block. Identity, stock, claims and generation are kept, as for an offhand upgrade. If
the resize is refused, the lease is placed back at the same tier. The replaced entity drops its
handle first, so its removal does not mark the moved lease unavailable. On a valid network the
upgrade needs Edit at the bank. Off a network it needs no right, as breaking the bank needs none.
A skipped level or a downgrade is refused before anything changes.

The bank's screen (`EnergyBankMenu`) shows charge, tier and network state, and the FE in and out
during the last complete tick. `EnergyBankAccess.lastTick` reads that from the per-backing
budgets it already keeps, plus the previous tick's budget, bounded by the number of banks. The
module slot is a one-item container. It upgrades on insertion, returns a refused module when the
screen closes, and follows the position, so it stays open while the block is replaced.
Alternative: average flow over a second (rejected: needs a per-bank history the ledger does not
keep; one tick matches the per-tick limits shown beside it).
