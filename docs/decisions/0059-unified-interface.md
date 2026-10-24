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
  share one tier ladder (ADR 0054). A switch keeps the item-level tier only when the
  new kind can carry it; otherwise the switch is refused until the upgrades are
  removed. In practice all three kinds accept the same tiers.
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
