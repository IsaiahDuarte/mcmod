# ADR 0046 — Independent-save mismatch adoption for cells, energy banks and network nodes

Status: selected; two-JVM dedicated injection evidence for both save orders (older
SavedData, older chunks) for cells; native single-JVM evidence for banks. Banks, buffers and
structural nodes are not covered.

## Context

Canonical storage (`factorycore_devices.dat`) and chunk block entities are saved
independently by Minecraft. A hard interruption between the two can leave a drive slot
holding a reference whose generation differs from the canonical lease. ADR 0023 made such
mismatches inert and gave operators no path forward.

## Decision

`factorycore recovery cell adopt <backing> <canonical generation> <drive x y z> <slot>`
(permission level 2, one audited repair per 20 ticks per server, in the source's
dimension) moves the single canonical lease to that drive slot. It does so only when all
of these hold:

- The canonical generation matches. The lease may or may not be conflicted, but it must be
  placed, never portable.
- No transfer for the backing is unresolved.
- The old canonical holder's chunk is loaded, and if a drive is there, it holds no
  reference to the backing in any slot.
- The target drive is loaded and readable, and its slot holds exactly one cell of the same
  world, backing and tier with a **different** generation.

`DeviceRegistry.adopt` releases the old placement, consumes one generation, clears
conflict and leaves routing offline until network revalidation. The physical reference is
rewritten to the new generation, so every other copy becomes stale. Quantities and claims
are never touched; there is one ledger. Before mutation, binary evidence is written as
cell audit **schema 3** (`HolderKind=CELL_ADOPTION`, with the before-state lease,
`Target` location and the adopted `Stack`) in `world/factorycore-device-recovery`.

## Alternatives

- **Automatic adoption on load:** it cannot tell a crash survivor from a duplicated block
  (both look like a mismatched reference), so an operator decides with evidence.
- **Choosing the newest generation:** wrong for the converse order, where the chunk is
  older.
- **Refunding or merging contents:** violates single-ledger ownership.

## Consequences

Mismatches remain inert until an operator acts. They are never duplicated or lost.
Buffered-interface mismatches still have only conflict repair (cells, banks and controllers/gateways are adoptable). The
injection replaces the whole data file, which is coarser than a torn write. Corrupt
files are handled by the existing quarantine path.


## Energy banks — 2026-10-18

The command `factorycore recovery bank adopt <backing> <generation> <bank x y z>` applies
the same rule to finite energy banks.

- **Old position:** must be loaded, and must not hold a bank that references the backing.
- **Target:** the loaded bank must hold a reference with the same world and backing, a
  different generation, and a matching finite tier.
- **Transfers:** none may be unresolved.
- **Evidence:** bank audit **schema 4** (`HolderKind=ENERGY_BANK_ADOPTION`) records the
  before-state lease, `Target`, and the bank's native block state and block entity.

FE, claims and rate spending are unchanged. Buffers and structural nodes still have only
conflict repair.


## Controllers and gateways — 2026-10-18

The command `factorycore recovery node adopt <id> <generation> <x y z>` uses
`NodeRegistry.adopt` on the same rule, for controllers and gateways.

- **Old position:** must be loaded, and must not hold a structural node that references
  the ID.
- **Target:** the holder must have the same world, ID and kind, with a different
  generation.
- **Transfers:** none may be unresolved, for transfers or devices.
- **Evidence:** schema 5 (`HolderKind=NETWORK_NODE_ADOPTION`), including controller
  grants.

Grants, ownership and gateway policy are unchanged. Topology rebuilds from both
positions. Buffered interfaces, whose canonical slots and deployments are bound to their
position, still have only conflict repair.
