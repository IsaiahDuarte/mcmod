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
only in version one; fluids were added by [ADR 0045](0045-fluid-processing.md). Polling is every 20 ticks
while processing. Recipe: IGI/RHR/IGI (four iron, two gold, two redstone, one hopper),
unlocked by gold.


## Physical-target lease correction (2026-10-18)

Nested crafting-job schema4 adds optional `Dispatch.Target` (packed block position
in the persisted interface dimension). New dispatches capture the exact faced
machine before any input insertion and retain it through inserted/collected
updates, cancellation and reload. Two interfaces facing that same coordinate
contend on one lease; unrelated adjacent machines remain usable. Rotating the
interface cannot retarget output collection or input staging: work reports
`machine_changed` until the original orientation returns. Target chunk lookup
never loads a chunk. A copied interface coordinate is not a new processing owner.

The shared connector path checks every declared physical backing member against
canonical dispatch targets before rate admission and again before actual mutation.
Program moves, presets, terminal/external routes and cached trusted ports cannot
insert or extract while a processing job or its cancellation cleanup owns that
machine. Reciprocal acquisition derives existing provider group/partition identities
from loaded persistent markers and refuses `reserved` before pulling inputs when
any foreign hold or unresolved staging names that backing, even if every connector
has unregistered. `ExternalClaims.hasBacking` uses a bounded metadata index maintained
through restore/reserve/consume/release; no full claim scan or stock read is needed. Scheduled admission returns typed BUSY with no transfer/extraction;
cached primitive calls return0. Physical automation outside Factory Core remains
external to these leases and must not feed a processing machine.

Nested schemas1–3 still load. Their active dispatches have no proven target, so
they conservatively fence all six neighbors of the saved interface, including
while unloaded. They report `legacy_machine`; no target is guessed and no
inserted call is replayed. Cancel returns job-owned staging normally, retains the
bounded cleanup fence, then leaves unproven machine outputs in place without
claiming/refunding them. Save rewrites schema4 while preserving unknown targets.
Malformed/future target data quarantines the world unchanged. No outer world
schema changes. This is a deliberate migration restriction rather than a claim
that old uncertain processing calls can be resumed.

Persistence/core regressions cover exact targets and legacy/type rejection; native
regressions cover distinct-interface contention, script BUSY/conservation and
rotation. Full save interruption and multiplayer acceptance remain separate.


## Provider identity fence (nested schema5)

A target coordinate alone cannot prove that the same machine remains there. New
dispatches now capture a three-entry `Providers` map before any ingredient pull.
Each kind's UUID fingerprints the existing external backing group (world + kind +
persistent component UUIDs), member coordinates and registered block-entity types.
The shared existing `factorycore:external_identity` marker schema1 is initialized
only during new capture, after every described group/marker has validated. At
most3 groups ×64 loaded components are inspected, without capabilities or stock
queries. A provider without persistent block-entity identity and normalized backing evidence
(vanilla container or explicit `ExternalBackingProvider`) is unsupported for
new processing dispatch; the job retains its ingredient reservations and reports
`machine_identity`.

Before every insertion/collection pass and cancelled cleanup, read-only metadata
validation must match all saved fingerprints and the exact target. A fresh machine
at the same coordinate has no matching marker and reports `machine_replaced`;
resumption never initializes its marker or claims its output. Known saved provider
NBT preserves markers/types/groups across orderly reload and can resume. A changed
member/type/group also fails. New calls and cancellation cleanup never invent a
refund for inputs already accepted by the original machine. Cancellation releases
unused ingredients and eventually leaves unproven outputs in their actual machine.

Nested schemas1–4 migrate with an empty identity map. Known schema4 targets keep
their exact target fence; older unknown targets retain the neighbor fence. These
dispatches report `legacy_provider` or `legacy_machine`, and require cancellation
and inspection rather than inferred attribution. Writing schema5 preserves this
unknown state. Malformed, partial or future identity maps quarantine the complete
world unchanged. Outer world schema is unchanged.

The identity boundary is the same persisted provider metadata already used by
connectors. Administratively restoring exact old provider NBT restores that
provider identity; this cannot distinguish a byte-for-byte copied old provider
save from a legitimate reload. Do not clone provider identity markers or assume
these checks reconcile independent interrupted world/provider saves. Those
recovery gates remain explicit. Native regressions exercise fresh same-coordinate
replacement through active work and cancelled cleanup, read-only refusal to
initialize a replacement, and original provider NBT restoration without input replay.
