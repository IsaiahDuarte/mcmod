# ADR 0063 — Crafting-pattern substitution (parity E1, PD4)

Status: accepted and implemented 2026-10-25.

## Context

AE2 and RS let a crafting pattern accept any item its recipe accepts, for example any planks.
Factory Core's patterns were exact (ADR 0030), so a network full of birch planks could not
craft sticks from an oak-plank pattern. PD4 allowed opt-in substitution for crafting-table
patterns only, with a per-pattern toggle. Processing patterns stay exact.

## Decision

- **Encoding.** The terminal's Table grid has a **Subst.** toggle. When it is on, Encode
  records, per cell, the other items the server recipe's matching ingredient accepts
  (`Ingredient.getItems()`). A cell is left exact if its ingredient lists more than
  `Pattern.MAX_ALTERNATIVES` (16) items, rather than truncating. The pattern item saves
  this as an optional `substitutes` list. Older and exact patterns have no such field and
  keep their exact bytes.
- **Contract.** `Pattern.substitutes` maps an input key to its alternatives. Cells that
  share an input keep only the alternatives every one of them allows. An alternative that
  is the output, another input or another input's alternative is dropped, so each concrete
  key belongs to exactly one input.
- **Planning.** For a substituting input, the planner uses this plan's own surplus of the
  primary key first, then stock across the primary and its alternatives (most available
  first, ties by registry ID), then crafts the remainder as the primary. The concrete keys
  join `consumed`, so the job still reserves once (SPEC R06). The step records the
  allocation (`Step.uses`).
- **Execution.** A job step carries its remaining allocation, saved in crafting-job schema
  6 (`Uses` on each step). Schema 6 is written only when some step has an allocation;
  otherwise the schema-5 bytes are unchanged. Each craft takes its units from its own
  step's allocation in registry-ID order, never from units planned for another step. It
  pulls exactly those units, builds the 3×3 grid from the chosen items, and revalidates
  the real server recipe on that grid. The output and remainders must still equal the
  encoded ones, otherwise the job waits with "recipe_changed". If the allocation runs out
  it waits with "substitutes_short" (not expected; it is a guard).

## Alternatives

- **Tag inputs in the pattern.** Rejected. Tags change with datapacks, and the planner
  would have to enumerate them while planning. The encoded list is fixed per pattern, and
  the recipe is revalidated anyway.
- **Choosing items at craft time from whatever is staged.** Rejected. A substituting step
  could take units planned for an exact step (oak planks meant for a later oak-only
  recipe) and leave it stuck.
- **Fewest leftover kinds instead of most available.** Not needed for parity. Most
  available keeps rare variants in stock.

## Consequences

- Crafting-job schema 6 and an optional pattern field. Schema 5 saves still load. The
  first unknown job schema is now 7 (`CraftingJobPersistenceTest`).
- Substituted outputs that depend on the input (for example dyed items) fail
  revalidation and wait instead of crafting the wrong item, because the encoded output
  must match.
