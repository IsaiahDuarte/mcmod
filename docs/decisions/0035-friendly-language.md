# ADR 0035 — Friendly language version 1 (D05)

Status: selected and frozen for version 1; portable compiler and evaluator have JVM
evidence. Program Controllers, deployment, the editor and Rust host parity are later
P5 slices.

## Context

D05 must freeze grammar, types, conditions, iteration, functions, errors and limits
before the parser. R07: the friendly language compiles to validated native rules and
bounded callback work using the same API as Wasm; persistent rules use stable keys;
missed invocations coalesce; callbacks stage commands and traps discard the batch.

## Grammar

```text
program   = { rule } ;
rule      = "every" INT ( "tick" | "ticks" | "second" | "seconds" ) [ "as" STRING ] block ;
block     = "{" { statement } "}" ;
statement = "for" NAME "in" machines block
          | "keep" target "stocked" "with" INT "of" filter [ "from" "storage" ]
          | "move" filter "from" place "to" place [ "limit" INT ]
          | "craft" INT "of" "item" "(" STRING ")"
          | "if" "stock" "(" filter ")" ( "<" | "<=" | ">" | ">=" | "==" ) INT block
          | "log" STRING ;
machines  = "machines" "(" STRING ")" ;
target    = NAME | machines ;
place     = "storage" | target ;
filter    = "item" "(" STRING ")" | "fluid" "(" STRING ")" | "tag" "(" STRING ")" | "energy" ;
```

`#` starts a comment to end of line. Strings are double-quoted with `\"` and `\\`
escapes, at most 128 characters, single-line. IDs without a namespace mean
`minecraft:`. `tag` is an item tag. Keywords cannot be variable names.

## Semantics and limits

- **Rules** are persistent with stable keys: `as "name"` or `rule-N` by order.
  Duplicate keys are compile errors; redeploying replaces rules by key. Intervals are
  10–72,000 ticks (seconds × 20). An overdue rule runs once (missed runs coalesce).
- **Types**: a machine target is a loop variable or every Machine Interface with the
  exact, case-sensitive local label in the program's segment (stable ID order; an
  empty selection is a visible no-op). Filters are registry-ID or item-tag
  `ResourceFilter`s; `energy` is FE.
- **keep** requests the current deficit for that invocation so each machine holds the
  amount; **move** transfers at most `limit` (default 64, max 4,096) between storage
  and machines (exactly one side is storage); **craft** requests a crafting job for an
  item, deduplicated per rule; **if** compares available storage stock; **log**
  appends a bounded line. None of these creates another periodic task.
- **Iteration** is only over machine labels (≤ 64 per label). There are no user
  functions or general loops, so every rule terminates.
- **Limits**: 16,384 source characters, 64 rules, 512 statements, block depth 8,
  amounts 1–2^20; per invocation 1,024 evaluation steps and 256 commands. Exceeding an
  evaluation bound, or a failing host query, is a trap that publishes none of the
  invocation's commands.
- **Errors** report one-based line and column with a player-facing message; recovery
  continues at the next statement and reports at most 16 diagnostics.

## Alternatives

Compiling the language to Wasm would hide bounds inside the guest and duplicate the
sandbox; a native IR keeps evaluation inspectable and makes idle rules cost nothing.
General loops/functions were rejected for version 1 because termination and work
bounds would need fuel accounting the native path does not otherwise need.

## Consequences

Programs describe intent; the platform executes commands through the same
authorization, scheduling and staging as presets and terminal actions. Wasm programs
will produce the same command set through host calls.
