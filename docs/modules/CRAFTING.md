# Crafting

P4 contracts. Portable planning is in [ADR 0029](../decisions/0029-crafting-planner.md);
pattern items and Crafting Units are in [ADR 0030](../decisions/0030-patterns-and-crafting-units.md).
Jobs, Crafting Interfaces and request UI are not implemented yet. Manual crafting is
the [terminal grid](STORAGE_TERMINAL.md#manual-crafting-grid).

## Pattern

`core.crafting.Pattern` fields: stable `id`, `version` (≥ 1), `type` CRAFTING or
PROCESSING, `priority` (-1,000..1,000), exact `inputs` and `byproducts` (positive
unit amounts, at most nine keys each, iteration order preserved), primary `output`
and `outputAmount` (≥ 1) per craft. Invalid values throw IllegalArgumentException.

## Planner

`new CraftingPlanner(producers)` takes a function returning candidate patterns for a
key (non-matching outputs are ignored). `plan(request, amount, available)` requires
amount ≥ 1 and non-negative stock and returns `Plan(status, request, amount, steps,
consumed, missing, cycles, nodes)`:

- `OK`: every unit is covered; `consumed` is the exact stock to reserve.
- `MISSING`: `missing` lists unsuppliable units; `cycles` lists key paths such as
  `[coal, charcoal, coal]`. Steps and consumption still describe the attempted plan.
- `LIMIT`: depth 32, 4,096 needs, 256 steps or long overflow was reached; the plan
  is empty.

Steps are `(pattern, version, output, crafts)` in dependency order. Instances are
single-threaded and not shared; plans are immutable.


## Pattern items and Crafting Units

`platform.crafting.PatternContents` is the immutable `factorycore:pattern` component
(codec and stream codec reject invalid data). `pattern(codec)` throws
IllegalArgumentException for unrepresentable stacks. `CraftingUnitEntity.patterns()`
is the nine-slot handler (Crafting Patterns with contents only, one per slot);
`current()`, `origin()` and `diagnostic()` describe the holder. On the server thread,
`WorldCrafting.get(server).index(scope)` returns `Index(byOutput, truncated)`;
`producers(key)` feeds `CraftingPlanner` and `offer(patternId)` names the unit and
slot that supplied a pattern. The index is advisory and grants no authority.
