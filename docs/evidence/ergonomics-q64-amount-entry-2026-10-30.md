# Q64 — Amount entry

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) Q64, the amount entry (M66). The JEI bulk
requests (M65) are Q111. Design:
[ADR 0031 amendment](../decisions/0031-crafting-jobs.md#amendment--amount-entry-2026-10-30-ergonomics-plan-q64);
player guide under Preview and Start.

## Checks

- `CraftAmountTest` (5 tests): the existing whole-number cases still pass. New cases cover
  precedence and parentheses, eight levels allowed and nine refused, an inexact division
  (`fraction`), division by zero, a zero or negative result, over the 4,096 cap, malformed
  input, the 32-character bound, overflow refused as too many (never a wrapped number), and
  `=N` (marked as a target, may pass the cap, `=`, `=0` and over 10^9 refused).
- `TerminalJobGameTests.targetsPreviewOnlyTheShortfallAndQuickStarts`: with 10 planks stored,
  `=18` previews 8, `=10` answers ENOUGH with no preview, and QUICK 4 starts a job in one
  command.
- `TerminalJobPacketsTest`: the action bound is now QUICK_TARGET + 1. QUICK over 4,096 and
  TARGET over 10^9 are refused; a 10^9 target is accepted.
- `runPresentationClient` PASS: the Jobs step now types `4*2` through the amount box path and
  gets the eight-plank preview (`jobs-preview-scale-2.png` shows `4*2`, "8 × Oak Planks", and
  the − / + steppers in place of ×1 / ×8).
- Not exercised graphically: the steppers' Shift/Ctrl sizes, the wheel and Up/Down, and the
  Shift+Start button path (its server action QUICK is covered above).
- Full GameTest run: all 458 passed.
- `python3 scripts/verify.py`: PASS (all 458 GameTests).
