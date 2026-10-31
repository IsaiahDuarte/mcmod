# 0066 — Importer and Exporter

Status: accepted 2026-10-26 (playtest plan H6, gate HD2). Amends ADR 0019 (one port per
backing) for these two blocks only.

## Context

In the first survival playtest the player never found import or export (finding K4). It lived
under Interface → Machine mode (chosen silently from what the Interface was placed against) →
Presets tab → a **Paused** button that cycled to Import. AE2 and Refined Storage name the
blocks Import Bus/Importer and Export Bus/Exporter, so the item says what it does. HD2 chose
two named blocks that attach to a machine like a hopper.

The playtest's auto smelter also needs several attachments on one furnace: ore in at the top,
fuel at the side, ingots out at the bottom. ADR 0019 allowed only one port per container
backing (`WorldConnectors` refused the rest as `OVERLAP`) and left coordinating several ports on
one backing as future work.

## Decision

- **Blocks.** `factorycore:importer` and `factorycore:exporter` are `PortBlock`s with a fixed
  role. The socket faces the block they are placed against, and cables connect on the other five
  faces. Their block entity is the existing Machine-port device (`MachineInterfaceEntity`,
  lease kind `MACHINE_INTERFACE`), so leases, persistence, rates, staging, recovery, labels, the
  Label Tool, redstone control and Throughput/Overdrive upgrades are unchanged and shared.
  Breaking one drops the same block's item with its lease, like an Interface.
- **Roles.** An Importer runs only Import (machine → storage). An Exporter runs only Export
  (storage → machine) or Stock ("Keep N"). Both may be paused. The entity refuses any other
  preset (`deployPreset` throws), and the menu reports it as denied.
- **Defaults (as U6).** Once its network is valid, a placed port binds the resource its machine
  offers on the touching face: items, else fluids, else energy. An Importer then starts importing
  **everything** under its placer's current grants. An Exporter stays paused until it has a
  filter, because an Exporter must never empty all of storage into a machine; Apply without a
  filter answers "Choose what to send first". A placer without the grants, or one who left,
  leaves it paused.
- **Screen.** A 176 px panel-kit screen (ADR 0064) on the same menu, through two menu types
  (`importer_settings`, `exporter_settings`). It has:
  - a resource choice (rebinds and closes; an Importer keeps importing everything of the new
    resource);
  - the 16 ghost filter slots, where an empty Importer filter means everything;
  - **Only these / All but these**;
  - **Keep** (Exporter: blank sends everything that matches; a number keeps that many);
  - redstone;
  - Return, Stop and Apply in the footer.

  Two preset commands were added: `DEPLOY_AS` (apply as a given mode) and `FILTER_MODE`.
- **One backing per face.** For Importers and Exporters only, the backing is derived from the
  container group and the touched face (`factorycore:face-port:v1:<group>:<face>`). Ports on
  different faces of one container no longer overlap. Each has its own staging, unresolved-transfer
  fence and recovery identity. The derivation is deterministic, so a saved transfer recovers
  after a reload. Unchanged:
  - Storage-mode Interfaces and Machine-mode Interfaces keep one owner per group, so a container
    that is network storage stays exclusive. An Importer on it is refused as `OVERLAP` and
    nothing is counted twice. Existing worlds' Machine-port backings are also unchanged.
  - Rate budgets stay per group, so several ports on one machine share its rate.
  - Crafting-job leases are by machine position, so they still cover every face.
  - Processing admission now also checks the six face backings, so a port's unresolved transfer
    still blocks a Crafter dispatch into that machine.

## Alternatives

- **Import/Export as Interface modes with clearer labels.** HD2 rejected this. The item name is
  what players look for.
- **One port per machine with a side override per preset.** This keeps ADR 0019's exclusivity,
  but a smelter then needs one block plus three face settings, which is the mental work the
  playtest asked to remove. Programs already offer it (`side`) for players who want it.
- **Per-face backings for every Machine port.** This would change the backing identity of
  existing Machine-mode Interfaces and orphan their saved transfer records. H7 removes Machine
  mode anyway.

## Consequences

- Two blocks, two items, two Starter recipes (copper around a hopper; the redstone marks the
  output side), generated chevron textures (Importer "V", Exporter "Λ"), and lang.
- SPEC R02 lists the Importer and Exporter roles.
- Programs can target a labelled Importer or Exporter like any Machine port. H5 replaces this
  with labels on the machine itself.
- `PortGameTests` covers:
  - a placed Importer emptying a chest of three different items exactly;
  - an Exporter that waits for a filter, refuses Import, then keeps a chest at exactly 8;
  - a guest without Deploy left paused, and an Importer refusing Export;
  - an Importer on a storage chest refused as an overlap;
  - three ports making a furnace an auto smelter (raw iron in, coal kept stocked, ingots back
    in storage, coal only burned or stocked).

## Amendment — rule status on the Exporter screen (2026-10-30, ergonomics plan Q100)

While an Exporter's screen is open, each exact or registry-ID rule shows whether the network holds
some (as the viewer would see it for a job preview), has none but a visible pattern makes it, or
has none. Tag rules show nothing, because a tag can match many keys and the answer would mislead.
The status is computed at most once a second, or when the filter changes, with one stock lookup
per rule (at most 16). It is display only; export behavior is unchanged.
