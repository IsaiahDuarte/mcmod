# Programs

Portable P5 contracts. The language is frozen in [ADR 0035](../decisions/0035-friendly-language.md).
The Program Controller runtime is in [ADR 0036](../decisions/0036-program-controller-runtime.md);
the editor is below; bounded Wasm programs and persistent guest state are implemented.

## API

`FriendlyCompiler.compile(source)` returns `Result(program, diagnostics)`; `ok()` is true
when `program` is non-null. `Diagnostic(line, column, message)` is one-based.
`ProgramIr.Program(rules)` holds `Rule(key, interval, body)` with statements `For`,
`Keep`, `Move`, `Craft`, `If` and `Log`, targets `Variable`/`Label` and places
`Storage`/`Machine`.

`ProgramEvaluator.invoke(rule, host)` evaluates one invocation against a read-only
`Host` (`machines(label)`, `stock(filter)`) and returns staged `ProgramCommand`s
(`Keep(machine, filter, amount)`, `Move(filter, from, to, limit)` with a null side
meaning storage, `Craft(item, amount)`, `Log(text)`), or throws `Trap` and stages
nothing. Bounds: `MAX_STEPS` 1,024, `MAX_COMMANDS` 256, `MAX_MACHINES_PER_LABEL` 64.

## Example

```text
every 10 ticks as "smelt" {
    for machine in machines("smelter_in") {
        keep machine stocked with 64 of item("raw_iron") from storage
    }
    move item("iron_ingot") from machines("smelter_out") to storage limit 64
}
every 5 seconds {
    if stock(tag("logs")) < 32 {
        craft 16 of item("oak_planks")
    }
}
```


## Program Controller

`ProgramControllerEntity`: `draft()/draft(source)`, `deployedSource()`, `deployer()`,
`deployment()`, `state()`, `message()`, `program()`, `energy()`, `energyStorage()`,
`logs()`. Trusted runtime transitions `deploy/state/undeploy` are called only by
`WorldPrograms` (server thread): `deploy(actor, controller)` returns compiler or
permission diagnostics (empty on success); `control(actor, controller, state)` pauses,
resumes or stops (false without DEPLOY or deployment); `status(controller)` is
`running`, `no_power` or `offline`. Costs: `BASE_COST` 4 FE, `STEPS_PER_FE` 256; queue
`MAX_QUEUE` 64.


## Editor

Using a controller with View opens `ProgramMenu`. `ProgramPackets.Command(menu, session,
action, index, count, text)`: CHUNK uploads the draft in at most eight 2,048-character
chunks (index 0 restarts; the last chunk saves the draft, needing EDIT, and reports
diagnostics), CHECK compiles the saved draft, DEPLOY/PAUSE/RESUME/STOP go through
`WorldPrograms` (DEPLOY permission). Actions share a two-tick throttle. `View` carries
state, message, FE, runtime status, deployed flag, up to eight `line:column message`
diagnostics and eight recent logs; `Draft` sends the saved draft once on open.
`ProgramTemplates.ALL` lists seven compiling starters (import, export, stock,
round-robin, fluids, energy, craft-when-low).


## Wasm programs

`core.wasm.WasmPreflight.validate(bytes)` and `new WasmProgram(bytes, clock)` (throws on
invalid artifacts or failed init); `rules()` lists `RuleSpec(key, interval)`;
`invoke(index, host)` returns `Outcome(commands, instructions)` or throws
`ProgramEvaluator.Trap`, after which `failed()` is true. A guest's `panic` import traps
with its own message ("guest panicked: …"), which the controller log shows. ABI and limits:
[ADR 0037](../decisions/0037-wasm-factory-v1.md) as revised by
[ADR 0054](../decisions/0054-rust-sdk-v2.md); SDK: [sdk/rust](../../sdk/rust/README.md).


## Editor resource insertion

`ResourceExpressions.item/fluid` build escaped literals for insertion. With JEI installed,
dropping an item or fluid on the editor types one at the cursor
([ADR0043](../decisions/0043-jei-integration.md)).


## Persistent guest state

Wasm guests may keep one blob of at most 4,096 bytes per deployment through
`state_read`/`state_write` ([ADR0044](../decisions/0044-persistent-guest-state.md)). Writes
commit only with a successful callback. Friendly-language programs have no persistent
variables; their rules are stateless.


## Native authoring controls

[ADR 0051](../decisions/0051-program-authoring.md) documents the authoring contracts.
The friendly editor colors keywords/calls/literals/comments and underlines current
local compiler diagnostics; Save/Check/Deploy still compile on the server. Ctrl+Z
undoes, Ctrl+Y or Ctrl+Shift+Z redoes, and Tab inserts four spaces. History retains at
most 64 text snapshots per open screen and survives a GUI resize. Undo places the
cursor at the end. Paste, template replacement and resource insertion each make
one history step.

**Insert / complete** opens an in-place picker: click its category to cycle Items,
Fluids, Local labels and Keywords, type a search, page with arrows and select a row.
Escape returns to the draft. JEI is optional. Items/fluids insert escaped expressions;
labels insert `machines("label")`. Ctrl+Space instead completes the identifier or
literal at the cursor and initially picks its enclosing call's category. Local labels
are VIEW-authorized, case-sensitive, segment-only snapshots, 16 per server page.
Filtering applies to the current label page; Next fetches further pages. Other
categories search the public local registry, without exposing stored identities or
quantities. Ports are configured on Machine-mode Interfaces, selected through labels.

**Preview next rule** saves then evaluates one saved native rule using your current
VIEW scope. Saving needs EDIT; a View-only user can preview the existing saved draft.
Repeated clicks advance through rules. Wrapped preview text pages with arrows;
Escape returns to the draft. The snapshot shows resolved machine IDs, actions and
amounts or an explicit failure. It never deploys, moves resources, creates jobs,
reserves stock, emits program logs or spends FE. It samples current conditions;
future execution rechecks permissions, stock, power, ports and budgets. It returns
at most eight lines and states when more actions are omitted. Hover **View scope**
for the current response's full network/segment IDs; click to refresh it. A snapshot
is advisory and may become stale after rewiring or grant changes.

`ProgramPackets.AuthorRequest(menu, session, preview, rule, after)` requests a
16-label page after a case-sensitive label, or previews one native rule index 0–63.
The menu rechecks VIEW/reach/loaded scope and accepts at most one query per five
ticks. `AuthorView` carries scope IDs, at most 16 labels of 64 characters, the page's
`more` flag, selected/total rules and up to eight 192-character preview lines. Invalid
session requests disclose nothing. The payloads do not alter saved schema or ABI.

## Limits and overload (ADR 0056)

Each rule invocation has a 256-unit host-work budget: one unit per storage catalog page, and 1 + found/16 per label query. Exceeding it traps the invocation. Friendly rules allow at most 512 host calls, the same as Wasm. The program log keeps 16 lines of up to 160 characters, collapses immediate repeats, and keeps at most 8 guest lines per invocation. A refused scheduler admission sets status `throttled`, and an invocation 20 or more ticks late sets `delayed`; each is logged once. See [ADR 0056](../decisions/0056-program-limits-and-overload.md).

## Language version 2 (proposed, ADR 0062)

[ADR 0062](../decisions/0062-friendly-language-v2.md) freezes a strict superset of the v1
language: moves between two machines, `side`/`slots` per endpoint, `else`/`and`/`or`/`not`,
`has` conditions, one-`*` globs with `except`, and redstone `redstone(...)`/`emit`. **None of
it is implemented yet.** `src/test/resources/program/v2/` holds one fixture per form and
names the slice (D2–D6) that must make it compile. `FriendlyV1PinTest` fails if one
compiles early, or if any v1 program's IR changes.

