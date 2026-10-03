# ADR 0002 — Exact resources and conservative partial transfers

Status: accepted; core and representative handler contracts locally validated.
Real Rust ABI round-trip evidence remains pending with D04 in P1.

## Decision and contract

Items use whole item units; fluids use NeoForge millibuckets (1,000 mB per
bucket); energy uses whole NeoForge Energy units (FE). All mutable accounting
uses nonnegative signed 64-bit quantities with checked operations. A single
device's technical maximum is `Long.MAX_VALUE` total units. Infinite item tiers
remove gameplay capacity, not this separately reported representation limit.
Totals across distinct devices use `BigInteger`, never saturation or wrapping.
The ABI uses Wasm `i64` bit patterns in the nonnegative signed range; persistence
uses exact decimal strings or native longs, never floating-point JSON numbers.

The portable identity is resource kind, namespaced registry ID and immutable
canonical component bytes (maximum 65,536 bytes). Energy has one identity,
`neoforge:energy`, without metadata. Adapters serialize normalized count/amount
one using registry-aware platform codecs and canonical NBT: compound keys are
sorted recursively, lists preserve order, and primitive types remain distinct.
Identity rejects empty/unsupported resources instead of silently merging them.
Registration IDs and component bytes are bounded before becoming ledger keys.

Each backing store has one stable UUID, supplied by its owner. A view deduplicates
repeated references to the same store. Conflicting stores claiming the same UUID
are rejected; persistent duplicate-owner resolution belongs to D03. Reservations
reduce available stock without removing physical resources and belong to an
explicit owner UUID. They survive capacity upgrades. Loaded stock alone is
available to extraction. Mutations run on the ledger's constructing thread;
read snapshots are immutable and background planning never receives platform
objects. Per-device catalog and reservation quotas are administrative limits,
separate from gameplay capacity.

The shared transfer path first checks authorization and requested limits, then
preflights destination and owned staging space. Simulation is advisory. Actual
extraction is credited to owned staging before any insertion. Actual destination
acceptance debits staging; partial rejection stays owned. The bounded operation
never loops until an external handler accepts everything. Retry/cancellation
operate on that existing staging; uncertain handler mutations are quarantined,
never blindly repeated or refunded. Quantities returned outside the offered
range are handler faults. One endpoint call is limited to `Integer.MAX_VALUE`
for NeoForge handlers; bounded slot traversal further limits each request.

## Alternatives and consequences

Floating point loses exact large counts. Arbitrary-precision mutable counts add
allocation cost to every operation without removing finite memory/disk limits.
Signed longs match Wasm and Minecraft codecs; explicit technical ceilings and
arbitrary-precision aggregate queries satisfy R03 without wraparound. Registry
ID alone loses metadata. Raw unsorted NBT byte identity can distinguish equal
components by map insertion order; canonical encoding avoids that.

This decision does not settle durable saves, topology, user grants, recipes or
balance. P1 validates portable conservation and real platform handler contracts;
P2/P3 connect them to persistence and world capabilities. No complete A02/A03/A04
is claimed from core tests alone.

## Evidence and validation

Primary platform sources: [NeoForge capabilities](https://docs.neoforged.net/docs/1.21.1/inventories/capabilities/)
and the pinned NeoForge 21.1.252 source artifact (`FluidStack`, `IFluidHandler`,
`IItemHandler`, `IEnergyStorage`). Inspect exact pinned interfaces before writing
each adapter. Planned checks cover boundary/ABI round trips, component equality,
partial acceptance, competing reservations, offline storage and generated
conservation sequences with replay seeds. Observed results are recorded in
[the implementation plan](../IMPLEMENTATION.md).
