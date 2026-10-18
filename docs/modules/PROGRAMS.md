# Programs

Portable P5 contracts. The language is frozen in [ADR 0035](../decisions/0035-friendly-language.md).
The Program Controller runtime is in [ADR 0036](../decisions/0036-program-controller-runtime.md);
the editor is below; Wasm programs are not implemented yet.

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
