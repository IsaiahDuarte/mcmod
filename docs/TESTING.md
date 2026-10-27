# Verification requirements

How Factory Core is verified, what each suite proves and how to run it. Per-feature history is
in the [verification work log archive](archive/TESTING-LOG.md).

## Quick start

| Command | When | Time |
| --- | --- | --- |
| `python scripts/verify.py` | Every change (the inner loop) | about 45 s warm |
| `python scripts/verify.py --full` | Before merging or releasing; CI runs this | about 3 min |
| `./gradlew test --tests '*Name*'` | Focused JVM tests | seconds |
| `./gradlew runGameTestServer` | All native GameTests | about 30 s |
| `./gradlew spotlessApply` | Format before committing | seconds |

Graphical clients share one display. Wrap every run so it holds a lock and keeps the display
awake; macOS sleeps it after two minutes, causing "Failed to find a primary monitor":

```text
until mkdir /tmp/factorycore-gui.lock 2>/dev/null; do sleep 30; done
caffeinate -d -i -u ./gradlew runPresentationClient
rmdir /tmp/factorycore-gui.lock
```

## What the gates check

`verify.py` (fast) checks:
- **Docs and tooling:**
  - required files, titles and closed fences;
  - repository-local links;
  - matching acceptance-ID tables in SPEC and IMPLEMENTATION;
  - the tooling unit tests, where an empty or skipped suite fails.
- **Gradle `build`:**
  - Spotless (google-java-format) and PMD. CloseResource treats Minecraft's borrowed
    server, levels and window as allowed in `config/pmd/ruleset.xml`; any other suppression needs
    a stated reason;
  - `-Werror` compilation;
  - ArchUnit architecture rules (the portable core has no Minecraft types, and optional
    integrations stay off mandatory class loading);
  - JUnit with no empty or skipped runs;
  - the locked Rust guest build (`verifyRustGuest`: the `sdk/rust` workspace's fmt, clippy,
    native tests including the `factorycore::testing` mock and trybuild macro diagnostics, and
    `wasm32v1-none` builds of the smelter, counter and restock examples, each at most 131,072
    bytes and importing only `factory_v1`);
  - every native GameTest;
  - packaging, and compiling the client and JEI fixture source sets.

The local run uses the Gradle daemon; CI uses `--no-daemon`.

`--full` adds Gradle `releaseCheck`, the dedicated-server suites. Each runs in its own JVM on a
fresh flat world:

| Task | Proves |
| --- | --- |
| `runStagingRecoveryServer` | Accepted known/uncertain staging survives an orderly restart and is delivered or returned without replay |
| `runSaveOrderRestoreServer`, `runSaveOrderChunksRestoreServer` | Independent-save skew in both orders leaves exactly one inert ledger that an operator can adopt |
| `runWirelessLifecycleServer` | A partner chunk unload/reload keeps stock, reservations and identities, with no implicit chunk loads |
| `runGatewayDepthServer` | Configured gateway depth applies after a restart |
| `runAutomaticThroughputServer` | Paid upgrades make real chest import faster (Base > Advanced > Endgame ticks) with exact conservation |
| `runInterruptionKill/DataOld/ChunksOldServer` | A seed SIGKILLs itself mid-processing-job (exit 137 required). Restores prove exact resume, a `save_mismatch` quarantine for older SavedData, and an orphan-journal block for older chunks ([ADR 0057](decisions/0057-processing-journal-and-interruption.md)) |

The CI workflow only runs when started manually from the Actions tab. Pushes and pull requests
are not gated; the local run is the gate.

## Suite map

| Suite | Location | Proves |
| --- | --- | --- |
| Portable core unit and property tests | `src/test` | Accounting/ledger conservation (seeded generated operations), scheduler fairness and bounds, topology and authority, planner, friendly language, Wasm limits, persistence codecs and migrations |
| Native GameTests | `src/gameTest` | Real block entities, capabilities, chunks, menus/packets, permissions, recovery commands, crafting, programs and wireless in a running server |
| Terminal tour and restart | `runTerminalRestartClient` (`TerminalClientVerification`) | Every terminal mode, settings screens, program editor/authoring and wireless handheld through real input, then a separate-JVM restart restoring preferences and state |
| Presentation tour | `runPresentationClient` (`PresentationClientVerification`) | Orientations, lamps/bays, held items, tooltips, every screen at GUI scales 1–4 in the 427×240 minimum, upgrade installs, Jobs mode and access/recovery screens |
| JEI client | `runJeiClient` (`src/jeiTest`, JEI at runtime only) | Recipe transfer into the grid and ghost drops into the program editor through JEI's own runtime |
| Two-client multiplayer | `runMultiplayerCheck` | Concurrent withdrawals conserve, and revocation applies live, between two real clients |
| Server benchmark | `runBenchmarkServer` (manual, quiet machine) | Idle/small/large/overload/1,024-network workloads, tick-time percentiles, backlog and exact conservation |
| Microbenchmarks | `coreBenchmark`, `wasmBenchmark` | Portable core and Wasm timings and allocations |

Read the screenshots a graphical run produces; they have caught real layout and input bugs that
assertions missed. `Screenshot.grab` captures the previously rendered frame, so capture a few
ticks after a UI change.

Persisted formats have goldens. `WorldGoldenTest` decodes and re-encodes
`persistence/world-v13.dat`, a real save with every section populated, and requires the identical
tag. `AuditGoldenTest` feeds every recovery audit writer fixed inputs and requires the committed
`persistence/audit/<name>.snbt`. A codec or audit change that alters bytes must update a golden
deliberately, alongside a schema bump and migration where applicable.

Each graphical suite lists its screenshots once, in `src/clientTest/resources/captures/<suite>.txt`
(JEI: `src/jeiTest/resources/captures/jei.txt`). Gradle fails the run when a listed file is missing,
and `Captures.grab` refuses a name that is not listed, so adding a capture means adding one line.
Verification runs are declared with `verificationRun(...)` in `build.gradle`; dedicated-server runs
get their world from `flatWorld` and pass only through `requirePass`.

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

## Writing tests

- Test observable behavior and invariants, not private structure or mock call counts.
- Make it deterministic: fixed clocks and seeds, with the seed recorded in the assertion message.
- A bug fix needs a regression test. Where practical, prove it fails without the fix.
- Never weaken an assertion or a deadline to get green. If a requirement changed, say so in the
  commit and replace the coverage.
- GameTests that share a batch run side by side. Keep each fixture inside its template, or give it
  its own batch.
- A test that changes the world or the network must wait for the network to be valid again
  before asserting. Ports truthfully report OFFLINE while a network rebuilds.
- A fixture that reaches past its template (6×4×6 for `empty`) needs its own batch and must
  remove what it built when it ends. Placing the next batch does not clear blocks outside the
  template, and left-behind cables join later tests' networks.
- Batches run at the same time, not one after another (the whole suite takes about 20 s). A
  fixture with a large network rebuild disturbs tests running alongside it (M145); test large
  bounds in a unit test of the bounded code, as `RowWalkTest` does.

## Performance verification

Maintain small, large, and overload workloads, with documented active/idle programs, slots, distinct resources, transfer rates, crafting graph sizes, concurrent users, and mutation patterns. Include both huge quantities of identical items and many distinct metadata-heavy items.

Record hardware, OS, JVM/runtime versions, heap, mod list, warmup, repetitions, baseline revision, throughput, tick cost, tail latency, allocations, and memory. Establish numerical budgets before advertising performance. Run stable microbenchmarks in their harness and full server workloads on controlled hardware; do not claim precise server latency from noisy shared CI runners.

To profile allocation or CPU, run the benchmark under Java Flight Recorder:
1. Run `JAVA_TOOL_OPTIONS="-XX:StartFlightRecording=filename=/tmp/rec-%p.jfr,settings=profile,dumponexit=true" ./gradlew --no-daemon runBenchmarkServer`.
2. Inspect the server's recording (the `devlaunch` JVM) with `jfr print --json --events jdk.ObjectAllocationSample`.
3. Compare runs only when both used the same settings.

## Reporting

For each change, report the commands run, results, checks omitted with reasons, and any required checks that remain blocked. Existing unrelated failures should be identified, not silently relabeled as passes. Once applicable checks pass, repeat or broaden testing only for a concrete unresolved risk or another required gate.
