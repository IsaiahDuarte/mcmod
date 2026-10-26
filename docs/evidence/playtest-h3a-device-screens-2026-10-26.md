# H3a — Interface and Drive screens on the panel kit

Scope: playtest plan H3 group (a), findings K1 and K3. Decisions are in the
[ADR 0064 H3a amendment](../decisions/0064-panel-kit.md).

Implemented:

- **Storage Interface** (`ConnectorSettingsScreen`):
  - Layout: a Settings/Filter pair at the top. Settings shows resource choices (Items, Fluids,
    Energy), access choices (In and out, Take only, Store only), priority and rate, the slot
    picker, and one muted line with the side and speed. Filter shows the insert mode, the 16
    ghost slots with Add by ID and Clear beside them, and the selected rule. The Remove button
    is gone because a right-click on a slot removes it.
  - Footer: device status, then Reset, Cancel, Apply.
- **Buffer Interface** (`BufferSettingsScreen`):
  - Settings view: Settings/Inventory sits in the title row. Mode is shown as choices (Paused,
    Import, Export, Stock) and filter as choices (Any item, Only these, Not these). Below are
    Rate and Goal with Set, the ghost slots, and Redstone and Sample.
  - Inventory view: the nine buffer slots, Take 1, Take 64 and Return, then the player inventory
    and the Interface mode row.
  - Server: `BufferSettingsPackets.MODE`/`FILTER` with `value` n now pick choice n − 1. A value
    of 0 still cycles, and an out-of-range choice is refused as invalid.
- **Signal Interface** (`SignalScreen`):
  - Rows: Watch (slot, name, Add by ID; the typed ID replaces the name while open), then When
    (Amount stored or Crafting job runs), then ≥ or < with the amount and Full or Scaled
    output, the live output line, Label, and Mode.
- **Drive** (`DriveScreen`): bays are inset rows with kit slots and fill bars. The partition
  panel uses the same slots layout as the Interfaces. The player inventory is centred.
- **Shared parts:**
  - `InterfaceModeRow` draws the captioned mode row on the kit for all three Interfaces.
  - `FilterSlots.describe` uses kit colours.
- **Instruction lines removed:** "Apply saves your changes…", "Click a bay…", the partition
  note and the Signal watch hint. Each is in a tooltip now.

Checks:

- `BufferSettingsGameTests.modeAndFilterChoicesAreSetDirectly` (new): Stock is picked directly
  and picking it again keeps it, and 0 still cycles. The block list is picked, an unknown choice
  is invalid and changes nothing, and Apply saves the picked choices.
- `runGameTestServer`: 414 of 414.
- Graphical tours, with captures inspected:
  - `runPresentationClient`: connector, connector-filter, connector-picked, buffer, signal, drive
    and drive-partition, at scales 1 and 4.
  - `runTerminalClient`: the Buffer stages now pick Export, Stock and Paused as choices and use
    the new inventory positions. Captures: buffer-scale-2/3/4 in both views.
  - `runJeiClient`: filter drops into the ported screens.
- `python3 scripts/verify.py --full`: passed (build, PMD, JVM tests, 414 GameTests, release check).

Not done here: groups (b) Access/Recovery and (c) Program Controller, Label Tool and Guide.
