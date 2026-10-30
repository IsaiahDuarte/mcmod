# ADR 0030 — Pattern items and Crafting Units

Status: selected; native headless evidence. Job execution, request UI and gateway
publication are later P4 slices.

## Decision

A **Blank Pattern** is an ordinary item. The terminal grid's **Encode** action writes
the current ghost recipe onto one blank from the player's main inventory, producing a
**Crafting Pattern** (stack size 1) with the `factorycore:pattern` component
`PatternContents(id, version, priority, cells, output, remainders)`:

- `cells` are the exact shaped placement (nine count-one stacks, empty allowed).
- `output` and `remainders` come from the server's matched `CraftingRecipe`
  (`assemble`, non-empty `getRemainingItems`) at encode time, never from the client.
- A fresh random UUID, version 1 and priority 0. Encoding is refused, consuming
  nothing, without a recipe, without a blank, if the encoded pattern would not fit,
  or if the recipe is not a valid portable `Pattern` (for example output = input).
- `pattern(codec)` converts to the portable contract with exact component keys.

A **Crafting Unit** is a wired block holding up to nine Crafting Patterns in a
one-per-slot handler that rejects every other item; hoppers and players may insert
patterns (physical access is outside network permissions). It has no ownership lease
and owns no network resources: copying it copies only ordinary pattern items. It
joins topology with the new non-persisted `CRAFTER` role, valid while it is the
current loaded holder with supported data.

`WorldCrafting.index(scope)` rebuilds, per query, the patterns offered by current,
VALID units in exactly that network and segment (no gateway publication), sorted by
unit position; the first unit wins duplicate pattern IDs. Bounds: 1,024 tracked
units per server and 4,096 patterns per index, with overflow reported as
`truncated`. Each query rebuilds, so there is no cache invalidation state.

Block-entity schema1 stores `Patterns`. An unsupported schema or slot count is kept
unchanged and inert and travels with the broken unit in `factorycore:unit_retained`;
breaking a supported unit drops every pattern.

Recipes (D06, vanilla only): Blank Pattern PRP/RIR/PRP (four paper, four redstone,
one iron) makes four; Crafting Unit IGI/RTR/IGI (four iron, two gold, two redstone,
one crafting table), unlocked by gold for the Expanded tier.

## Alternatives

Encoding from client-supplied stacks would trust client recipe output. Making the
unit a leased canonical owner would add recovery surface for items that are not
network resources. A persistent pattern registry would duplicate physical state and
need invalidation; per-query rebuilding stays bounded and exact.

## Consequences

Patterns are segment-local until gateway publication exists. Units do not execute
jobs yet; the index supplies the planner's producer function for the next slice.

## Amendment — editing and wiping patterns (2026-10-30, ergonomics plan Q4)

An encoded pattern can be loaded back into the terminal's crafting grid and rewritten, and wiped
to a blank. The grid has no item slot, so loading is a Ctrl-click on the pattern in the player's
inventory, the gesture that already places ghost ingredients; the server reads the pattern from
that main-inventory slot. Encode then replaces that stack while it still holds the same pattern
id, so no blank is needed and no second copy appears. The rewritten pattern gets a fresh id,
like any new encoding: Crafters index patterns by id, and a copy of the old pattern elsewhere
must not be taken for the new recipe. A pattern the grid cannot represent exactly is refused
rather than rounded. Wiping is a shapeless recipe (either encoded pattern → one Blank Pattern,
`blank_pattern_from_pattern`, the only second recipe for an item) and a sneak-use in the air.
Alternatives: a real pattern slot in the panel (rejected for now: the panel holds no items, and
a slot would need item custody and return-on-close rules for one gesture), and rewriting in
place under the old id (rejected as above).
