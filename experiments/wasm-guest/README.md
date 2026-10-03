# Rust/Wasm feasibility guest

This P1 experiment exercises an interpreter sandbox. It is not the production
Rust SDK or a player-deployable program. No Rust compilation runs in Minecraft.

Install Rustup, then from the repository root:

```text
rustup toolchain install 1.95.0 --profile minimal --component rustfmt --component clippy --target wasm32-unknown-unknown
python3 scripts/verify_guest.py
./gradlew test
./gradlew wasmBenchmark
```

Windows uses `python` and `gradlew.bat`. The script runs fmt, native/guest clippy
with warnings rejected, nonempty/unskipped native tests and the locked Wasm
release build. It writes all outputs beneath `build/`, including the artifact
consumed by JVM tests. These checks also run through the canonical verifier.

The experimental import `factory_probe.record(amount: i64) -> i64` accepts
nonnegative exact quantities; negative inputs return -1. Each successful callback
returns a bounded committed list; traps discard its staged list. This list is
an observation, not a world mutation. `probe_echo`, `probe_loop`,
`probe_host_loop`, `probe_grow` and `probe_trap_after_record` provide the ABI,
interruption, memory and trap fixtures. Native tests check quantity validation;
JVM tests execute the compiled artifact, rather than an equivalent Java function.

The linker requests 2 initial/32 maximum 64-KiB pages and a 64-KiB guest stack.
Unsupported LLVM target features are disabled, and preflight still checks the
actual instructions; compiler flags or custom feature metadata are not trusted.
See [runtime decision](../../docs/decisions/0004-wasm-feasibility.md) for bounds
and [measurement evidence](../../docs/evidence/wasm-v1-2026-10-03.md).
