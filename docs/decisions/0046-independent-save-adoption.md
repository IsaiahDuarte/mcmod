# ADR 0046 — Independent-save mismatch adoption for cells

Status: selected; two-JVM dedicated injection evidence for one save order (older
SavedData), plus native single-JVM evidence for the converse. Banks, buffers and
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
Energy-bank, buffer and structural-node mismatches still have only conflict repair. The
injection replaces the whole data file, which is coarser than a torn write. Corrupt
files are handled by the existing quarantine path.
