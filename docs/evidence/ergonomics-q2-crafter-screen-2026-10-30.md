# Q2 — Crafter screen

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) Q2 (M2). Design:
[ADR 0060 amendment](../decisions/0060-crafter.md#amendment--crafter-screen-2026-10-30-ergonomics-plan-q2).

## Behaviour

Empty-hand use opens the Crafter screen: nine pattern slots, crafts per run, what the Crafter is
doing, and each machine beside it (free, making an output, or waiting with a reason). Patterns
move like any slot; without Edit they are greyed and every take, insert or Shift-click is refused
on the server. Sneak-use still takes the last pattern. The action-bar status line and its lang
entry are gone.

## Checks

- `CrafterScreenGameTests` (3 GameTests, all pass):
  - `theThirdOfThreePatternsIsTakenAndOneIsInsertedFromTheCursor`: clicking slot 3 of three
    puts exactly the third pattern on the cursor and leaves the others; a cursor pattern goes
    into the clicked slot; a diamond is refused; Shift-click moves a pattern to the inventory.
  - `withoutEditPatternsCannotBeMoved`: a player without EDIT sees `editable` false, and a
    click, a Shift-click and an insert all change nothing.
  - `aMachineHeldByAJobIsShownBusy`: a blast furnace beside the Crafter is `FREE` and the Crafter
    `IDLE` before a job; while the job smelts, the furnace is `BUSY` making
    `minecraft:iron_ingot` and the Crafter is `WORKING`.
- `python3 scripts/verify.py`: PASS, all 435 GameTests.
- `runPresentationClient` passes; the tour opens a Crafter holding nine crafting patterns and
  captures `crafter-scale-1.png` and `crafter-scale-4.png`. The panel is 230×238 and fits the
  427×240 minimum at scale 4. The first run showed three lines cut at the right edge and "1
  crafts"; the texts were shortened, and any line still too long shows whole as a tooltip.
