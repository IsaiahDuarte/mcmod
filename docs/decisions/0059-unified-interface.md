# 0059 — Unified Interface block

Status: accepted 2026-10-24 (UX plan U11, gates UD1 and UD7).

## Context

Players met three look-alike blocks, the Storage Connector, Machine Interface and
Buffered Interface, that all sit against something and move resources. The
differences between them were only learnable from tooltips (UX_PLAN F1). UD1 decided
to merge them into one **Interface**. UD7 decided there are no save migrations,
aliases or legacy fallbacks.

The three devices are not shallow. Each owns a canonical lease kind
(`NodeRegistry.Kind` CONNECTOR / MACHINE_INTERFACE / BUFFERED_INTERFACE) and a
persisted record with its own schema. Each has its own transfer and staging recovery,
operator repair and adoption audits, and a screen. More than 30 GameTest classes
cover these rules.

## Decision

One block and one item, `factorycore:interface`, whose `kind` block-state property
(`storage`, `machine`, `buffer`) selects which existing device implementation backs
it:

| Mode | Backing entity (unchanged) | Player meaning |
| --- | --- | --- |
| Storage | `ConnectorEntity` | The container joins network storage (priority, access, window, rate). |
| Machine | `MachineInterfaceEntity` | A labelled machine port with Import/Export/Stock presets. |
| Buffer | `BufferedInterfaceEntity` | Its own nine-slot buffer for hoppers, with Import/Export/Stock. |

- **Placement.** A fresh item placed with its socket against a block that has an item,
  fluid or energy capability becomes Storage for a supported storage container
  (vanilla containers and identified backing providers). Otherwise it becomes Machine.
  Placed against nothing usable, it becomes Buffer. An item that carries a lease keeps
  the mode of that lease, so moving an Interface never changes its kind.
- **Switching mode.** The Interface screens share a mode row (Storage / Machine /
  Buffer). A switch is refused unless the device is idle:
  - no unresolved staged transfer, owned staging or in-flight operation for its
    lease;
  - an empty buffer;
  - no active preset deployment.

  An idle switch releases the old lease through the existing removal path, then
  places the new kind through that kind's existing `place` path, under the switching
  player's current EDIT authority. Nothing is copied between kinds. The refusal names
  the reason, for example "Empty the buffer first".
- **Upgrades.** Throughput and Overdrive installation stays per kind, which already
  share one tier ladder (ADR 0054). Upgrades cannot be removed, so an upgraded
  Interface refuses to switch rather than lose its upgrade (implemented in U11b).
- **Removed.** The `storage_connector`, `machine_interface` and `buffered_interface`
  blocks and items are deleted, along with their recipes and loot tables, with no
  aliases or converters (UD7). Worlds containing them are unsupported. The three
  block-entity types stay as internal implementation types bound to the new block, so
  their persisted schemas and recovery tools are untouched.

## Alternatives

- **One entity owning all three subsystems.** This would be the cleanest model, but
  it means rewriting three persistence schemas, the lease kinds and every recovery
  and audit path at once, for no player-visible gain over this design. Rejected for
  risk. It can follow later behind the same block.
- **Keep three blocks with clearer names.** UD1 already rejected this.
- **Switch mode in place without releasing the lease.** Lease kinds are part of the
  canonical identity and ownership model. Reinterpreting one kind as another would
  bypass the ownership invariants that the recovery tools rely on. Rejected.

## Consequences

- SPEC R02's role table lists one Interface role with three modes. The
  responsibilities are unchanged.
- Tests and fixtures that placed the old blocks move to the new block with an
  explicit `kind`. No legacy fixture is kept.
- `ConnectorBlock` and `BufferedInterfaceBlock` merge into one `InterfaceBlock`. The
  offhand controls stay until U16 removes them.
- Mode switching is new behavior. Its idle check is the safety boundary: a refused
  switch changes nothing, and an accepted one is exactly a break followed by a fresh
  placement.

## Implementation notes — U11b (2026-10-24)

Switching needs a lease retirement path that did not exist before, because canonical
node records were never deleted. Without one, every switch would leak a record
against the node limit (4096) and the buffer limit (256). The new operations are:

- `NodeRegistry.retire`: removes only a detached, current connector,
  machine-interface or buffer lease. Controllers and gateways throw.
- `BufferRegistry.retire`: removes only an unbound, empty buffer and its deployment.
- `MachinePresets.forget`: removes the deployment and the configuration fence. A
  stale fence would otherwise fail the load-time cross-check.

`InterfaceSwitch` runs every check (permission, idle, empty, no upgrade) before
detaching anything. It then calls `portableForBreak`, retires the records, replaces
the block entity, and places the new kind with a fresh item. GameTests cover each
refusal, six consecutive switches leaving exactly one lease with all old ids
retired, and the screen button path.

## Amendment — Signal mode (parity C1, 2026-10-25)

PD1 chose a fourth Interface mode over a separate block. `InterfaceKind.SIGNAL` uses the
Buffer's full-block shape, wires on all six faces and emits the same weak power on every
side. It holds no lease, contents or upgrades, so switching into or out of it needs only
EDIT and the device's reach; the new mode is placed fresh and a Signal switched back to
starts unset.

- **Authority.** The signal reads storage under the grants of the player who last applied
  its settings (EDIT to apply). Losing VIEW turns it off with the reason "denied"; it
  never reads under a wider identity.
- **Bounded work.** `WorldSignals` evaluates each tracked signal at most once per 20 ticks,
  at most 4,096 signals, through the scheduler-backed paged catalog (at most 640 pages).
  Busy or over-limit evaluation keeps the last output and says why.
- **Neighbor updates.** A strength change notifies only non-network neighbors. Network
  blocks treat neighbor updates as topology changes and read no redstone; notifying them
  rebuilt the network and briefly refused permissions (regression-tested).
- **Scope.** One watched resource rather than a filter; a filter condition can come later
  without a schema break (`FactoryCoreSignal` Schema 1).

Alternatives: a separate Level Emitter block (rejected by PD1: another item and recipe for
one behavior); evaluating every tick (unbounded on large networks).

