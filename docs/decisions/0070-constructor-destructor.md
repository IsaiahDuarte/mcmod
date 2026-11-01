# 0070 — Constructor and Destructor

Status: accepted 2026-11-01 (ergonomics plan Q34/Q35, gate QD9). Extends ADR 0066 (Importer and
Exporter).

## Context

AE2 (formation and annihilation planes) and Refined Storage (Constructor, Destructor) let a
network place and break blocks, drop and pick up items. QD9 asked for these:
- one block each;
- acting as a fake player owned by whoever applied it;
- firing the game's place and break events so protection mods can refuse;
- never voiding anything.

## Decision

- **Built on the port engine:**
  - The Constructor is an Exporter, and the Destructor an Importer (`PortBlock` with a
    `WorldAction`). Each keeps the port's screen, filter, redstone control, presets, schedules,
    authority and grant checks unchanged.
  - Their connector reaches the port's own buffer (`ConnectorEntity.providerPosition`) instead of
    a neighbour. The buffer is accepted as a provider only by the port that owns it
    (`WorldConnectors.probe`, `ProviderIdentity.providerPositions`), so no other connector can
    reach it.
- **The world action:** every 4 ticks, while a non-paused preset runs, the network is VALID and
  redstone allows it (IGNORE and PULSE always; POWERED and UNPOWERED follow the input), the port
  acts on the block in front. It acts as a `FakePlayer` whose profile is the running preset's
  principal.
  - **Constructor (1-item buffer):**
    - **Blocks:** a block item is placed through `ItemStack.useOn`, which fires
      `EntityPlaceEvent` and rolls back if it is cancelled. It is placed only into a replaceable
      block.
    - **Fireworks:** a firework rocket is launched.
    - **Anything else:** dropped as an item entity, only into open space.
    - **Refusals:** a refused or blocked action keeps the item, and the full buffer stops the
      Exporter taking more.
  - **Destructor (9-slot buffer):** it acts only with an empty buffer.
    - **Breaking:** a block is broken when:
      - it has no block entity and no fluid, and is breakable;
      - it is not a Factory Core block;
      - its drops (`Block.getDrops` with a netherite pickaxe) fit the buffer;
      - `BreakEvent` is not cancelled.
    - **Pickup:** in air, it picks up item entities there, as much as fits.
    - **Full storage:** the Importer drains the buffer into storage. While storage cannot take
      the drops, the buffer stays full and nothing more breaks.
- **Saved data:** the buffer is saved with the port (`FactoryCoreWorldBuffer`, schema 1) and
  dropped with it when broken.

## Not included (plan Q131)

- **Fluids:** placing fluid sources, and picking them up. A fluid buffer cannot be dropped when
  the block breaks, so it needs its own no-loss rule.
- **Fortune and Silk Touch upgrades.**
- **Blocks with block entities:** breaking them would spill their contents into the world.

## Alternatives

- **New port roles:** rejected. About 20 role checks in screens and menus would have to learn
  them, while the existing Exporter and Importer behaviour is exactly what these blocks need.
- **Acting with no buffer (simulate, then move straight from storage):** rejected. The transfer
  engine has no all-or-nothing world sink, and a buffer keeps every refusal lossless.
- **Real players' permissions only:** a fake player owned by the deployer was chosen (QD9).
  Protection mods see that player, and the network grants were already checked when the preset
  was applied.
