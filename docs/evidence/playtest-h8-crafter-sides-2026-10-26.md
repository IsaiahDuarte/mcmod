# H8 — Crafter serves any machine beside it

Scope: playtest plan H8 (K7; gate HD5; amendment to [ADR 0060](../decisions/0060-crafter.md)).
Minecraft 1.21.1, NeoForge 21.1.252, Java 21, Linux x86-64.

Implemented:

- `CrafterEntity.machineSides()` lists the sides with a machine, starting after the side used
  last. `machine(side)` and `fluidMachine(side)` replace the single faced handler.
  `WorldCraftingJobs` picks a side per dispatch through `refusal(...)` (handlers, another job's
  lease, admission, output not clean, journal), saves the target as before, and uses that saved
  side for later passes and cleanup.
- Factory Core blocks, cables, other Crafters and a hopper pointing into the Crafter are never
  machines. Machine sides hide the pattern handler, and `CrafterBlock.neighborChanged`
  invalidates capabilities.
- Text: the `machine_missing` reason, the Crafter tooltip, guide page 9, the player guide and
  SPEC R02.

Checks:

- `CrafterSidesGameTests` (new):
  - one Crafter between two blast furnaces sends the first craft to one and the second to the
    other, collecting both ingots;
  - a furnace already holding the output is skipped and the other makes the ingot;
  - furnaces and a chest count as machines; the feeding hopper, a cable and the controller do
    not.
- `CraftingUnitGameTests.crafterHoldsBothPatternKinds…` now checks the new rule: every side with
  a machine is closed to patterns, other sides stay open. The old "only the faced side" rule no
  longer exists.
- `python3 scripts/verify.py --full` PASS. The existing processing, journal, interruption and recovery GameTests pass unchanged, with the
  Crafter still facing its machine there: 407 of 407.
