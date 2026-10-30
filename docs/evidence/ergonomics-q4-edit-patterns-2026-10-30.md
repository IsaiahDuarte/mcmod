# Q4 — Edit and clear patterns

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) Q4 (M4). Design:
[ADR 0030 amendment](../decisions/0030-patterns-and-crafting-units.md#amendment--editing-and-wiping-patterns-2026-10-30-ergonomics-plan-q4);
protocol in [STORAGE_TERMINAL](../modules/STORAGE_TERMINAL.md#manual-crafting-grid); player guide
under Patterns and the Crafter.

## Deviation from the slice text

The slice speaks of "the crafting panel's pattern slot". The panel holds no items (ghost cells
only), so a slot would need item custody and return rules. Loading is a Ctrl-click on the
encoded pattern in the inventory instead, the gesture that already fills ghost cells; the result
is the same (load → change → Encode rewrites).

## Checks

- `TerminalCraftingGameTests`:
  - `aCraftingPatternLoadsAndEncodeRewritesIt`: a stick pattern with substitutes is encoded on
    the only blank, loaded back (Table cells and `loaded_substitutes`), changed to planks from a
    log, and Encode rewrites it in the same slot with no blank left: one pattern, planks, exact.
  - `aProcessingPatternLoadsWithAmountsAndIsRewritten`: 2 raw iron + 500 mB water → 2 ingots
    loads with its amounts (fluid as two 250 mB units); a pattern with 300 mB is refused as
    `unsupported` and leaves the grid; the output count changed to 3 is rewritten in place.
  - `anEncodedPatternWipesToABlank`: a pattern alone in a crafting grid makes a blank;
    sneak-use in the air wipes it; a plain use does not.
- `RecipeGameTests.everyRecipeResolvesUniquelyAndEveryRegisteredItemIsObtainable`: 26 recipes;
  the wipe recipe is the named exception to "one recipe per item" and must yield a blank. Every
  other assertion still applies to it (canonical grid, unlock advancement, unbound result).
- `RecipeProgressionTest.reversalsOnlyGiveBackWhatAnEncodingConsumed`: the wipe recipe is a
  reversal ([ADR 0048 amendment](../decisions/0048-final-balance.md#amendment--reversals-2026-10-30-ergonomics-plan-q4)):
  one shapeless ingredient, each option an encoding made from exactly a blank, yielding one. The
  first verify run failed because the balance test parsed it as a second blank source with a
  non-exact ingredient; the reversal list leaves it out of the source, stage and grid checks only.
- `TerminalCraftingPacketsTest`: the action bound now ends at LOAD.
- `runData` generated the recipe and its unlock advancement.
- `python3 scripts/verify.py`: PASS (all 448 GameTests) on the second run; the first failed only
  on the two unit tests above.
- Not run graphically: the Ctrl-click path is client input code, reused from the existing
  ghost Ctrl-click; no screen layout changed.
