# Q112 — Encoding preferences and processing slot options

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) Q112 (M113, M114, split from Q100). Design:
[ADR 0043 amendment](../decisions/0043-jei-integration.md); player guide under Processing Pattern
and JEI.

## Checks

- `GridTransferTest.prefersThePlentifulOption`: 300 birch planks beat 2 oak planks; a tie keeps
  the listed order.
- `runJeiClient` PASS:
  - The scene stocks 8 red sand and no sand. The furnace glass recipe fills `minecraft:red_sand`, the plentiful option.
  - Switching from Table puts "Machine recipe: the grid switched to Inputs/Outputs" on the action bar (read from the HUD).
  - A crafting recipe on the processing grid switches back and shows "Crafting recipe: the grid switched to Table".
  - The over-3×3 refusal (`too_large`) is unchanged.
- `TerminalCraftingGameTests.aProcessingPatternTakesTypedAmountsAndAChosenMainOutput`:
  - Counts 3 raw iron and 2 stone; MAIN on cell 10 swaps stone (with its count) into the first output.
  - The encoded pattern makes 2 stone from 3 raw iron, with an iron ingot byproduct.
  - MAIN on cell 9 is refused at decode.
- `runPresentationClient` PASS:
  - `crafting-inputs-scale-{1,4}.png` show the amount box between − and + reading "1000" for water. The first version read "00", then "100", at scale 1; the box was widened twice.
  - `crafting-outputs-scale-4.png` shows **Main** beside the selected second output.
- `runTerminalClient` PASS. `python3 scripts/verify.py` PASS (465 GameTests).

Not exercised: typing in the amount box graphically (the `typeGridAmount` hook exists but no
client step uses it; COUNT's effect is covered by the GameTest).
