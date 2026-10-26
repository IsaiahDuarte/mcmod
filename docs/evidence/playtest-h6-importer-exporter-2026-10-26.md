# H6 — Importer and Exporter

Scope: playtest plan H6 (K4, K8; gate HD2; [ADR 0066](../decisions/0066-importer-exporter.md)).
Minecraft 1.21.1, NeoForge 21.1.252, Java 21, Linux x86-64 on Wayland.

Implemented:

- The `importer` and `exporter` blocks and items (Starter recipes, chevron textures). They are
  backed by the Machine-port entity, with the role enforced in `MachineInterfaceEntity.deployPreset`
  and in the menu.
- A placed Importer binds what the touched face offers and imports everything under its placer's
  grants. An Exporter waits for a filter, and Apply without one says "Choose what to send first".
- `PortScreen` (panel kit, 176 px) offers:
  - the resource choice;
  - 16 ghost filter slots (now drawn in the kit's slot style everywhere);
  - Only these / All but these;
  - Keep (Exporter);
  - redstone;
  - Return, Stop and Apply.

  New preset commands: `DEPLOY_AS` and `FILTER_MODE`.
- Per-face backings for Importers and Exporters (`factorycore:face-port:v1:<group>:<face>`), so
  several can serve one machine. Storage and Machine-mode Interfaces keep one owner per
  container, and processing admission also checks the face backings.

Checks:

- `PortGameTests` (5 new GameTests):
  - an Importer empties a chest of iron, dirt and nuggets exactly into storage;
  - an Exporter waits for a filter, refuses Import, then holds a chest at exactly 8 iron while
    storage loses exactly 8;
  - a guest without Deploy stays paused, and an Importer refuses Export;
  - an Importer on a chest that is network storage is refused as `OVERLAP` and nothing moves;
  - **auto smelter**: Exporter on top (raw iron), Exporter on the side (coal, Keep 8), Importer
    underneath. Both raw iron come back as ingots, and coal is only burned or stocked.
- `MachinePresetPacketsTest` (new actions in range, the next one refused), `RecipeProgressionTest`
  (both Starter) and `RecipeGameTests` (25 recipes).
- `runGameTestServer`: 404 of 404 on two consecutive runs. One earlier run lost the unrelated
  `DrivePartitionGameTests.partitionLimits…` to the known PD7 rebuild race. Its single terminal
  deposit is refused while the network rebuilds and is not retried.
- `runPresentationClient` PASS with new `importer`/`exporter` captures at scales 1–4 (176×153 and
  176×175 panels). I inspected `exporter-scale-4.png` and `importer-scale-1.png`. The Exporter
  hint was cut off and was shortened before commit.
- `python3 scripts/verify.py --full` PASS (404 GameTests and every release suite).

Limits: In-world block appearance has not been inspected. Programs reach an Importer/Exporter by
its label like a Machine port until H5 lets labels go on machines.
