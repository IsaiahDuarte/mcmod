# Crafting

> Since U12 ([ADR 0060](../decisions/0060-crafter.md)) the Crafting Unit and Crafting Interface are one `factorycore:crafter` block and `CrafterEntity` holding both pattern kinds; references below to either describe the Crafter.

P4 contracts. Portable planning is in [ADR 0029](../decisions/0029-crafting-planner.md);
pattern items and Crafters are in [ADR 0030](../decisions/0030-patterns-and-crafting-units.md).
Persisted jobs are in [ADR 0031](../decisions/0031-crafting-jobs.md); processing patterns
and Crafters are in [ADR 0032](../decisions/0032-processing-patterns.md). Manual crafting is
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


## Pattern items and Crafters

`platform.crafting.PatternContents` is the immutable `factorycore:pattern` component
(codec and stream codec reject invalid data). `pattern(codec)` throws
IllegalArgumentException for unrepresentable stacks. `CraftingUnitEntity.patterns()`
is the nine-slot handler (Crafting Patterns with contents only, one per slot);
`current()`, `origin()` and `diagnostic()` describe the holder. On the server thread,
`WorldCrafting.get(server).index(scope)` returns `Index(byOutput, truncated)`;
`producers(key)` feeds `CraftingPlanner` and `offer(patternId)` names the unit and
slot that supplied a pattern. The index is advisory and grants no authority.


## Jobs

Staging belongs to the request segment. Pull/delivery, remote processing
insertion/collection and published table input/output legs use the same wireless
pair allowances as terminal/preset/script moves. Table crafts reserve all their
legs before consuming ingredients; insufficient quota leaves staging intact and
waits. See [shared wireless throughput](../decisions/0050-shared-wireless-throughput.md).

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


## Fluid processing

Processing patterns may declare fluid inputs/outputs (mB); the Crafter uses the
faced machine's fluid handler for them ([ADR0045](../decisions/0045-fluid-processing.md)).
Requests and keep-stock rules accept item and fluid keys. Wait reasons: `machine_missing`
also covers a missing fluid port; `output_not_clean` also covers tanks holding the output
fluid.


## Physical machine leases and migration

New processing dispatches persist the exact physical machine coordinate in nested
job schema4. Interfaces facing the same machine share one lease; scripts/presets
and all shared external transfers report BUSY and extract nothing until that
job or its cancellation cleanup releases it. A job also waits `reserved` before
pulling ingredients while any existing provider group/partition hold or unresolved
staging retains that machine, even if its connector has unregistered. Release the
actual hold or recover the original transfer; restarting or rebinding never erases
that ownership. Cached external primitive mutations
also stop. Other adjacent machines remain usable. Rotating a dispatched interface
returns `machine_changed` and cannot transfer the job to another target; restore
its original orientation. External pipes/hoppers remain outside Factory Core's
lease path and must not feed processing machines concurrently.

Legacy schemas1–3 lack a target for active dispatches. They retain their exact
inserted/collected/staging records, fence all six neighboring coordinates without
loading chunks, and report `legacy_machine`. Cancel to return known staging and
let bounded cleanup finish; inspect the actual machine for already inserted
inputs/outputs. No target, output attribution or refund is guessed. See the
[processing decision](../decisions/0032-processing-patterns.md#physical-target-lease-correction-2026-10-18).


Nested job schema5 additionally persists provider-group/component/type fingerprints
before new dispatch. Every active and cancellation-cleanup mutation first checks
the original identity read-only. Replacing a machine at the same coordinates
cannot inherit the old job or have its existing outputs collected; the job reports
`machine_replaced`. Restore the original provider state or cancel and inspect the
old machine; never replay already inserted inputs. Normal provider NBT reload
preserves existing identity markers and can resume without another extraction.
Schema1–4 dispatches without identity proof remain fenced and require explicit
cancellation/inspection (`legacy_provider` when the target is known). See the
[identity/migration boundary](../decisions/0032-processing-patterns.md#provider-identity-fence-nested-schema5).
