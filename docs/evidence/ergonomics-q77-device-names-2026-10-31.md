# Q77 — Device names (2026-10-31)

**How.** Vanilla 1.21 keeps an item's unconsumed components on the block entity it places, and
saves them with it, so an anvil name (`CUSTOM_NAME`) needs no new field. `DeviceNames`:
- `name(entity)` reads it, or the Crafter's label;
- `title(entity, fallback)` gives the screen title (Terminal, Drive, Buffer, Storage and Signal
  Interface, Importer/Exporter/cable port, Energy Bank, Program Controller, Gateway);
- `carry(entity, drops)` names a leased device's own drop (`NetworkedBlock.onRemove`, which the
  wrench pick-up also uses).

Loot-table devices list `CUSTOM_NAME` in `CarriedComponents` (Terminal, Drive, Access Point,
Program Controller, Fuel Generator), and `runData` regenerated their loot tables.

**Lists.** The Patterns view already shows Crafter names (ADR 0060). Job steps are Q70 (needs
QD19). Overview and Access lists are recorded as Q116.

## Checks

- `WrenchGameTests.deviceNamesShowAsTitlesSurviveReloadAndPickUp`: a named Exporter's title is
  "Ore line". A `saveWithFullMetadata`/`loadStatic` round trip keeps it. A wrench pick-up gives an
  item named "Ore line", and placing that item again keeps the name. A named Terminal's title is
  its name, and its loot drop keeps it.
- 481 GameTests passed twice.
- Presentation client `buffer-slot-goal-scale-1.png` / `-scale-4.png`: the Buffer named "Smelter
  feed" shows that as its title (the name is cleared afterwards).
- `./gradlew runData` (5 loot tables); `python3 scripts/verify.py`: PASS.
