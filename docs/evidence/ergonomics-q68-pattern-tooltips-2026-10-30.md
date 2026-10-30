# Q68 — Pattern tooltips

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) Q68 (M69). Design:
[ADR 0030 amendment](../decisions/0030-patterns-and-crafting-units.md#amendment--pattern-tooltips-2026-10-30-ergonomics-plan-q68);
player guide under Patterns and the Crafter.

## Checks

- `runPresentationClient` passes with four new captures from the real tooltip path
  (`TooltipBoard` renders `graphics.renderTooltip` for the stack):
  - `pattern-tooltip-crafting-scale-2.png`, `-scale-4.png`: a stick pattern with substitutes shows
    its 3×3 grid (planks in two cells, cycling to spruce and birch), an arrow and 4 sticks,
    above the existing text lines.
  - `pattern-tooltip-processing-scale-2.png`, `-scale-4.png`: 2 raw iron, 1 coal and 1,000 mB
    water (shown as "1.0k" on the water icon) → 2 iron ingots.
  The first run drew the crafting output at the top of the grid; outputs are now centred.
- Not captured: the Shift decorator (the harness cannot hold Shift for `Screen.hasShiftDown`);
  it is a NeoForge item decorator on both pattern items that draws the first output.
- No server behaviour changed; GameTests and unit tests ran in `verify.py`.
- `python3 scripts/verify.py`: PASS on the first run (all 449 GameTests).
