# Crafting

P4 contracts. Portable planning is in [ADR 0029](../decisions/0029-crafting-planner.md);
pattern items and Crafting Units are in [ADR 0030](../decisions/0030-patterns-and-crafting-units.md).
Persisted jobs are in [ADR 0031](../decisions/0031-crafting-jobs.md); processing patterns
and Crafting Interfaces are in [ADR 0032](../decisions/0032-processing-patterns.md). Manual crafting is
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


## Jobs

`core.crafting.CraftingJob` holds `(id, principal, network, segment, origin, request,
amount, steps, reserved, staged, state, reason)`. States: RUNNING, WAITING (reason),
DELIVERING, CANCELLING. Mutators: `pulled(backing, key, n)` (reserved → staged),
`crafted(inputs, outputs)` (RUNNING only; advances the current step), `delivered(key,
n)` (DELIVERING/CANCELLING), `released(backing, key)`, `waiting(reason)`, `resume()`,
`cancel()`. `finished()` is true when delivering/cancelling with nothing staged or
reserved. Invalid transitions throw. `CraftingJobs` bounds 64 jobs (8 per principal)
and `retire(id)` refuses unfinished jobs.

`platform.crafting.WorldCraftingJobs` (server thread): `preview(actor, origin, key,
amount)` and `submit(...)` return the plan and a reason (`""`, `missing`, `cycle`,
`limit`, `denied`, `offline`, `unsupported`, `changed`, `job_limit`,
`principal_limit`); `cancel(actor, id)` (requester or operator); `jobs(principal)`;
`notices(principal)` (last eight finished, not persisted). `CraftingJobCodec` is the
nested schema-one codec in world schema 13.


## Processing

`ProcessingContents` is the `factorycore:processing_pattern` component; `pattern(codec)`
returns a PROCESSING `Pattern` whose first output is primary. `CraftingInterfaceEntity`
(extends `PatternHolderEntity`) exposes `facing()` and `machine()`. A job's
`dispatch(machine)`, `inserted(key, n)`, `collected(key, n)`, `processed(inputs,
outputs)` and, after cancel, `abandonDispatch()` record one leased machine craft;
`crafted` refuses while a dispatch exists and `finished()` requires none. Wait reasons
add `machine_missing`, `busy`, `output_not_clean`, `machine_full` and `processing`.
Cleanup after cancel is bounded by `WorldCraftingJobs.MAX_CLEANUP_PASSES` (60).


## Branch publication

`WorldCrafting.Offer.published()` marks patterns offered by descendant branches whose
gateways all allow CRAFT for the branch ([ADR 0033](../decisions/0033-branch-recipe-publication.md)).
`WorldNetworks.ancestors(origin)` lists gateway ancestors nearest first. Jobs are
unchanged: they belong to the requester's segment, reserve from its view and deliver
there.


## Keep-stock rules

`core.crafting.KeepStockRule` (`deficit(available)`) and `CraftingJobs.setRule/ruleFor/
removeRule/rules` with the bounds in [ADR 0034](../decisions/0034-keep-stock-requests.md).
`WorldCraftingJobs.keep(actor, origin, key, low, high)` returns `""` or a reason
(`denied`, `offline`, `unsupported`, `rule_limit`, `principal_rule_limit`, `limit`);
`rule(actor, origin, key)`, `unkeep(actor, ruleId)` (owner or operator) and
`ruleStatus(ruleId)` (`satisfied`, `in_flight`, `started` or a submit reason).
`CraftingJob.rule()` links a job to its rule. Nested job schema 3 adds `Rules`.
