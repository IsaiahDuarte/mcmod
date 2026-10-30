# Q7 — Craft as many as possible

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) Q7 (M7). Design:
[ADR 0031 amendment](../decisions/0031-crafting-jobs.md#amendment--craft-as-many-as-possible-2026-10-30-ergonomics-plan-q7);
protocol in [STORAGE_TERMINAL](../modules/STORAGE_TERMINAL.md); player guide under Jobs.

## Checks

- `CraftingPlannerTest` (native):
  - `maximumIsTheLargestAmountThatPlans`: nine logs and a 1 log → 4 planks pattern give exactly
    36 planks (plan amount 36, status OK); through a stick pattern, 72 sticks.
  - `maximumIsZeroWhenNothingCanBeMadeAndNamesWhatIsMissing`: no logs gives 0, with the
    single-unit plan missing one log.
  - `maximumStopsAtTheCapAndSaysSo`: ample stock stops at 4,096 with `capped`; a cap of 0 is
    refused.
- `TerminalJobGameTests.maxPreviewsTheMostThatCanBeMade`: through the terminal's MAX command,
  nine stored logs preview 36 planks with status `maximum` and no reason; for stone buttons with no
  stone, the preview is one with status and reason `missing`.
- `LangKeysTest` covers the two new `JobStatus` keys.
- `runPresentationClient` passes. `jobs-scrolled-end-scale-1.png` and `-scale-4.png` show the
  button row ×1, ×8, ×64, Max, the amount box and Start, all fitting the panel; the box still
  holds four digits. The tour does not press Max.
- `TerminalJobPacketsTest`: the action bound now ends at MAX.
- `python3 scripts/verify.py`: PASS (all 449 GameTests) on the second run; the first failed only
  on that bound.
