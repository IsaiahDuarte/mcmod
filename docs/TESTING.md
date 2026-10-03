# Verification requirements

## Current executable checks

Run from the repository root with Python 3.11 or later:

```text
python scripts/verify.py
```

This canonical command checks required files, document titles/nonempty content, Markdown conflict markers, closed code fences, repository-local inline file links, and matching nonempty acceptance-ID tables in the spec/plan. It then runs the tooling suite and fails if collection is empty, a test fails, or a required tooling test is skipped. It skips fenced examples for link/title checking and does not validate external URLs, heading fragments, reference-style links, or semantic correctness.

For focused tooling diagnosis, run: python -m unittest discover -s tests/tooling -v. This does not replace the canonical gate.

The canonical command also runs the required JVM `build` subprocess and its Rust
guest checks. Rust under `experiments/wasm-guest/src` is wired; other Rust/Kotlin
source remains rejected until its verification is configured. This is a temporary
bootstrap guard, not a complete source inventory or permanent language restriction.
Generated/build/dependency directories are excluded. Tooling tests exercise missing
docs/build/guest files, broken/escaping links, unwired sources, JVM dispatch/failure,
empty/skipped Rust summaries and valid docs.

The [CI workflow](../.github/workflows/quality.yml) runs the canonical command on Windows and Linux. It is prepared for GitHub; no remote execution or branch protection has been configured here.

## Implementation bootstrap gate

Before the first production module can pass verification:

1. Select and document Minecraft, loader, JDK, build, test, and guest-language toolchain versions.
2. Add reproducible build tooling and pinned/locked dependencies according to the platform ADR.
3. Replace the temporary source guard with actual checked subprocesses in the verification entry point, and install the necessary toolchains in CI.
4. Wire compilation, formatter checking, static analysis, architecture dependency tests, and unit/property tests. Add platform integration/GameTests and Rust guest/ABI tests as the corresponding modules arrive.
5. Fail on command failure or missing required tools. Never report success for skipped required gates. Record exact commands and supported platforms here.
6. Include one real behavior test demonstrating the new module's acceptance contract and a meaningful failure case.
7. Preserve current documentation/tooling checks and acceptance-ID tracking while adding the real gates. Replace the expected-rejection bootstrap regression with actual build/test dispatch tests in the same change; keeping a test that demands rejection after build verification exists would be incorrect.

Follow P0 and subsequent decision gates in [IMPLEMENTATION.md](IMPLEMENTATION.md). Platform integration and guest-specific checks become mandatory with their owning modules; no empty suite is accepted as a completed module's tests.

## JVM bootstrap commands

Selected versions and alternatives are in [ADR 0001](decisions/0001-platform.md).
Use JDK 21 and the wrapper (Windows: `gradlew.bat`):

```text
./gradlew clean build
./gradlew spotlessApply
./gradlew test
./gradlew runClient
./gradlew runServer
```

`build` includes `spotlessCheck`, PMD production/test analysis, compilation,
resource processing, JAR packaging and JUnit/ArchUnit tests. `spotlessApply`
changes source and does not replace checking. Compiler lint warnings are errors.
JUnit rejects zero executed tests or skipped required tests. Architecture checks
inspect compiled production classes; a negative fixture demonstrates forbidden
optional integration detection. The core dependency rule may be empty during P0
because no core module exists; require nonempty core coverage when P1 arrives.
Metadata tests require the processed loader descriptor to match the entry point
and reject remaining template tokens.

Direct dependencies and build plugins are pinned. Architecture tests exclude
ArchUnit's newer SLF4J dependency and use Minecraft's strictly pinned SLF4J 2.0.9;
this compatibility was exercised by the passing JVM tests. Resolve reviewed lock updates
with `./gradlew build --write-locks`; normal builds consume the committed lockfile.
The wrapper validates the Gradle distribution SHA-256. Minecraft build tools are
owned by pinned ModDevGradle/NeoForge, not a hand-maintained parallel graph.

Client smoke: reach the title screen and confirm Factory Core in the Mods list;
close the client normally. Dedicated development-server smoke: reach the `Done` startup message and
stop through the console. The NeoForge development launch used locally did not
require an `eula.txt` edit. Client/server logs and worlds are isolated under
`run/client` and `run/server`. Startup without JEI/Mekanism is only P0's portion of
A14. Handler and UI integration evidence is still required in later stages.
Record commands, actual outcomes and environment in the implementation plan.
Neither a JUnit metadata test nor `build` substitutes for real startup.

Documentation/tooling and JVM gates remain required together. Rust/ABI and actual
handler GameTests become mandatory when their owning modules arrive.

## Rust/Wasm feasibility checks

Install Rust 1.95.0 with rustfmt, clippy and wasm32-unknown-unknown as shown in
[the experiment guide](../experiments/wasm-guest/README.md). `build` depends on
`verifyRustGuest` and executes `scripts/verify_guest.py`: locked native/guest
clippy with warnings as errors, fmt check, nonempty/unskipped native tests and a
Wasm release build. JVM tests consume that real artifact; no checked-in binary
substitutes for compilation. Only this exact Rust source tree bypasses the guard.
CI installs the same pinned toolchain on Windows/Linux; execution is unverified.

Focused commands are `python3 scripts/verify_guest.py` and `./gradlew test`.
`./gradlew wasmBenchmark` writes manual raw timing/allocation samples. See
[ADR 0004](decisions/0004-wasm-feasibility.md) and
[local evidence](evidence/wasm-v1-2026-10-03.md) for limits and interpretation.
This probe is test-only; world host API/lifecycle/event/log/state checks remain P5.

## Required implementation coverage

| Area | Required behaviors |
| --- | --- |
| Storage/transfer | Exact identities/counts; partial acceptance; full destination; external mutation; upgrade/downgrade; arithmetic limits; conservation across staged moves. |
| Topology | Private scopes, reused labels, stale handles, rewiring/bypass rejection, unload/reload, nested gateway policies. |
| Crafting | Dependencies/cycles, missing inputs, competing reservations, busy machines, real output attribution, cancellation and recovery. |
| Scheduler/runtime | Fairness, idle cost, finite queues, infinite-loop interruption, memory and host-work limits, malformed guest requests. |
| Persistence | Versioned fixtures, migrations, interrupted transitions, explicit uncertain recovery, no blind replay. |
| Integrations | Actual handler contracts, optional dependencies absent, JEI dragging, representative inventories/tanks/energy sources. |
| UI/networking | Server validation, scopes, stale state, pagination/deltas, useful errors, actual client interaction for affected flows. |

Use deterministic clocks and recorded random seeds. Prefer observable output and state invariants to implementation details. Keep regression fixtures minimal and representative. Measure coverage to identify untested branches; no arbitrary coverage percentage is currently required.

## Performance verification

Maintain small, large, and overload workloads, with documented active/idle programs, slots, distinct resources, transfer rates, crafting graph sizes, concurrent users, and mutation patterns. Include both huge quantities of identical items and many distinct metadata-heavy items.

Record hardware, OS, JVM/runtime versions, heap, mod list, warmup, repetitions, baseline revision, throughput, tick cost, tail latency, allocations, and memory. Establish numerical budgets before advertising performance. Run stable microbenchmarks in their harness and full server workloads on controlled hardware; do not claim precise server latency from noisy shared CI runners.

## Reporting

For each change, report the commands run, results, checks omitted with reasons, and any required checks that remain blocked. Existing unrelated failures should be identified, not silently relabeled as passes. Once applicable checks pass, repeat or broaden testing only for a concrete unresolved risk or another required gate.

## Portable core benchmark

`./gradlew coreBenchmark` runs the version-one small/large/overload/idle harness
using a separate 2 GiB JVM. It is a manual evidence command, not a CI latency
assertion. See [scheduler contract](modules/SCHEDULING.md),
[target ADR](decisions/0003-scheduling-workloads.md), and
[initial measured evidence](evidence/core-v1-2026-10-03.md). Guest/runtime, live
world and client measurements remain required in their owning stages.
