# ADR 0036 — Program Controller runtime for native rules

Status: selected; native headless evidence. The editor screen, templates and Wasm
programs are later P5 slices.

## Decision

A **Program Controller** is a wired block (`PROGRAM` role) holding an editable draft,
at most one deployed program, its deployer and deployment ID, a state (STOPPED,
RUNNING, PAUSED, TRAPPED) with message, a 10,000 FE buffer (receive ≤ 1,000 FE/tick, no
extraction) and 16 in-memory log lines. Block-entity schema 1 persists draft,
deployment, state and FE; a deployment that no longer compiles loads TRAPPED.
Breaking the block keeps the draft and FE in the drop, never the deployment. Unsupported
saved data is retained inert.

**Lifecycle** (`WorldPrograms.deploy/control`): deploy, pause, resume and stop require
DEPLOY at the controller; deploy compiles the draft (diagnostics are returned and
nothing changes on failure) and replaces the deployment and rule keys.

**Invocation.** Each tick, every due rule of a RUNNING controller in a VALID segment
queues one shared-scheduler task (network, deployment ID, key `rule:<key>`, cost 16);
overdue rules run once. A task re-checks the deployer's DEPLOY grant (losing it
pauses), requires 8 FE, evaluates the rule against a read-only host (labels resolve
through the authority's VIEW-checked machine query; `stock()` requires VIEW and counts
digital cells, ≤ 64 catalog pages), charges 4 FE + 1 FE per begun 256 steps
(ADR 0006), and publishes the staged commands. A trap charges 4 FE, publishes
nothing and sets TRAPPED. Logs are written immediately.

**Execution.** Published commands enter a bounded per-controller queue (64; a newer
invocation of a rule replaces that rule's unstarted commands) and run one at a time:
`keep` reads matching stock through the Machine Interface and moves the deficit (≤ the
interface rate) from storage; `move` routes from a machine's observed contents to
storage or from storage to a machine; both use the same `allKeys` catalog search
(bounded like presets) and `priorityGuarded` routes as presets, with staging and
uncertainty guards. `craft` submits a crafting job under the deployer, deduplicated per
rule and item while the job exists. Every non-success outcome writes a log line
(unloaded machine, shortage, denied, rate limit, full, ...).

## Alternatives

Running rules directly in a server-tick loop would bypass shared fairness; one task per
due rule gives per-deployment fairness. Executing commands concurrently would need
per-command staging ownership; one in-flight command per controller is bounded and
reuses the existing route ownership. Carrying the deployment in the dropped item would
copy a deployer's authority to another place.

## Consequences

Throughput is one routed command at a time per controller. Fluid/energy machine moves
use the same path but have no dedicated native test yet. Recipe IGI/RPR/IRI (four
iron, one gold, three redstone, one repeater), unlocked by a repeater.
