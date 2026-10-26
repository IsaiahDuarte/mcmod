# H7 — Interface Machine mode removed

Scope: playtest plan H7 (gate HD4; amendments to [ADR 0059](../decisions/0059-unified-interface.md)
and SPEC R02).

Implemented:

- `InterfaceKind.MACHINE` is gone. An Interface against anything that is not a storage container
  is a Buffer. The mode row shows Storage, Buffer and Signal. An old Machine item reads as Storage
  and is refused on placement (UD7: older worlds unsupported).
- The `machine_settings` menu type and `MachineSettingsScreen` are deleted, along with the
  Interface's `kind=machine` blockstate variants, the `machine_interface` block model and the
  `interface_panel` texture. The `machine_interface` entity type and preset engine stay, behind
  Importer, Exporter and cable port. JEI ghost drops now target the Importer/Exporter screen.
- Player text: Label Tool help and copy messages, the Interface tooltip, guide page 6 (Importer,
  Exporter, Label Tool), the Crafter's `machine_changed` reason, the player guide and SPEC R02.

Test changes (each replaces coverage rather than dropping it):

- `Net.machine` and about 13 GameTest classes now use a cable port (the same
  `MachineInterfaceEntity`) as their generic machine port. All program, preset, recovery,
  adoption and indexed-port tests pass unchanged in what they assert.
- `InterfacePlacementGameTests`: a furnace now detects as Buffer, and the carried-mode round trip
  uses Storage.
- `InterfaceSwitchGameTests`: switches cycle Buffer and Storage (five switches, every old lease
  retired). Its "preset forgotten with the device" check moved to
  `CablePortGameTests.breakingAnIdlePort…`, which now deploys a preset first.
- `GuidePagesTest` requires Importer, Exporter and Label Tool instead of "Machine mode".
- Tours and suites: the presentation scene's Machine Interface is now an Exporter, renaming the
  `exporter-filter`, `exporter-throughput` and `exporter-overdrive` captures. The terminal tour's
  machine stages drive the Exporter screen (filter by a recipe-viewer drop, Keep 8, Apply) with
  the same conservation check. The restart check verifies the reopened Exporter's Stock. Gallery,
  benchmark, split-root and staged-recovery worlds place cable ports through `Ports`.

Checks:

- `python3 scripts/verify.py --full` PASS: 410 GameTests and every release suite, including
  staged recovery with cable ports.
- `runPresentationClient` and `runTerminalRestartClient` PASS after the fixes below. I inspected
  `exporter-scale-4-stock.png`, which shows Keep 8 applied and the status Ready.
- `runJeiClient` and `runMultiplayerCheck` PASS. They ran on this slice before the two Exporter
  screen fixes, which touch only that screen.

Found and fixed by the tours:

- **Keep box lost on resize.** Changing the GUI scale rebuilt the Exporter screen and reset the
  typed Keep amount, so Apply exported everything. The typed value now survives a rebuild, and
  the box fills once from the first synced preset. The status message now has two lines.
- **Mixed stock in the fixture.** An Item rule matches every component variant, so the terminal
  fixture removes the plain iron from the cell before stocking the named variant (the conservation
  totals count only the named item). The cursor and JEI gestures both make Item rules. A
  component-exact rule from a held stack is a possible follow-up.
