# ADR 0054 — Rust SDK v2 and the revised `factory_v1` ABI

Status: implemented; native, JVM and GameTest evidence in the implementation plan. Revises
the SDK and ABI table of [ADR 0037](0037-wasm-factory-v1.md); runtime, preflight fences,
limits, FE costs and [ADR 0044](0044-persistent-guest-state.md) state semantics are unchanged.

## Context

The first SDK was a thin wrapper over the imports. Every program hand-wrote
`#[no_mangle] factory_init/factory_rule`, kept `match index` arms in sync with `register`
order, supplied a panic handler and a `cfg(wasm32)` module, and built with a long
`cargo rustc` flag list. `wasm32-unknown-unknown`'s prebuilt `core` emits bulk-memory opcodes
the MVP preflight rejects, so guests had to avoid `copy_from_slice` and `core::fmt`. Rules
could not be tested natively, and a panic reached the player only as "unreachable". Nothing
has shipped, so source and ABI compatibility were not required.

## Decision

**Target.** Guests build for Rust's stable `wasm32v1-none` target (WebAssembly 1.0 plus
mutable globals, which the guests neither import nor export). Its prebuilt `core` contains
no post-MVP instructions. `sdk/rust/.cargo/config.toml` supplies the memory/stack link
arguments and a `cargo guest` alias.

**Program macro.** `#[factorycore::program]` on an inline module collects
`#[rule(ticks|seconds|minutes = N, key = "...")] fn name(cx: &Rule)` in source order and
generates `RULES` plus the two exports. It rejects at compile time, with spans, everything
`WasmProgram.register` would trap on (interval outside 10..=72,000 ticks, empty/long/
duplicate keys, more than 64 rules) and wrong rule signatures. The generated init traps if
the host's rule index differs from the position.

**Rule context.** Host calls exist only as methods of `Rule`, passed by reference to each
rule, so init-time or static host calls do not compile. `Machine<'cx>` borrows the `Rule`,
so a handle cannot outlive the callback whose table issued it. Staging methods return `()`
because every staging import returns 0 or traps. `log!` formats into a 128-byte stack
buffer.

**Native test host.** On non-wasm targets the same API calls `factorycore::testing`, a
thread-local mock that mirrors `WasmProgram`'s trap conditions and messages and commits
state only when a rule completes. It does not model instruction budgets or FE.

**ABI revisions** (module still `factory_v1`; strings are UTF-8 pointer/length, ≤ 128 bytes;
resource kinds 0 item, 1 fluid, 2 energy, 3 item tag):

| Change | Before | After |
| --- | --- | --- |
| Resource encoding | `kind, id, len, tag` with invalid combinations | `kind, id, len` with kind 3 = item tag |
| Machine imports | `keep`, `move_from`, `move_to` | `keep_stocked`, `move_to_storage`, `move_from_storage` `(i32,i32,i32,i32,i64)->i32` |
| Stock query | `stock(kind, id, len, tag)` | `stock(kind, id, len)` `(i32,i32,i32)->i64` |
| Failure report | none | `panic(text, len)` `(i32,i32)->()`: traps with "guest panicked: …", any phase, message cut to 128 bytes |

The SDK's default `panic-handler` feature sends `file:line: message` through `panic`.
Previously deployed artifacts that import the old names fail preflight on load; the
controller reports "Deployed Wasm no longer loads", as for any invalid artifact.

## Alternatives

- **WIT and the component model** (as Pumpkin and Spin use). One `.wit` contract would give
  typed bindings for many languages and model handles as resources. Chicory supports core
  modules and WASI preview 1 only; adopting components now means native Wasmtime per
  platform inside the mod, fuel-based metering in place of exact instruction counts, a guest
  allocator and `cabi_realloc` against the 128 KiB cap, and a new preflight. Revisit when
  Chicory's successor Endive ships component support, or when the ABI needs rich types,
  events or multi-language bindings. The public SDK exposes no ABI encodings, so a transport
  change touches only the macro output and the private `sys` module.
- **Declarative `macro_rules!` registration.** No proc-macro dependency, but weaker
  diagnostics and a less natural per-function syntax.
- **`linkme`/`inventory` distributed registration of free `#[rule]` functions.** Neither
  works reliably on `wasm32` linkers; the module attribute sees every rule directly.
- **Nightly `-Zbuild-std` with bulk memory disabled.** Same instruction set as
  `wasm32v1-none` but requires a nightly toolchain.
- **Typed state via `serde`/`postcard`.** Deferred; the byte API is sufficient and the
  example's 8-byte layout is relied on by tests.
- **Recoverable `Result` errors from host calls.** Rejected: a callback's commands are
  all-or-nothing, matching the friendly language's traps.

## Consequences

- Program crates are shorter and rule mistakes surface at compile time; `cargo test` runs
  rules without the game.
- The proc macro adds `syn`/`quote`/`proc-macro2` (build-time only) and `trybuild` (dev only)
  to `sdk/rust/Cargo.lock`.
- `core::fmt` is linked for panic reports: the smelter example grew from 706 to 9,237 bytes
  and the counter from 5,616 to 13,311 bytes, both far below 131,072. Disabling
  `panic-handler` (and supplying a handler) removes it.
- `scripts/verify_guest.py` builds the SDK from its workspace with `wasm32v1-none` and checks
  artifact size and import modules before JVM tests consume the artifacts.
