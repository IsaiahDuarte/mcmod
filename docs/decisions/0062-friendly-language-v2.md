# ADR 0062 — Friendly language version 2 (parity D1)

Status: accepted 2026-10-25 (PD2: v2 is a strict superset of v1; Wasm gains additive
`factory_v1` imports). Grammar, limits and diagnostics are frozen here before parser work.
Slices D2–D6 implement it, and each one is complete only when its fixtures in
`src/test/resources/program/v2/` compile and run. Implemented so far: D2, D3.

## Context

The parity audit (G8–G11) found that everyday SFM-style programs need moves between two
machines, a face and slot range per statement, richer conditions, wildcard filters with
exceptions, and redstone. ADR 0035 froze v1 without them. PD2 chose a superset rather than
a separate language. Every existing program, template and saved draft must keep its exact
meaning.

## Decision

### Compatibility

- There is **one** grammar and no version header. A program that uses no v2 form is a v1
  program and must compile to **identical IR**. `FriendlyV1PinTest` pins the IR of every
  v1 fixture and template; changing it is a breaking change and needs a new ADR.
- New keywords (`side`, `slots`, `else`, `and`, `or`, `not`, `has`, `redstone`, `emit`,
  `on`, `except`, the six side names) become reserved. A v1 program that used one of them
  as a loop variable no longer compiles. The error names the keyword and suggests a
  rename. Neither the templates nor the test fixtures used any of these names.
- Deployed programs are saved as source and recompiled on load
  (`ProgramControllerEntity`). A deployment that no longer compiles is kept but TRAPPED
  with "Deployed program no longer compiles", and it never runs a guessed meaning. Only
  the reserved-keyword case can cause this, and the controller shows the diagnostic.

### Grammar (additions to ADR 0035 in bold names)

```text
statement = … v1 statements …
          | "move" filter "from" endpoint "to" endpoint [ "limit" INT ]      (* widened *)
          | "if" condition block [ "else" ( block | ifstmt ) ]               (* widened *)
          | "emit" "redstone" INT "on" target ;
endpoint  = "storage" | target [ "side" SIDE ] [ "slots" INT [ ".." INT ] ] ;
SIDE      = "down" | "up" | "north" | "south" | "west" | "east" ;
condition = disjunct { "or" disjunct } ;
disjunct  = negated { "and" negated } ;
negated   = "not" negated | atom ;
atom      = "(" condition ")"
          | "stock" "(" filter ")" CMP INT
          | target "has" CMP INT "of" filter
          | "redstone" "(" target ")" ;
CMP       = "<" | "<=" | ">" | ">=" | "==" ;
filter    = term { "except" term } ;
term      = "item" "(" STRING ")" | "fluid" "(" STRING ")" | "tag" "(" STRING ")" | "energy" ;
```

`not` binds tightest, then `and`, then `or`, and parentheses group. `else if` chains are
allowed. v1's `if stock(f) CMP INT { … }` is the `atom` form and produces the same IR.

### Semantics

- **Moves (D2).** At most one endpoint may be `storage`. `storage` to `storage` is a
  compile error. Machine to machine goes through the shared transfer path
  (`WorldStorageTransfers` with two `External` endpoints): the items are staged and then
  delivered, and an uncertain delivery is never replayed. Both machines must be in the
  program's segment. Processing leases and fences (ADR 0053) apply to both ends, and a
  leased machine refuses the move with the busy reason. With a label on either side, the
  evaluator emits one move per (source, target) pair in stable order. A label × label
  product above 64 pairs is a trap.
- **Side and slots (D3).** `side` names the face of the *machine* that the access uses. The
  Interface must still touch that machine; `side` replaces only the capability face.
  `slots A..B` (inclusive, zero-based, `slots A` means one slot) are positions in the slot
  list of the face actually used. Sided containers re-index their slots per face (a vanilla
  furnace's top shows one slot, its input). On the Interface's own face, the range must lie
  inside its bound window: it narrows the window and never widens it. A *different* face
  is allowed only when the Interface is bound to every slot of its own face, meaning the
  player allowed the whole container. A narrowed binding cannot be mapped onto another
  face's numbering, so it is refused, never guessed. A provider with ADR 0053 fixed
  partitions refuses both, because its backing identity depends on the window. Every
  refusal is a visible log reason at run time. The compiler checks only that 0 ≤ A ≤ B <
  1,024. A view shares the binding's backing, rate budget, access mode and processing
  lease. A transfer that has to resume from its saved context after an interruption uses
  the Interface's own face and window, and an item that cannot go back there stays staged
  and visible.
- **Conditions (D4).** Evaluation short-circuits left to right. `stock` is one host query.
  `target has CMP N of filter` sums the matching contents of every selected machine. Its
  cost is one host query per machine plus one per extra observation page, and it counts
  against the existing 512-query budget. `redstone(target)` is true when any selected
  Interface is powered (one query per machine). An empty selection makes `has` sum to 0
  and `redstone` false.
- **Filters (D5).** An `item`, `fluid` or `tag` string may contain **one** `*`, anywhere in
  the ID *path*. It matches zero or more of `[a-z0-9_./-]`, and the namespace (if given)
  must be exact. Globs match registry IDs only, never component data or display names, and
  are compiled to a bounded matcher (no regex). `a except b except c` keeps `a` minus `b`
  minus `c`. Every term must be the same resource kind, at most 8 terms per filter.
  `energy` cannot take `except`.
- **Redstone out (D6).** `emit redstone N on target` (0 ≤ N ≤ 15) sets the output of each
  selected **Signal-mode** Interface (ADR 0059 C1) while this program is deployed. While a
  program drives it, the Interface ignores its own rule and shows "Set by program".
  Pausing, stopping, undeploying or a trap that ends the deployment returns it to its own
  rule within one evaluation interval. When two deployed programs emit to the same
  Interface, the highest strength wins. Emitting to anything that is not a Signal
  Interface does nothing, with a visible reason.

### Limits (unchanged unless listed)

Source 16,384 characters, 64 rules, 512 statements, depth 8 (an `else` block counts as
one level), 1,024 steps and 256 commands per invocation, and 512 host queries all stay as
ADR 0035/0056 set them. New: at most 16 operators per condition, 8 terms per filter, one
`*` per string, slot indices below 1,024, and 64 move pairs per statement.

### Diagnostics (fixed text keys, one-based line/column)

`v2.storage_to_storage`, `v2.reserved_keyword` (with the keyword), `v2.glob_count`,
`v2.glob_namespace`, `v2.except_kind`, `v2.except_energy`, `v2.filter_terms`,
`v2.condition_size`, `v2.slot_range`, `v2.emit_strength`, `v2.side_name`. Recovery and the
16-diagnostic cap are unchanged.

### Not in v2

- **SFM-style round robin (`each`).** Rejected. `for machine in machines("x")` already
  visits machines in stable order with a per-machine limit, which is how the existing
  round-robin template works. A rotating start offset would need saved per-rule state.
  Revisit only with a playtest finding (V1).
- **User functions, general loops and variables beyond loop machines.** These are still
  rejected for the termination reasons in ADR 0035.

## Alternatives

- **A separate v2 language with a header.** Rejected by PD2. It would split templates,
  editor support and documentation for no gain over a superset.
- **Glob as regex.** Rejected. It is hard to bound and easy to write slow patterns with.
- **`has` as a single-machine condition only.** Rejected. Summing over a label matches
  what `stock` means for storage and keeps label and variable targets interchangeable.

## Consequences

- `ProgramCommand.Move` loses its "exactly one side is storage" invariant in D2 and gains
  optional side and slot range fields. The IR records are extended additively, and v1 IR
  stays byte-identical (pin test).
- The Wasm ABI gains additive imports in D7 (`move2`, `has`, `redstone_in`, `emit`), and
  old guest artifacts keep loading.
- The editor (D9) highlights the new keywords, and the templates gain machine-to-machine
  smelting, refuel-when-low and a redstone-triggered export.
