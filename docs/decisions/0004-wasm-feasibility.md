# ADR 0004 — Rust/Wasm feasibility experiment

Status: accepted candidate for P5; P1 feasibility locally validated on macOS
arm64. Cross-OS and production integration validation remain pending.

## Candidate design

Evaluate Chicory 1.7.5's pure-Java interpreter using Rust 1.95.0,
`wasm32-unknown-unknown`, no WASI, no in-game compilation and explicit deployment.
The P1 guest is an experimental ABI/sandbox probe, not a production SDK or a
complete scripting implementation. Dependencies are initially test-only; no
runtime is embedded into the playable mod until the experiment passes.

The probe uses signed nonnegative i64 quantities through `factory_probe.record`.
Production `factory_v1` APIs remain a P5 contract. Guests must remain bounded in
artifact size, parser declaration counts, memory/tables, locals/stack, execution
instructions, host calls and host work. Startup/init is subject to the same
metering. Callback effects are staged and traps discard their batch.

Chicory exposes an experimental per-instruction listener. Pin its version and
test the listener path on every upgrade; do not infer safety from the presence
of the interface. The source parser accepts vector/local counts from binary
declarations, so artifact byte limits alone are insufficient against allocation
amplification. Validate declaration bounds before handing untrusted bytes to
the parser. Reject unsupported proposals/imports rather than implicitly enabling
WASI, threads, shared/multi-memory, GC or unbounded table growth.

## Alternatives

Wasmtime offers native fuel/epoch interruption and high throughput, but adds JNI
packaging and native builds for each supported OS/architecture. Compare against
it if the pure-Java candidate cannot meet bounded execution or measured targets.
AOT compilation adds cold-load time and compiler resource controls; the candidate
uses interpretation to avoid an unbounded JIT/AOT deployment step. A friendly-only
frontend cannot satisfy R07's Rust/Wasm requirement.

## Required evidence

Compile a real Rust artifact with pinned tooling and checked fmt/clippy/native
tests/Wasm build; run exact-quantity host round trips, infinite guest interruption,
host-call flooding, guest memory growth, invalid imports/declarations and trap
batch discard. Measure startup, memory, host calls and throughput on the selected
reference JVM. Linux/Windows CI setup is not cross-OS runtime evidence. Full
program lifecycle, SDK/API conformance and in-game deployment remain P5.

Primary sources inspected 2026-10-03:

- [Chicory 1.7.5 release](https://github.com/dylibso/chicory/releases/tag/1.7.5).
- [Pinned runtime sources](https://github.com/dylibso/chicory/tree/1.7.5/runtime):
  Instance listener/memory configuration, InterpreterMachine, MStack, HostFunction.
- [Pinned Wasm parser sources](https://github.com/dylibso/chicory/tree/1.7.5/wasm):
  parser vector/local/type handling and validation.
- [Wasmtime interruption](https://docs.wasmtime.dev/examples-interrupting-wasm.html).

## Local evidence and exact probe limits

The real Rust guest and six required JVM tests passed. Startup/allocation and
execution measurements, including the first cold instance, are in
[the evidence record](../evidence/wasm-v1-2026-10-03.md). The interpreter is
selected for the next implementation experiment; it remains a test dependency.
No production SDK/runtime or complete scripting acceptance is claimed.

The preflight admits at most 65,536 artifact bytes, 256 types/functions/globals/
exports, 16 parameters, one result, 16 function imports, one funcref table with
at most 256 initial entries, one memory with a required maximum of 32 pages,
16 element/data segments, 128 locals/function, 8,192 instructions/function and
64 structured control levels. Custom sections are stripped without decoding.
Only scalar MVP instructions plus sign-extension are allowed; table growth,
bulk memory, SIMD, GC, reference proposals and implicit start sections are rejected.
Chicory's semantic validator still checks the bounded artifact after preflight.

Callbacks admit 10,000 instructions, 16 host calls, 64 call frames and 4,096
operand values. Frame limits apply before allocation through a pinned interpreter
override; operand limits apply before the next instruction. A one-second probe
deadline is checked per instruction. These are experimental ceilings, not chosen
production server shares. Host calls only append bounded scalar observations,
so they cannot demonstrate accounting for a real inventory operation. Failure
discards staged observations and forbids reusing the instance.

First class-loading/startup exceeded 100 ms locally. P5 must schedule/bound
admission separately and measure worst permitted artifacts before offering a
server latency guarantee. Pure-Java interpretation avoids an in-game compilation
step but is not automatic evidence of bounded Java parser wall time. Production
guest counts, event/log/state limits and lifecycle remain P5 work. Linux/Windows
CI installs pinned Rust tools and runs these tests; remote execution is pending.

Observed experiment results also belong in [the implementation plan](../IMPLEMENTATION.md).
