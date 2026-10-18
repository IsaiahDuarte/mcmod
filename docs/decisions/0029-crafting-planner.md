# ADR 0029 — Portable pattern model and bounded recursive planner

Status: selected; portable core with JVM evidence. Pattern items, Crafting Units,
jobs/reservations and execution are subsequent P4 slices.

## Context

R06 requires saved recipes without scripts, recursive planning under a budget,
explained cycles and shortages, priority-ranked alternatives with stable-ID ties,
a single reservation of planned stock, and versioned patterns so in-flight jobs keep
their accepted version. Global optimality is not required.

## Decision

`Pattern(id, version, type, priority, inputs, output, outputAmount, byproducts)` is
an immutable contract in exact `ResourceKey` units. Inputs are exact keys (component
data included); tag or fuzzy ingredients are not part of version one. Byproducts are
deterministic per-craft returns such as container remainders. A pattern may not list
its own output as an input. At most nine input and nine byproduct keys; priority is
-1,000..1,000; version ≥ 1.

`CraftingPlanner.plan(request, amount, available)` works on an immutable stock
snapshot and never mutates storage:

- The requested key is always crafted (a craft request asks for new output). Other
  needs use units already produced by this plan (surplus/byproducts) first, then
  stock; only stock units appear in `consumed`, which a job reserves exactly once.
- Remaining units expand the highest-priority producer (stable UUID ascending on
  ties). A lower-ranked alternative is chosen only if every higher-ranked attempt
  adds shortages; otherwise the first is kept so the shortage it explains is shown.
- Each step's crafts are aggregated and placed after its inputs (dependency order).
  Byproducts and surplus become available only to later steps, so a step never
  reuses its own returns; this is conservative and documented.
- A key already on the expansion path is a cycle: the path is reported in `cycles`
  and its units are missing. Stock for any key on the cycle breaks it.
- Bounds: depth 32, 4,096 needs, 256 steps; checked arithmetic. Exceeding any bound
  returns `LIMIT` with no partial steps, consumption or shortages.

## Alternatives

Optimizing across alternatives (linear programming or full backtracking) has
unbounded cost and is not required. Taking the top-level item from stock would make
a craft request a withdrawal. Interleaving crafts within a step to reuse returned
containers would couple planning to execution timing; jobs may later run crafts
sequentially, but plans stay conservative.

## Consequences

A preview can be shown before submission and recomputed at submit time; it is
advisory. Jobs must revalidate stock when reserving `consumed`, record each step's
pattern version, and treat a changed/removed pattern per R06. Pattern discovery,
gateway-published recipes and processing execution supply the producer function in
later slices.
