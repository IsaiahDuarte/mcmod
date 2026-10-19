# ADR 0046 — Independent-save mismatch adoption for storage and structural holders

Status: selected; two-JVM dedicated injection evidence for both save orders (older
SavedData, older chunks) for cells; native single-JVM coverage for other holder kinds.
Port adoption has targeted regression coverage; full two-JVM port coverage remains pending.

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
Every canonical holder kind (cells, banks, controllers/gateways, buffered interfaces,
connectors and Machine Interfaces) has an operator adoption path. The
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

FE, claims and rate spending are unchanged. Later sections extend the same mechanism
to other holder kinds.


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
positions.

## Buffered interfaces — 2026-10-18

The command `factorycore recovery buffer adopt <id> <generation> <x y z>` uses
`BufferRegistry.adopt` on the same rule.

- **Unchanged:** the canonical slots and the accepted deployment, which stay keyed by the
  unchanged backing.
- **Dropped:** live bindings and transfer bindings. Like conflict repair, automation must
  be reapplied with current permissions.
- **Evidence:** schema 6 (`HolderKind=BUFFERED_INTERFACE_ADOPTION`), with slots,
  deployment, `Target` and the native holder.

## Connectors and Machine Interfaces — 2026-10-18

`factorycore recovery port adopt <id> <generation> <x y z>` reconciles a stale
connector or Machine Interface in the command source's dimension. Both holders
must be loaded; the old canonical position must contain no reference to the ID.
The target must have the same world, ID and physical kind, a different lease
generation, readable settings and no persisted provider fault. Portable leases,
same-position adoption, overflow and unresolved operations naming the node are
refused. Unscoped uncertain foreign operations also refuse under ADR 0047.

Before mutation, audit schema 7 (`FOREIGN_PORT_ADOPTION`) records the canonical
node, current canonical deployment/fence, target position and full stale physical
holder. A Machine Interface's new configuration exceeds both saved fences and
its old deployment is paused. Original deployer authority is never adopted.
Settings, label and face survive; old provider caches and handles are invalidated.
No foreign capability is read and no transfer or provider resource is replayed.
Redeployment requires current player permissions. The world schema is unchanged.
