# Q149 — Recipe-conflict arrows (2026-11-02)

From playthrough note 12: "recipe conflict handling, like putting arrows on the crafting output…
and do that in the encoder too". Split from Q139.

## Change

- **Several recipes match:** `TerminalCrafting.lookup` now lists every crafting recipe that
  matches the ghost grid (`getRecipesFor`), sorted by recipe ID so the order is stable, and
  shows choice `variant`.
  - The grid view carries `choice` and `choices`.
  - The panel draws "‹1/2›" under the result. Clicking its left or right half, or scrolling over
    the result, sends CUT with cell -1. On the crafting table CUT cycles the recipe, and on the
    stonecutter it cycles the result as before (Q6).
  - Any cell change starts again at the first recipe.
  - Encode and Hand craft use the recipe shown.
- **Tag ingredients:** for each crafting cell, the shown recipe's ingredient that accepts the
  ghost lists its items (2 to 16 distinct; more stay unmarked, as substitutes do).
  - The view carries a 9-bit `alternatives` mask, cached per grid revision.
  - A marked cell gets a cyan corner (`Panels.choiceMark`) and a tooltip line.
  - Scrolling over it sends CUT with the cell's index, which puts the next or previous accepted
    item there and keeps the recipe choice.
- **Crafter:** a job's crafting step used `getRecipeFor`, the first match, and then refused any
  output other than the pattern's. A pattern encoded from the second matching recipe would have
  stalled. The step now uses the matching recipe whose assembled output is the pattern's.

## Tests

- **Test recipes:** two shaped recipes, "dirt over dirt" → coarse dirt and → podzol, in the
  GameTest data pack (`factorycore_tests`) and the client-test mod's data
  (`factorycore_client_tests`). The real game has neither.
- **GameTests:**
  - `severalMatchingRecipesAreChosenAndUsed`: 2 choices, choice 0 shows coarse dirt; CUT shows
    choice 1, podzol; Encode writes a podzol pattern; Hand craft stores one podzol from the two
    dirt.
  - `aTagCellCyclesTheItemsItsRecipeAccepts`: in the stick recipe the two plank cells are
    marked and an empty cell is not; CUT on cell 0 puts a different plank there and the result
    is still sticks.
  - `aPatternFromTheSecondMatchingRecipeCraftsItsOwnOutput`: a Crafter with a dirt → podzol
    pattern finishes a podzol job, using exactly 2 dirt.
- **Presentation tour** (passed, headless display):
  - dirt over dirt shows 1/2 (coarse dirt); a real click on the right half shows 2/2 (podzol);
  - `crafting-choices-scale-1.png` and `-scale-4.png` are new;
  - `crafting-table-scale-4.png` shows the cyan mark on the oak-log cell (`#oak_logs`).
- `python3 scripts/verify.py` passes (533 GameTests).

## Screenshots looked at

- `crafting-choices-scale-4.png`: "‹ 1/2 ›" with spaces ran under the Use selected button, so the
  label became "‹1/2›". At scale 1 it sits under the result, clear of the buttons.
- `crafting-table-scale-4.png`: the cyan corner on the log cell.
