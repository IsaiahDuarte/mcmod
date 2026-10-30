# Q107 — Crafter labels

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) Q107 (M146), after Q3. Design:
[ADR 0060 amendment](../decisions/0060-crafter.md#amendment--crafter-labels-2026-10-30-ergonomics-plan-q107);
payload in [STORAGE_TERMINAL](../modules/STORAGE_TERMINAL.md#patterns-view-q3); player guide
under Label Tool.

## Behaviour

Using the Label Tool on a Crafter labels it (EDIT where it is) instead of opening its screen. The
Crafter screen title and the terminal's Patterns rows show the label, else the position. The label
is saved with the Crafter (optional `Label` in schema 1) and travels as the dropped item's
vanilla name (`minecraft:custom_name`, added to the generated loot table), so an anvil-named
Crafter is labelled when placed.

## Checks

- `TerminalPatternGameTests.aLabelledCrafterIsNamedByItsLabel`: a player without EDIT is refused;
  the owner's use labels the Crafter and opens no screen; the Patterns view row carries
  "Planks"; a save and load keeps it; the item made from its components is named "Planks" and a
  Crafter placed from it is labelled. It passed in 12 full GameTest runs (Q108 runs 9–20).
- `runData` regenerated `loot_table/blocks/crafter.json` (copies `minecraft:custom_name`).
- `runPresentationClient` passes. The presentation's Jobs Crafter is now labelled "Planks";
  `terminal-patterns-scale-1.png` and `-scale-4.png` show that row named "Planks" between rows
  named by position. The `crafter-scale-*` captures show a different, unlabelled Crafter, so the
  labelled screen title was not captured.
- `python3 scripts/verify.py`: PASS on the first run (all 445 GameTests), on the tree holding Q108 and Q107.
