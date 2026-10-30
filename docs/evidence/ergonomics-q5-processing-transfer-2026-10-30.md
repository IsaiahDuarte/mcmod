# Q5 — Recipe-viewer transfer for processing patterns

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) Q5 (M5). Design:
[ADR 0043 amendment](../decisions/0043-jei-integration.md#amendment--processing-transfer-from-any-recipe-2026-10-30-ergonomics-plan-q5);
payload in [STORAGE_TERMINAL](../modules/STORAGE_TERMINAL.md#recipe-viewer-fill); player guide
under JEI.

## Checks

- `ProcessingTransferTest` (native): five outputs keep the first three with a total of five
  (the action-bar message "Kept the first 3 of 5 outputs" is built from these); two outputs are
  not truncated; item and fluid amounts become grid units (300 mB rounds up to two units, capped
  at 64). `LangKeysTest` passes with the three new keys.
- `runJeiClient` PASS. After the existing crafting checks, the suite resolves JEI's handler for
  the open terminal and a smelting and a blasting category, and transfers through JEI's own recipe
  layouts. Sand → glass fills Inputs cell 0 and Outputs cell 9 (one each) in processing mode;
  raw iron → iron ingot from blasting then replaces it. `jei-processing-transfer.png` shows the
  Inputs page holding sand. As before, the RecipesGui button click itself is not simulated.
- Not covered graphically: fluid recipes (vanilla has no fluid machine recipe for JEI to show);
  the fluid path is the same cell code checked by the unit conversions.
- `python3 scripts/verify.py`: PASS on the first run (all 448 GameTests).
