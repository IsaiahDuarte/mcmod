# Q100 — Crafting nits

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) Q100 (M115–M118). M113 and M114 moved to Q112.
Design: amendments to [ADR 0030](../decisions/0030-patterns-and-crafting-units.md) and
[ADR 0066](../decisions/0066-importer-exporter.md); player guide under Patterns, Jobs and
Importer and Exporter.

## Checks

- `TerminalPatternGameTests.filterModesHidingAndShiftClickStore`, on a full labelled Crafter and a torch Crafter:
  - Not full lists only the torch Crafter.
  - "coal" matches nothing by output; by input it finds the torch pattern.
  - By name, "shop" finds the "Wood shop" Crafter.
  - Hiding the torch Crafter drops it from Visible, saves `Hidden` and lists it under All marked hidden. Showing it clears the flag.
  - STORE from inventory slot 5 skips the full Crafter and fills the torch Crafter's slot 1; a diamond is refused and stays.
- `TerminalJobGameTests.jobStepsNameTheirMachine`:
  - A waiting processing step names `factorycore:crafter` while no machine faces it.
  - After a furnace is placed beside the Crafter and the Crafter is labelled "Smelter", the step names `minecraft:furnace` with the label. The terminal is reopened after the network rebuild.
- `PortGameTests.exporterFilterRulesShowWhatStorageHolds`: an Exporter filter shows
  STOCKED (iron in storage), CRAFTABLE (sticks from a Crafter's pattern, a registry-ID rule), EMPTY
  (diamond) and UNKNOWN (a tag rule).
- `LangKeysTest`: the new Patterns statuses have keys.
- `runPresentationClient` PASS:
  - `terminal-patterns-scale-{1,4}.png` show the Visible and Out buttons and a hide link per Crafter.
  - `jobs-monitor-waiting-scale-4.png` shows "Waiting · Cr…": the machine name is cut at scale 4, so step rows have a tooltip with the full line.
- `runTerminalClient` PASS: `exporter-scale-4-stock.png` shows the rule dot.
- Decision: blanks stay in the main inventory (ADR 0030 amendment).
- `python3 scripts/verify.py`: PASS (464 GameTests).

Not exercised graphically: the Shift-click itself and the step tooltip (the GameTest covers STORE's server effect).
