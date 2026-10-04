# AI implementation handoff

## Authority and current state

[SPEC.md](../SPEC.md) owns product behavior and release scope. This file owns implementation sequence, decision gates, and evidence tracking. [AGENTS.md](../AGENTS.md) owns the contributor workflow. Follow the current user's instructions; do not treat old proposals or research comparisons as additional requirements.

Current state: **P0 locally verified** on macOS arm64. The minimal loader entry, wrapper, JVM tests and build gates are implemented. Linux/Windows CI execution is pending. P1 is **in progress**; P2's cells/drives, ownership and wired topology are **in progress**; P3–P7 are **not started**. No complete product acceptance criterion has passing evidence yet.

## How an AI should proceed

1. Read the spec, contributor rules, testing policy, this plan, and accepted ADRs.
2. Select the earliest incomplete stage. Resolve its prerequisite decisions using current primary documentation and focused experiments.
3. Record the choice and evidence in an ADR. Routine technical/balance choices within the spec are delegated to the implementing AI; do not ask the user to select every library or numeric default.
4. If no feasible option satisfies a required behavior, report the concrete conflict and alternatives. Do not silently remove that requirement or treat an untested assumption as settled.
5. Implement one reviewable vertical slice with real behavior, affected docs, tests, and required checks. Temporary experiments use their own reproducible harness and must not bypass source/build verification.
6. Update stage progress and acceptance evidence with file paths, test names, commands, environment, and observed outcomes. Partial implementation stays partial.
7. Continue authorized work through subsequent stages. Report limitations honestly; an unavailable required test prevents verified completion of the affected stage.

## Decision gates

D01 is **selected and locally validated** in [ADR 0001](decisions/0001-platform.md). D02 is selected in [ADR 0002](decisions/0002-resource-accounting.md) with initial local core/handler evidence. D07 scheduler/workload targets and a measured portable baseline are recorded in [ADR 0003](decisions/0003-scheduling-workloads.md). D04's interpreter candidate is selected and its local P1 probe validated in [ADR 0004](decisions/0004-wasm-feasibility.md); cross-OS/production evidence remains pending. D03's initial ledger/ownership design is selected in [ADR 0005](decisions/0005-persistence-ownership.md), with world validation pending. Initial D06 balance/progression defaults are selected in [ADR 0006](decisions/0006-initial-balance.md); survival/powered/wireless validation remains P6 work. D05 and D07 release validation remain open. The implementing AI owns resolution within the stated product contract. Record a selected design before dependent coding; mark it validated only after its required evidence exists. Experimental implementation/builds needed to obtain evidence are allowed within the owning stage. They do not count as a completed gate or stage. Performance budgets are targets until measured.

| Gate | Decide and record | Selection/validation timing | Required evidence |
| --- | --- | --- | --- |
| D01 | Minecraft/loader/JDK, mod ID/package, supported OS, build/wrapper, pinned dependencies, license for original code, test harness, baseline Rust toolchain. | Select before P0 coding; validate before P0 exit. | Official compatibility sources; reproducible clean build; development client/server startup; CI toolchain setup. |
| D02 | Item/fluid/energy identities and exact units, integer representation/technical limits, aggregate overflow behavior, ABI quantity encoding, adapter partial-transfer contract. | P1 accounting and public storage API. | Boundary/overflow/round-trip tests and representative platform-handler compatibility. |
| D03 | Device ownership, schema, save ordering, job/reservation/cleanup transitions, portable identity/duplicate references, recovery and uncertain external writes. | P2 persistent storage. | Transition diagrams, migration fixtures, interruption experiment; explicit guarantee boundary for third-party inventories. |
| D04 | Wasm runtime, features/imports, Rust target/ABI, deployment flow, metering/host budgets, state lifecycle. | P1 feasibility proof; production scripting in P5. | Real Rust guest, runaway guest interruption, bounded compilation/host calls, startup/memory/throughput measurements on supported OS. |
| D05 | Friendly-language grammar/types, loop/function limits, statement vs persistent-rule semantics, diagnostic source locations. | P5 parser/compiler/editor. | Executable grammar fixtures, valid/error examples, conformance with common API and program lifecycle. |
| D06 | Recipes, numeric tiers/buffers/rates, finite fluid/energy capacities, gateway depth, fuel-generator conversion, power curves, wireless range/throughput; no optional-mod progression dependency. | Select before P2 block defaults; validate/finalize in P6. | Versioned balance table, checked recipe dependency graph, starter-to-endgame playtest, wired/wireless comparison. |
| D07 | Reference hardware/workloads, tick/latency/throughput/memory/network budgets, idle/large/overload scenarios, regression tolerance and scheduling limits. | P1 benchmark harness; freeze targets before scheduler optimization and P7 measurement. | Reproducible workload definition and baseline; final controlled measurements at P7. |

A gate may produce a selected interface/design before the dependent module is complete; its validation remains pending until the experiment or test exists. For rows expressed as “before implementation,” select before dependent production coding and validate by the owning stage's exit. Update decisions if later implementation disproves them. Do not move target budgets after a failure without documented product reasoning.

## Ordered stages

| Stage | Scope | Exit evidence |
| --- | --- | --- |
| P0 — Platform and real verification | Resolve D01. Add build/wrapper, formatting/static checks, dependency-boundary checks, real test harness, minimal loadable mod, client/server smoke checks. Replace the temporary source guard only in the same change as actual subprocess gates and CI setup. | Clean build and relevant nonempty tests; actual dev startup; documented exact commands. |
| P1 — Feasibility and core contracts | Resolve D02 and D04; establish D07 workloads/budget targets. Prove safe resource accounting, bounded scheduling concepts, and a real Rust guest before committing the rest of the architecture to a runtime. | Core invariants, overflow/partial-handler tests, sandbox stress results, benchmark baseline. |
| P2 — Storage, persistence, and wired topology | Resolve D03 and initial D06. Implement cells/banks/connectors, identities, save/reload, finite/infinite item progression, segments, gateway isolation, permissions, and root availability. | Ownership/reload fixtures, invalid-topology and authority tests, real storage round trips. |
| P3 — Transfers and simple player path | Implement item/fluid/energy adapters, staging, presets, hopper path, terminal item search/deposit/withdrawal, pagination, and accounting diagnostics. | Real inventory/tank/energy integration tests and hopper-to-terminal gameplay evidence. |
| P4 — Crafting | Add manual terminal grid/remainders, patterns, recursive jobs, machine leases, published branch jobs, cancellation/recovery, and stock-maintenance requests. | Dependency/reservation/contention/output attribution tests and representative machine demonstration. |
| P5 — Programming | Resolve D05. Implement versioned host API/SDK, runtime lifecycle, friendly language, deployment, templates, script diagnostics, and script-backed production. | Rust/DSL conformance, runaway/host-work limits, pause/restart/replacement tests, job ownership tests. |
| P6 — Complete player experience and progression | Finish terminal fluid/container and energy-item actions, job UI, friendly editor/JEI, wireless access/paired links, recipes/balance, player/admin docs. Finalize D06. | Client interactions with optional mods present/absent, wireless fault cases, survival progression playtest. |
| P7 — Release verification | Run the complete acceptance matrix, migrations/interruption suite, multiplayer and D07 performance scenarios. Package and document the supported configuration. | All acceptance rows have evidence; required checks pass; benchmark data and release limitations published. |

Dependency order is logical, not a requirement for a monolithic phase. Small vertical slices can cross a boundary if their prerequisites are resolved. Do not mark the first playable complete at P3 or omit remaining features because a demo runs.

## Acceptance ownership and evidence

Maintain these rows as implementation proceeds. Replace “Not implemented” with concrete evidence and status; do not replace the IDs. Tests may support several criteria.

| Criterion | Owning stages | Evidence/status |
| --- | --- | --- |
| A01 | P3 | Not implemented. |
| A02 | P2, P6 | Partial foundation: ResourceAccountingTest verifies capacity/claim preservation, safe downgrade rejection, infinite-item technical limits and exact aggregates. Ten StorageDriveGameTests cover physical item/fluid cells, sequential module upgrades retaining stock/claims/components, player consumption, recipe costs and earned Infinite. Survival progression and complete network usability remain pending. |
| A03 | P1, P2, P3 | Partial: ResourceAccountingTest verifies staged partial transfers, shared competing reservations, duplicate backing references and 30,000 seeded conservation operations. World integration remains pending. |
| A04 | P2, P3, P6 | Partial foundation: PlatformResourceTest checks exact component identity, mB/FE units and actual NeoForge handler limits. World capabilities, permissions and UI remain pending. |
| A05 | P2 | Partial: NetworkTopologyTest/NetworkAuthorityTest verify local labels, inherited intersections, stale/foreign scopes, invalid graphs and descendant suspension. Four NetworkNodeGameTests verify actual directional ports, private player commissioning, move/reload and clone quarantine. [WiredDiscoveryTest and two WireDiscoveryGameTests](modules/WIRE_DISCOVERY.md) add bounded physical reciprocal probes, real private views and nonloading chunk evidence. Automatic world region/event coordination, activation, chunk lifecycle and program integration remain pending; see [network contract](modules/NETWORK_AUTHORITY.md). |
| A06 | P4 | Not implemented. |
| A07 | P4 | Not implemented. |
| A08 | P4, P5 | Not implemented. |
| A09 | P5 | Not implemented. |
| A10 | P1, P5, P7 | Partial foundation: FairSchedulerTest verifies bounded queues, deadlines, independent shares, coalescing, cancellation and failure isolation. Six WasmProbeTest tests execute real Rust and enforce instruction/host/memory/stack bounds, malformed admission and trap discard. Production host/event/log limits and world overload integration remain pending. |
| A11 | P3, P4, P6 | Not implemented. |
| A12 | P2, P6 | Partial: private current grants, separate operations, explicit ownership transfer, operator administration and execution-time queued revocation pass core tests. [NetworkPersistenceTest](modules/NETWORK_PERSISTENCE.md) verifies canonical schema-two identities/grants, fixed bits, admission/reclamation, ownership transfer/revocation on compressed disk reload and schema-one migration. NodeRegistryTest/NodePersistenceTest and four required NetworkNodeGameTests add physical controller/gateway leases and preserve grants across movement. World discovery/activation, player permissions UI and multiplayer checks remain pending. |
| A13 | P2, P4, P7 | Partial: exact ledger/schema recovery, generation-based owner registry, real SavedData disk save/reload and corrupt-file preservation. Ten required StorageDriveGameTests cover physical lease transitions, block-entity lifecycle reload, copied blocks, break drops and preserved future schemas. Schema-three metadata preserves exact offline stock/claims/grants through legacy migration and disk reload, with canonical structural policies and cloned-node quarantine. Actual chunk unload/world restart, staging/jobs and interrupted external-write/save integration remain pending. |
| A14 | P0, P3, P6 | Partial: P0 client/dedicated server loaded with only Minecraft, NeoForge and Factory Core on macOS arm64; see work log. Real vanilla/NeoForge resource handler tests and headless storage-drive GameTests pass with optional mods absent. Client/optional-mod UI and complete network integration remain pending. |
| A15 | P1, P7 | Partial: [portable core baseline](evidence/core-v1-2026-10-03.md) records bounds, timing outliers and a slight large-throughput miss. [Wasm probe measurements](evidence/wasm-v1-2026-10-03.md) record cold/warm startup, allocations and execution. Full server/client/production-guest measurements remain pending. |
| A16 | P6 | Not implemented. |
| A17 | P6, P7 | Not implemented. |

## Suggested implementation instruction

> Implement this repository according to AGENTS.md, SPEC.md, and docs/IMPLEMENTATION.md. Resolve decision gates with evidence and ADRs, starting with P0, then continue through the ordered stages. Preserve the full first-playable scope. Keep docs and meaningful tests with each behavior change, run the required checks, and maintain acceptance evidence. Make routine choices within the contract autonomously; report genuine blockers and never claim unrun checks passed.

This instruction authorizes implementation when the user gives it to an agent. Its presence in a design repository does not itself start implementation.

## P0 work log

2026-10-03, macOS 15.3.1 arm64 (Apple M1), OpenJDK 21.0.12,
Python 3.13.11, Rust 1.95.0:

- Baseline `python3 scripts/verify.py`: passed all 15 original tooling tests.
- `python3 -m unittest discover -s tests/tooling -v`: passed 21 tests after
  wiring JVM dispatch/failure checks, an actual failing subprocess fixture and
  retaining unwired-language rejection.
- Initial `./gradlew build --write-locks`: failed because NeoForge JUnit support
  was not enabled. After enabling it, resolution failed on ArchUnit's newer
  SLF4J versus Minecraft's strict 2.0.9. Fixed by excluding only ArchUnit's
  SLF4J dependency and using the platform version; no checks were disabled.
- `./gradlew spotlessApply`: formatted the bootstrap tests.
- `./gradlew build --write-locks`: passed after the fixes; reviewed dependencies
  are recorded in `gradle.lockfile`.
- `./gradlew clean build`: passed using the committed lock configuration. Java
  compilation, JAR packaging, Spotless, PMD and three JVM tests passed with zero
  failures/skips. Tests: `ModMetadataTest.processedMetadataMatchesLoaderEntryAndHasNoTemplateTokens`,
  `ArchitectureTest.mandatoryClassesDoNotLoadOptionalIntegrations`,
  `ArchitectureTest.optionalBoundaryDetectsAnIllegalDependency`.
- Gradle wrapper JAR matches the official 9.2.1 checksum; distribution SHA-256
  validation is configured.
- `./gradlew runServer`: started with only Minecraft, NeoForge and Factory Core.
  The first run shared the default directory with the client and was interrupted
  (exit 130); that is not a graceful-shutdown pass. Final configuration isolates
  server/client directories and forwards server console input. The second run
  reported `Done (7.108s)` at 15:32:33, accepted `stop`, saved all dimensions,
  and reported `BUILD SUCCESSFUL`. Local log: `run/server/logs/latest.log`.
- `./gradlew runClient`: loaded Factory Core, initialized Apple M1 OpenGL/audio,
  and entered an integrated world with `Dev joined the game` at 15:29:50.
  This launch used the original `run/` directory; subsequent launches use
  `run/client`. Local log: `run/logs/latest.log`. Client window remains available
  for manual use; normal client exit has not been verified.
- First server launches logged a missing `server.properties`, then generated it
  and started. Upstream command/asset warnings appeared; no mod loading failure
  was observed. Runtime logs/worlds are ignored rather than committed artifacts.
- `./gradlew spotlessApply build`: passed after adding the packaged-JAR test;
  four JVM tests passed with zero failures/skips. The added test is
  `ModMetadataTest.developmentJarContainsEntryPointAndMetadataButNoTestFixtures`.
- `python3 scripts/verify.py`: passed documentation/acceptance tracking, all 21
  tooling tests and the required JVM build/check subprocess.
- `git diff --check`: passed.
- Remote Linux/Windows CI: not run. Workflow installs Java 21 and runs the
  canonical verifier; this is configuration, not execution evidence.

P0's local build/load requirements are met. Cross-OS execution and normal client
exit remain unverified. No full acceptance criterion or first-playable feature
is complete. Next stage: P1 accounting contracts, real Rust/Wasm feasibility and
reference workloads, with D02/D04/D07 evidence before dependent implementation.

## P1 accounting work log

2026-10-03, same macOS/JDK environment as P0; bootstrap commit `54bb0f7`:

- D02 selected before source in ADR 0002; [module contract](modules/RESOURCE_ACCOUNTING.md)
  records units, bounds, errors, thread ownership, handler limits and an example.
- `./gradlew spotlessApply build`: passed with 14 JVM tests (four bootstrap, six
  portable accounting and four real platform-handler tests), zero failures/skips.
- Implemented signed-long checked device accounting, arbitrary-precision unique
  backing aggregates, immutable exact identities, reservations, capacity changes,
  loaded-state rejection and explicit staging/uncertain-transfer records.
- Actual NeoForge ItemStackHandler/FluidTank/EnergyStorage checks passed. Item
  ports intentionally mutate at most one slot per call so known extraction cannot
  accumulate through several external mutations before a later handler throws.
- Generated conservation tests completed 30,000 operations with replay seeds
  0xFAC7001/0xFAC7002/0xFAC7003, including reservation competition, unload, resize,
  partial insertion, retries and cancellation.
- `python3 scripts/verify.py`: passed after module docs; `git diff --check` passed.
  D04 real Rust guest/sandbox,
  D07 scheduling workloads/baseline, and world adapters remain incomplete.

P1 is partial. These foundation tests do not prove completed A02/A03/A04 or a
playable storage network. Full acceptance ownership remains unchanged.

## P1 scheduling work log

2026-10-03, Apple M1 / macOS 15.3.1 / OpenJDK 21.0.12 / 8 GiB RAM:

- D07 target/design selection preceded scheduler implementation in ADR 0003.
  [Module contract](modules/SCHEDULING.md) documents trusted host-work costs,
  owner/queue limits, lazy quotas, cancellation, error behavior and an example.
- `./gradlew spotlessApply build coreBenchmark`: passed 18 JVM tests, zero
  failures/skips, and all four benchmark scenarios. Four FairSchedulerTest tests
  cover saturation/fairness, replacement/cancellation, time/queue/error bounds and
  preserving queued work when diagnostics fail.
- Three warmup and five measured passes per scenario produced 40,000 raw samples.
  Compressed raw data and factual target assessment are in the evidence document.
  Initial exploratory stock-depleting samples were excluded after correcting the
  harness to sustain transfers; production scheduler budgets were not relaxed.
- Every measured tick respected queue/visit/task/credit bounds. Idle registered
  owners required zero active work. A few timing outliers and slightly low mean
  large-workload throughput remain target misses, not reclassified passes.
- `python3 scripts/verify.py`: passed with the final scheduling docs/evidence,
  all 21 tooling tests and the JVM build/check gates. `git diff --check`: passed.

D04 real Rust/Wasm sandbox feasibility, world scheduling integration, live
networking and release performance remain incomplete. P1 stays in progress.

## P1 Rust/Wasm work log

2026-10-03, same macOS/JDK environment; parent revision `2377399`:

- Selected Chicory 1.7.5 interpreter and Rust 1.95.0 experiment in ADR 0004.
  Runtime/parser dependencies remain test-only and locked. Only the guest source
  tree bypasses the Rust bootstrap guard, with real Gradle/CI verification wired.
- Installed exact fmt/clippy/Wasm target; `python3 scripts/verify_guest.py` passed
  native/guest lint, formatting, one native boundary test and a real Wasm build.
- Initial Gradle wiring failed because a task was registered during test-task
  configuration; moved registration to the task container. Initial compilation
  found a missing scalar-type reader; added it. No checks were disabled.
- `./gradlew spotlessApply build --write-locks` passed the corrected probe and
  dependency lock update. `./gradlew spotlessApply build wasmBenchmark` passed
  the final 24 JVM tests, zero failures/skips, including six runtime tests.
- Tests cover exact i64, runaway/flood/deadline limits, finite memory, trap batch
  discard/non-resumption, malformed declarations/imports/proposals, recursive
  calls and valid nested operand-stack accumulation. No world host operations
  are implemented by this observation-only experimental ABI.
- [Measurements and raw evidence](evidence/wasm-v1-2026-10-03.md) preserve a
  107.7-ms first startup and the warm distributions. P5 must separately schedule
  startup and measure maximum artifacts; warm timing is not cold tick evidence.
- `python3 scripts/verify.py` passed documentation/acceptance tracking, 24 tooling
  tests, pinned Rust checks and the JVM build gates. Final guest bytes are an
  explicit Gradle test input, so changed artifacts invalidate cached ABI tests.

D04 is locally feasible and selected for P5 experiments; cross-OS execution,
production SDK/lifecycle and scheduler/host-work integration remain pending.
P1 remains in progress because full gate/platform evidence and performance
validation are incomplete. P2's persistent-storage slice may proceed after D03;
block/progression defaults require initial D06 selection.

## P2 initial ledger persistence work log

2026-10-03, same macOS/JDK environment; parent revision `5539981`:

- Selected D03's initial component schema and future canonical registry/lease
  ownership design before coding in ADR 0005. World ownership, move/break/clone,
  staging/job save ordering and external uncertainty remain pending.
- Implemented immutable exact stock/claim snapshots, bounded deterministic
  version-one binary envelope with integrity hash, explicit schema rejection and
  restore on the new owner thread with availability initially offline.
- First `./gradlew spotlessApply build` ran 30 JVM tests and failed one fixture
  lookup because the NeoForge harness uses another working directory. Loaded
  the committed fixture from the test classpath instead; no coverage was removed.
- Final `./gradlew spotlessApply build` passed 30 JVM tests, zero failures/skips,
  formatter/PMD/compiler/architecture gates and pinned Rust checks. Six new
  LedgerPersistenceTest tests cover exact huge counts/claims, all units/empty
  saves, the fixed schema fixture/determinism, invalid/corrupt/unsupported data,
  overflow/byte bounds and 2,000 generated reload operations (seed 0x5A7E001).
- [Persistence contract](modules/PERSISTENCE.md) records fields, units, limits,
  owner/thread rules, errors and example. Serialization failure preserves the
  live contents; world storage must enforce its byte budget before deposits.
- `python3 scripts/verify.py` passed documentation/acceptance tracking, all 24
  tooling tests and the JVM/Rust build gates. Final review also exercises
  checksum-valid negative quantities and duplicate stock declarations.

At this slice P2 remained partial. The pure component codec is not an atomic world save,
portable ownership registry, migration from a prior released schema, or proof of
external-inventory crash recovery. No full A13 acceptance is complete.

## P2 ownership and SavedData work log

2026-10-03, same macOS/JDK environment; parent revision `f56da59`:

- Inspected pinned Minecraft/NeoForge SavedData, DimensionDataStorage and
  IOUtilities. The upstream loader swallows failed reads; the adapter proves
  file absence before creating a registry and quarantines invalid typed/schema/
  ledger records without replacing original tags.
- Implemented one mutable backing owner/world, persisted generations/holder
  slots, portable/placed transitions, stale/foreign rejection, persistent live-copy
  conflicts and offline claim recovery. Physical block/item wiring and
  topology/permission endpoints remain pending.
- Added O(1) exact serialized-byte/entry counters and preflight admission/reclamation
  for deposits/claims. World/device limits are bounded technical defaults, not
  measured memory or latency guarantees.
- `./gradlew spotlessApply build` passed initial core/adapter and aggregate-bound
  tests; later revisions added the server hook and committed registry SNBT
  fixture. Final checks and live startup evidence are recorded below.
- [Ownership contract](modules/DEVICE_OWNERSHIP.md) documents units, owners,
  locations, errors, thread behavior, schema and remaining recovery work.
- `./gradlew spotlessApply runServer` reached `Done (1.941s)` at 17:37:08 and
  accepted `stop` at 17:39:33; all dimensions saved and Gradle succeeded. The
  server-start hook created a 101-byte compressed registry with schema one,
  one world UUID and zero devices (no storage blocks exist yet).
- `./gradlew runServer` reached `Done (1.593s)` at 17:41:54 and stopped normally
  at 17:44:15. The registry SHA-256 remained
  `74225a39d9d9ae6bad41c33bcb097b904e081ad21b6f611155a384e9098d1130`.
  This verifies empty live-registry creation/reload; the actual disk adapter
  tests provide nonempty stock/claim recovery evidence. Local logs/worlds stay ignored.
- `python3 scripts/verify.py` passed the registry SNBT fixture, all 24 tooling
  tests and JVM/Rust build gates. A final write-protection regression was then
  added so quarantined SavedData cannot be marked dirty. Final
  `python3 scripts/verify.py` passed 41 JVM tests with zero failures/skips,
  all 24 tooling tests and required formatting/static/architecture/Rust gates.
  `git diff --check` passed. Linux/Windows, large registry autosave performance,
  physical storage blocks, permissions/topology and staging/jobs remain unverified.

P2 remains in progress. No playable storage block or complete A13 is claimed.

## P2 physical cells and drive work log

2026-10-03, macOS 15.3.1 arm64 / Apple M1 / Java 21.0.12; parent `0e71869`:

- Selected initial D06 capacities, vanilla recipes and progression before source
  in ADR 0006. P6 survival/powered/wireless validation remains pending.
- Implemented four-slot drives, seven item/fluid cell tiers, single-owner portable
  references, server-thread sequential upgrade modules and vanilla recipe-book
  unlocks. A creative tab, vanilla-referenced models and player guide accompany
  the new behavior. Network routing stays offline pending validated topology.
- Review fixed reload of a portable lease before it could consume a generation,
  deactivation on failed removal, duplicate casing loot and unknown-data saves
  overwriting Minecraft position metadata. Recovery preserves wrong-typed data
  instead of converting it into an empty drive.
- Initial compilation found Optional.getOrThrow was unavailable; corrected to
  Optional.orElseThrow. Initial GameTest configuration referred to mods before
  their declaration, then hit a split Java module package; moved tests into a
  separate package and exercised module upgrades through player actions.
- PMD's borrowed ServerLevel references triggered CloseResource false positives;
  narrow method suppressions explain that Minecraft owns/closes those worlds.
  No rule or coverage was disabled.
- First eight GameTests passed but the JVM nonempty-test guard caught test-mod
  construction without a GameTest report property. Restricted JVM loaded mods
  to the main mod and scoped test reporting to the actual GameTest server.
- `./gradlew spotlessApply build --write-locks` passed 41 JVM tests and ten
  required Minecraft GameTests, zero failures/skips. The test source set has
  formatting/compiler/PMD coverage and is excluded from the production JAR.
  Existing Rust gates remain wired; no new production runtime dependency added.
- `./gradlew spotlessApply build` passed after finite fluid-capacity/claim and
  wrong-typed recovery-envelope regressions.
- Final `python3 scripts/verify.py` passed document/acceptance tracking, all 24
  tooling tests and the full Gradle build gates, including a fresh ten-GameTest
  server run. Report totals: 41 JVM tests, ten GameTests, zero failures/errors/
  skips. Existing Rust checks are satisfied from their unchanged verified inputs.
- `git diff --check` passed. Reviewed lock changes retain dependency versions;
  additional coordinates are existing NeoForge transitive launch/native libraries,
  with the new test configurations recorded. No generated worlds/logs entered
  the change. Remote Linux/Windows CI and actual client interaction were not run.

This is an initial physical-device slice, not complete A02/A03/A13/A14. Actual
chunk unload/full world restart, crash save ordering, network roots/gateways/
permissions, banks/connectors, transfers/UI, client/manual survival checks and
remaining release stages are pending. P2 and the full implementation stay active.

## P2 portable topology and current authority work log

2026-10-03, same macOS arm64 / Apple M1 / Java 21.0.12; parent `b85dd27`:

- Selected segment collapse, directed gateway validation, current grants and
  publication/invalidation rules in ADR 0007 before implementation. This slice
  contains portable core behavior; it does not activate world storage by itself.
- Implemented bounded per-region node/edge admission and incremental union/
  topological validation, valid ordinary loops, private segment scopes, branch
  descendant suspension and stable paginated local label queries.
- Added private current permission owners, operation grants, explicit ownership
  transfer, operator administration without implicit storage/scope bypass, and
  distinct branch/inherited gateway restrictions. Ancestor intersections stop
  denied inherited views without enumerating private children.
- Seven topology and five authority tests cover structural failure, generation
  invalidation, stale/foreign/forged descriptors, local lookup/pagination and
  policy/owner/grant semantics. A real FairScheduler queue rechecks revocation
  and topology before mutation. Generated unload/recovery uses seed 0x70F0106
  for 300 operations; a 4,096-node/3,072-gateway fanout advances one visit/call.
- Review restricted machine targets to actual interfaces and fixed a completed
  validation job retaining its publication flag after later edits. Added explicit
  immediate invalidation before budgeted world discovery has captured edges.
  Scope-checked permission-edit/ownership-transfer wrappers also verify that a
  restricted branch cannot mutate the network's grants or owner.
- `./gradlew spotlessApply compileJava pmdMain` passed initial production checks.
  `./gradlew spotlessApply build` passed initial and expanded behavior suites.
  Final `python3 scripts/verify.py` passed 24 tooling tests and full Gradle gates:
  53 JVM tests, ten required Minecraft GameTests, zero failures/errors/skips.
  Unchanged Rust inputs retain their prior verified gate results.
- `git diff --check` passed. No dependency, platform, CI or policy gate changed.
  Visit bounds are verified counts, not measured world/server latency claims.

P2/A05/A12 remain partial. Next work is physical controller/cable/gateway ports,
bounded affected-region discovery/scheduling, persistent network identities and
grants, and validated device activation. Real chunk/restart, multiplayer/client
and full performance evidence remain pending. The full goal stays active.

## P2 canonical network metadata work log

2026-10-03, same macOS arm64 / Apple M1 / Java 21.0.12; parent `3079cb6`:

- Selected ADR 0008 before source. Extended the existing canonical SavedData to
  schema two for private network identities, owner UUIDs, checked permission
  generations and fixed permission bits, with no second authority file.
- Recognized schema-one migration preserves world identity, exact ledger bytes,
  claims, physical generations/conflicts/locations and marks the migration dirty.
  Its original fixture remains unchanged; the test now explicitly checks schema
  two and the added empty network table instead of expecting schema one output.
  Unsupported-schema coverage advances from two to three, and a committed
  schema-two fixture protects the newly supported format.
- Added preflight global principal admission and O(1) binding accounting, with
  reclamation on revocation/transfer. Network/backing UUID collisions, unknown
  fields, invalid masks, ownership and duplicate records preserve original tags
  in the write-protected canonical authority. Unknown IDs cannot invent owners.
- Five NetworkPersistenceTest cases exercise fixed bits, private/operator defaults,
  compressed disk transfer/revocation with exact offline stock/claims, corruption,
  world binding reclamation and network/principal ceilings. Added a portable
  budget/overflow regression. Physical controller/gateway leases remain absent.
- `./gradlew spotlessApply compileJava pmdMain` passed.
  `./gradlew spotlessApply test pmdTest` and the expanded
  `./gradlew spotlessApply test pmdMain pmdTest` passed.
- Initial `python3 scripts/verify.py` passed 24 tooling tests but the sandbox
  denied the external Gradle cache lock. Re-ran the exact command with approved
  cache access: full verifier passed, 59 JVM tests and ten required Minecraft
  GameTests, zero failures/errors/skips. Existing Rust gate inputs were unchanged
  and their verified tasks were up to date.
- `git diff --check` passed; no build/dependency/CI/policy gates changed and no
  generated worlds entered the change. Client/multiplayer, actual chunk/full
  restart, abrupt interruption and remote Linux/Windows checks were not run.

P2/A12/A13 remain partial. Next are structural controller/cable/gateway identities
and leases, explicit physical ports, bounded affected-region discovery and
validated device activation. Terminal/transfer/crafting/scripting/wireless and
full release verification remain required.

## P2 physical structural ownership work log

2026-10-03, same macOS arm64 / Apple M1 / Java 21.0.12; parent `b01004a`:

- Previous goal turn made verified progress: canonical network metadata committed.
  Selected ADR 0009 before dependent source, separating structural leases from
  resource ledgers and permission generations.
- Added controller/gateway canonical IDs, exact positions, checked physical
  generations, persistent copy conflicts and gateway policy ownership. Node
  admission precedes network creation; a 4,096-owner fixture proves rejected
  commissioning cannot leave orphan grants. Cables own no mutable ledger/state.
- Registered actual controller/gateway/cable blocks and items, creative entries,
  vanilla models, recipes/unlocks and cable loot. Player placement commissions
  private controllers; explicit use commissions command-created unowned roots.
  Creative placement consumes references. Break/move retains one canonical
  identity and owner/grants; current inspection rechecks physical ownership.
- Gateway facing is downstream, opposite upstream; only opposing ports connect.
  Shared local port eligibility also covers controller/cable/drive faces and
  rejects vanilla blocks. This is not network discovery or activation.
- Schema three requires Nodes and migrates recognized one/two exactly. Original
  old fixtures remain unchanged; compatibility assertions now explicitly check
  the required new schema and empty node table. Unsupported-schema coverage
  advances to four. Fixed schema-three fixture protects ownership/policy bits.
- Four NodeRegistryTest and four NodePersistenceTest cases cover leases, copy/
  collision/duplicate/quota/overflow failures and compressed disk movement. Four
  required NetworkNodeGameTests exercise actual creative placement and movement,
  gateway faces, same-position lifecycle reload, cloned-block quarantine, future/
  wrong-type recovery, portable chunk rejection, recipes and commissioning.
- Initial compile caught a DeferredItem generic mismatch; corrected the cable
  registration type. Review reconciled the initial controller quartz recipe with
  ADR 0006's basic non-Nether progression: final recipe uses glass. Added current
  inspection and wrong-type recovery regressions; no expected behavior/check was
  weakened to pass.
- Initial direct Gradle invocation was denied access to the external cache lock;
  repeated with approved cache access.
  `./gradlew spotlessApply compileJava pmdMain` passed after the generic fix.
  `./gradlew spotlessApply test pmdMain pmdTest` passed. Expanded
  `./gradlew spotlessApply build` runs passed 67 JVM tests and fourteen required
  Minecraft GameTests. Final `python3 scripts/verify.py` passed 24 tooling tests,
  full compilation/format/static/architecture/JAR gates, 67 JVM tests and a fresh
  fourteen-GameTest server run, zero failures/errors/skips. Unchanged Rust gate
  inputs retained their verified up-to-date results.
- `git diff --check` passed. No dependency/build/CI/policy gates changed, no
  generated worlds entered source. Client/manual survival, multiplayer, actual
  chunk/full restart, abrupt interruption and remote Linux/Windows were not run.

P2/A05/A12/A13/A14 remain partial. Next: bounded affected-region world discovery,
shared scheduling/invalidation and validated storage activation, followed by
banks/connectors and the remaining transfer/terminal/crafting/program/wireless
stages. Full release acceptance is still incomplete and the goal remains active.

## P2 bounded wire discovery work log

2026-10-03, same macOS arm64 / Apple M1 / Java 21.0.12; parent `4d2d60a`:

- The prior committed turn made verified structural-ownership progress. The
  interrupted discovery turn made no file changes; revalidated the clean tree
  and took the next safe implementation action. Selected ADR 0010 before source.
- Added owner-thread discovery of bounded immutable physical descriptors,
  reciprocal adjacency, separate gateway virtual ports, collision/admission
  rejection, memoized probes and complete source-epoch-checked publication.
  Incremental graph validation shares the discovery visit budget.
- Added common FairScheduler resubmission/cost admission and a real nonloading
  ServerLevel reader. Absent chunks retain only known unavailable geometry; loaded
  air rejects cached geometry. Canonical root/gateway references validate before
  loaded descriptors are admitted. Cached gateways suspend descendants while
  retaining virtual anchors so upstream/sibling segments stay usable.
- Six WiredDiscoveryTest cases include private reciprocal scopes, unload/removal,
  bypass/collision, reader failure diagnostics, cancelled/stale publication, a
  4,096-node chain advanced one visit/call with at most one actual host probe,
  seed/node ceilings and common scheduler rescheduling/rejected admission.
- Two required WireDiscoveryGameTests read actual placed controllers/gateways/
  drives, inspect private inherited views, explicitly cancel before an edit and
  reject detached-root invention. A real remote getChunkNow remains null before
  and after a cached/unknown probe; loaded air does not reuse cached geometry.
  These tests own scheduling/cancellation; no automatic runtime hooks are claimed.
- `./gradlew spotlessApply compileJava pmdMain` passed initially. The next
  `./gradlew spotlessApply test pmdMain pmdTest` and initial full build caught a
  helper-signature mismatch after removal of an unused argument; corrected both
  declaration and call sites. Full builds then caught PMD CloseResource on a
  borrowed Minecraft chunk cache despite passing sixteen GameTests. A narrow
  method annotation documents that Minecraft owns it; no rule/test was disabled.
- `./gradlew spotlessApply build` passed after corrections. Final
  `python3 scripts/verify.py` passed document/acceptance tracking, 24 tooling tests
  and all Gradle gates, 73 JVM tests and a fresh sixteen-GameTest server run, zero
  failures/errors/skips. Unchanged Rust gate inputs remained verified/up to date.
- `git diff --check` passed. Reviewed source/module boundaries, unchanged build/
  dependency/CI/policy files and absence of generated worlds. No persistence
  schema changed. Actual client, multiplayer, chunk lifecycle/full restart,
  interruption and representative server latency were not run.

P2/A05/A10 remain partial. Automatic region ownership/coalescing, immediate
block/chunk/canonical-owner event invalidation and validated cell activation are
the next required integration. Producer publication alone activates no resources.
Remaining storage/transfer/terminal/crafting/program/wireless/release work stays
in scope; the full goal remains active.
