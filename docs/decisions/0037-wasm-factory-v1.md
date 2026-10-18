# ADR 0037 — Production Wasm runtime and `factory_v1` ABI

Status: selected; JVM and native evidence with a real Rust SDK guest, including in-game
upload and deployment through Program Controllers. Promotes ADR 0004's
Chicory 1.7.5 interpreter from test-only to an embedded runtime dependency.

## Decision

**Packaging.** Chicory `runtime` and `wasm` 1.7.5 are `implementation` dependencies,
embedded with `jarJar` (strict version) and placed on dev run classpaths; the lockfile
pins them. Core (`dev.izzy.factorycore.core.wasm`) depends on Chicory but never on
Minecraft or NeoForge.

**Preflight.** `WasmPreflight` keeps the probe's MVP declaration fence (scalar MVP +
sign-extension, ≤ 1 funcref table, one memory with maximum ≤ 32 pages, no start
section, bounded counts/locals/instructions/depth, custom sections stripped) with a
131,072-byte artifact limit and only `factory_v1` function imports from a fixed set.

**ABI `factory_v1`.** Guest exports `memory`, `factory_init()` and `factory_rule(i32)`.
Host imports (strings are UTF-8 pointer/length pairs, ≤ 128 bytes; kinds 0 item,
1 fluid, 2 energy; `tag` 1 means an item tag):

| Import | Signature | Effect |
| --- | --- | --- |
| `register_rule(key, len, interval)` | `(i32,i32,i32)->i32` | init only; returns rule index |
| `machines(label, len)` | `(i32,i32)->i64` | appends labeled machines; returns `base<<32 | count` |
| `keep(h, kind, id, len, tag, amount)` | `(i32…,i64)->i32` | stages `Keep` |
| `move_from(h, …, limit)` / `move_to(h, …, limit)` | same | stage `Move` machine→storage / storage→machine |
| `craft(id, len, amount)` | `(i32,i32,i64)->i32` | stages `Craft` |
| `stock(kind, id, len, tag)` | `(i32,i32,i32,i32)->i64` | available stock (host query) |
| `log(text, len)` | `(i32,i32)->i32` | stages `Log` |

Every effect is the same `ProgramCommand` the friendly language stages, so the
platform pipeline, authority and accounting are shared. Bounds: init 100,000
instructions, each callback 65,536 instructions, 512 host calls, 64 call frames,
4,096 operand values, a 50 ms deadline, 256 staged commands, 64 machines per label,
1,024 handles per callback. Any failure is a `Trap` that discards the callback's
commands and permanently fails the instance; there is no suspended stack, so reload
re-initializes a fresh instance and re-registers rules by key.

**SDK.** `sdk/rust` is a Cargo workspace (Rust 1.95.0, `wasm32-unknown-unknown`, no
WASI) with the `factorycore` crate and an example guest equivalent to a friendly
program. `scripts/verify_guest.py` runs fmt, clippy (native and wasm), tests and the
release Wasm build, copying `smelter.wasm` for JVM parity tests.

## Alternatives

A distinct Wasm command model would duplicate authorization and accounting; reusing
`ProgramCommand` makes parity checkable command-for-command. Wasmtime remains the
fallback if measured throughput is insufficient (ADR 0004).

## Consequences

Per-callback FE cost uses instructions: 1 FE per begun 256 instructions plus the base
cost (ADR 0006). Persistent guest state was deferred here and added as two additive
imports by [ADR 0044](0044-persistent-guest-state.md).


## Deployment — 2026-10-17

The editor's **Upload Wasm** sends the newest `.wasm` in `<game dir>/factorycore/programs`
as at most sixteen 8 KiB `ProgramPackets.Artifact` chunks (in order, one session);
the final chunk calls `WorldPrograms.deployWasm`, which needs DEPLOY and instantiates
and initializes a fresh guest before replacing anything. Controller block-entity
schema 2 persists the artifact; load and resume-after-trap always build a fresh
instance. Each callback needs 4 + 65,536/256 FE available and is charged 4 FE plus
1 FE per begun 256 instructions.
