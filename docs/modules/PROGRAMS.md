# Programs

Portable P5 contracts. Programs are written as plain sentences
([ADR 0068](../decisions/0068-sentence-programs.md); the player-facing reference is
[PROGRAM_LANGUAGE.md](../PROGRAM_LANGUAGE.md)). The brace language of ADRs 0035 and 0062 was
removed; its IR, limits and semantics carry over. The Program Controller runtime is in
[ADR 0036](../decisions/0036-program-controller-runtime.md); the editor is below; bounded Wasm
programs and persistent guest state are implemented. The offline MCP helper is in
[PROGRAM_HELPER.md](../PROGRAM_HELPER.md).

## API

`FriendlyCompiler.compile(source, vocabulary)` returns `Result(program, diagnostics)`; `ok()`
is true when `program` is non-null.
- **`Diagnostic(line, column, message, hint, example)`:** one-based, and `hint`/`example` may
  be null.
- **`Vocabulary`:** resolves item, fluid and tag names. The game uses `GameVocabulary.get()`;
  `Vocabulary.NONE` takes names literally.
- **`FriendlyCompiler.canonical(program)`:** the vocabulary-free form a deployment is saved and
  reloaded with.
- **`ProgramPrinter.print(program, vocabulary)`:** writes any program as sentences.
- **`ProgramIr.Program(rules)`:** holds `Rule(key, interval, body)` with statements `For`,
  `Keep`, `KeepAt`, `Move`, `Craft`, `If`, `Branch`, `Emit` and `Log`, targets
  `Variable`/`Label`, and places `Storage`/`Machine`/`MachineAt`.

`ProgramEvaluator.invoke(rule, host)` evaluates one invocation against a read-only
`Host` (`machines(label)`, `stock(filter)`) and returns staged `ProgramCommand`s
(`Keep(machine, filter, amount, access)`, `Move(filter, from, to, limit)` with a null side
meaning storage, `Craft(item, amount)`, `Log(text)`), or throws `Trap` and stages
nothing. Bounds: `MAX_STEPS` 1,024, `MAX_COMMANDS` 256, `MAX_MACHINES_PER_LABEL` 64.

## Example

```text
every 0.5 seconds called smelt:
    for each machine in smelter_in:
        keep 64 raw iron in machine
    move iron ingots from smelter_out to storage
every 5 seconds:
    if storage has less than 32 logs:
        craft 16 oak planks
```

## Program Controller

`ProgramControllerEntity`: `draft()/draft(source)`, `deployedSource()`, `deployer()`,
`deployment()`, `state()`, `message()`, `program()`, `energy()`, `energyStorage()`,
`logs()`. Trusted runtime transitions `deploy/state/undeploy` are called only by
`WorldPrograms` (server thread): `deploy(actor, controller)` returns compiler or
permission diagnostics (empty on success); `control(actor, controller, state)` pauses,
resumes or stops (false without DEPLOY or deployment); `status(controller)` is a
`WorldPrograms.RuntimeStatus` (`RUNNING`, `DELAYED`, `THROTTLED`, `NO_POWER`, `OFFLINE`), or
null before any rule has run. Costs: `BASE_COST` 4 FE, `STEPS_PER_FE` 256; queue
`MAX_QUEUE` 64.

Each controller holds its pending commands by rule (`core.scheduling.RuleQueue`). A rule that
runs again replaces whatever it still had queued, so missed work coalesces, and keeps its turn;
a rule with nothing queued joins after every waiting rule. One command runs at a time, taken
round-robin across rules, each rule's commands in their own order. So a rule whose batch never
drains (for example twelve storage reads on a ten-tick interval) cannot keep the next rule
waiting. Before M145 a re-run rule's batch moved to the end of one queue, and a later rule
could starve forever behind an earlier busy one. Commands over the 64 total are dropped and
logged as before.


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
The editor colors the language's words, numbers, quoted text and comments, and underlines
the live compiler problems (compiled with the game vocabulary). The log area shows the first
problem with its hint and example. Save, Check and Deploy still compile on the server.
- **Undo history:** Ctrl+Z undoes, and Ctrl+Y or Ctrl+Shift+Z redoes. History keeps at most 64
  text snapshots per open screen and survives a GUI resize. Undo places the cursor at the end.
  Paste, template replacement and insertion each make one history step.
- **Suggestions while typing (Q123, `ProgramSuggestions`):** the best item, fluid, label, face
  or next word shows faintly after the cursor, with up to five choices under the line.
  **Tab** accepts (adding a space), **Up/Down** choose, and moving the cursor hides them.
  Otherwise Tab inserts four spaces.

**Insert / complete** opens an in-place picker: click its category to cycle Items,
Fluids, Local labels and Keywords, type a search, page with arrows and select a row.
Escape returns to the draft. JEI is optional.
- **Inserts:** items, fluids and labels go in as a program names them (`iron ingot`,
  `fluid water`, `create:brass_ingot`, `"my chest"`).
- **Ctrl+Space:** replaces the name or word at the cursor, and picks the category of the
  cursor's slot.
- **Local labels:** VIEW-authorized, case-sensitive, segment-only snapshots, 16 per server
  page. Filtering applies to the current label page; Next fetches further pages.
- **Other categories:** search the public local registry, without exposing stored identities
  or quantities.

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

## Language

[ADR 0068](../decisions/0068-sentence-programs.md) defines the grammar, name resolution and
errors; [PROGRAM_LANGUAGE.md](../PROGRAM_LANGUAGE.md) is the reference.
- **Fixtures:** `src/test/resources/program/sentences/` holds one fixture per form, pinned to
  its IR in `program/sentences-ir.txt`.
- **Behavior carried over from ADR 0062 unchanged:**
  - machine-to-machine moves (`WorldPrograms.between`);
  - faces and slots (`ProgramIr.MachineAt`, `ProgramCommand.Access`, `WorldConnectors.View`),
    and a faced keep (`KeepAt`, counted through that face's view);
  - conditions, patterns and `except`, and redstone (`WorldSignals`).
