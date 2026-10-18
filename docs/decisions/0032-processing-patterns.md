# ADR 0032 — Processing patterns and the Crafting Interface

Status: selected; native headless evidence with a real blast furnace. Fluid inputs/
outputs, multi-machine sides and graphical verification remain open.

## Context

R06 requires processing patterns bound to machine ports with deterministic inputs,
outputs and byproducts; a port lease before dispatch; clean output ports and recorded
pre-dispatch state; completion from collected leased outputs (never a storage count
increase); exclusive external use of the port during a job, documented in the UI; and
a bounded cleanup record for in-flight outputs after cancellation.

## Decision

**Patterns.** A Processing Pattern item carries `ProcessingContents(id, version,
priority, inputs, outputs)`: one to nine exact item inputs and one to three exact
outputs, each 1–64 per craft; the first output is primary, the rest byproducts. The
player declares them in the terminal grid's processing mode (Inputs/Outputs pages,
scroll to set counts, Encode onto a blank). No machine recipe is consulted, so the
player owns the claim that the machine is deterministic.

**Crafting Interface.** A wired, facing block holding nine processing patterns (any
side except the faced one accepts them by hopper). It offers its patterns to its
segment through the same `WorldCrafting` index as Crafting Units (`PatternHolderEntity`
base, `CRAFTER` role). Its machine handler is the faced block's unsided item handler
when available (so a furnace's input, fuel and result slots are all reachable), else
the faced side's handler. It stores no job state.

**Lease and dispatch.** The job's persisted `Dispatch(machine, inserted, collected)` is
the port lease; nested job schema 2 stores it (schema 1 still loads, without a
dispatch). A job may dispatch only when no other job holds a dispatch at that
interface (`busy`), the machine contains none of the expected output keys
(`output_not_clean` — the recorded pre-dispatch state), and every input for one craft
is staged. Inputs are then inserted for real a key at a time; accepted units leave
the job, the rest stay staged (`machine_full` retries). Each pass extracts only the
expected output keys from that machine into staging (`processing` until complete);
the craft completes when every input was inserted and every output collected.

**Cancel.** Unused reservations are released and staging delivered as usual. An
active dispatch becomes a bounded cleanup record: for at most 60 passes (≈60 s) the job
keeps collecting the dispatch's expected outputs, then abandons the rest in the
machine without claiming them. Inserted inputs are consumed; nothing is replayed or
refunded.

## Alternatives

Treating storage increases as completion would credit foreign insertions. A
persistent lease table separate from jobs could disagree with them after a crash; the
dispatch in the job record is the single source. Inserting all inputs atomically is
impossible with third-party handlers; per-key real insertion with staged remainders
never voids. Requiring a pattern editor screen would add a large untested UI; reusing
the grid keeps one authenticated editing path.

## Consequences

Machines must not be fed by other automation while a job runs (tooltip warning). Item
only in version one; fluids need the terminal fluid view. Polling is every 20 ticks
while processing. Recipe: IGI/RHR/IGI (four iron, two gold, two redstone, one hopper),
unlocked by gold.
