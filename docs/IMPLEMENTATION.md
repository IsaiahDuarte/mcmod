# AI implementation handoff

## Authority and current state

[SPEC.md](../SPEC.md) owns product behavior and release scope. This file owns implementation sequence, decision gates, and evidence tracking. [AGENTS.md](../AGENTS.md) owns the contributor workflow. Follow the current user's instructions; do not treat old proposals or research comparisons as additional requirements.

Current state: **P0 locally verified** on macOS arm64. The minimal loader entry, wrapper, JVM tests and build gates are implemented. Linux/Windows CI execution is pending. P1 is **in progress**; P2's storage/ownership/wired integration and P3's explicit world-transfer primitives are **in progress**; P4–P7 are **not started**. No complete product acceptance criterion has passing evidence yet.

## How an AI should proceed

1. Read the spec, contributor rules, testing policy, this plan, and accepted ADRs.
2. Select the earliest incomplete stage. Resolve its prerequisite decisions using current primary documentation and focused experiments.
3. Record the choice and evidence in an ADR. Routine technical/balance choices within the spec are delegated to the implementing AI; do not ask the user to select every library or numeric default.
4. If no feasible option satisfies a required behavior, report the concrete conflict and alternatives. Do not silently remove that requirement or treat an untested assumption as settled.
5. Implement one reviewable vertical slice with real behavior, affected docs, tests, and required checks. Temporary experiments use their own reproducible harness and must not bypass source/build verification.
6. Update stage progress and acceptance evidence with file paths, test names, commands, environment, and observed outcomes. Partial implementation stays partial.
7. Continue authorized work through subsequent stages. Report limitations honestly; an unavailable required test prevents verified completion of the affected stage.

## Decision gates

D01 is **selected and locally validated** in [ADR 0001](decisions/0001-platform.md). D02 is selected in [ADR 0002](decisions/0002-resource-accounting.md) with initial local core/handler evidence. D07 scheduler/workload targets and a measured portable baseline are recorded in [ADR 0003](decisions/0003-scheduling-workloads.md). D04's interpreter candidate is selected and its local P1 probe validated in [ADR 0004](decisions/0004-wasm-feasibility.md); cross-OS/production evidence remains pending. D03's initial ledger/ownership design is selected in [ADR 0005](decisions/0005-persistence-ownership.md), with world validation pending. Initial D06 balance/progression defaults are selected in [ADR 0006](decisions/0006-initial-balance.md); survival/powered/wireless validation remains P6 work. D05 is selected and frozen in [ADR 0035](decisions/0035-friendly-language.md) with compiler/evaluator evidence; D07 release validation remains open. The implementing AI owns resolution within the stated product contract. Record a selected design before dependent coding; mark it validated only after its required evidence exists. Experimental implementation/builds needed to obtain evidence are allowed within the owning stage. They do not count as a completed gate or stage. Performance budgets are targets until measured.

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
| P6 — Complete player experience and progression | Finish terminal fluid/container and energy-item actions, job UI, friendly editor/JEI, wireless access/paired links, recipes/balance, in-game onboarding, recovery administration and player/admin docs. Complete the asset inventory. Finalize D06. | Client interactions with optional mods present/absent, asset/state/GUI-scale checks, recovery walkthrough, wireless fault cases and survival progression playtest. |
| P7 — Release verification | Run the complete acceptance matrix, migrations/interruption suite, multiplayer and D07 performance scenarios. Confirm presentation/onboarding evidence and package/document the supported configuration. | All acceptance rows and release asset rows have evidence; required checks pass; benchmark data and release limitations published. |

Dependency order is logical, not a requirement for a monolithic phase. Small vertical slices can cross a boundary if their prerequisites are resolved. Do not mark the first playable complete at P3 or omit remaining features because a demo runs.

## Playable milestones and presentation tracking

These demonstrations make progress observable within P0–P7; they do not replace
stage exits or narrow the first-release scope. All demonstrations below are
currently unverified. Existing component tests remain partial evidence only.

| Milestone | Stages / acceptance | Required demonstration and evidence |
| --- | --- | --- |
| Useful storage | P2–P3; A01–A03, A11–A14 | Craft/place a root, drive, cell, cables, Buffered Interface and terminal; import by hopper, search, deposit and withdraw. Fill storage and the player inventory without loss, disconnect/reconnect and restart with exact counts. Record a reproducible world setup, checks and actual client interaction. |
| Reliable utility network | P2–P3; A04–A05, A12–A14 | Use physical banks/connectors and representative item/fluid/energy handlers; prove sides, rates, permissions, movement/upgrades and actual chunk/restart behavior. Include ownership recovery and offline diagnostics. |
| Everyday automation | P3; A01, A03–A05, A12, A14 | Configure import/export/stock presets without scripts; demonstrate filtering, backpressure and actionable full/busy/offline states through player controls. |
| Crafting factory | P4; A06–A08, A11, A13 | Use the manual grid and saved patterns; request recursive machine processing and a published child recipe; show shortages, contention, cancellation, real output attribution and recovery. |
| Programmable factory | P5; A08–A10, A12–A13 | Run equivalent Rust/friendly-language examples; edit/deploy/pause/replace/reload, diagnose errors, and isolate runaway work while other operations progress. |
| Complete progression | P6; A02, A11, A14, A16–A17 | Follow in-game help through starter storage, presets/crafting, scripting, generator-powered wireless and Infinite. Record recipe dependencies, fuel/power demand, why each upgrade is useful, and a functioning endgame factory. |
| Finished release | P7; A01–A17 | Complete acceptance evidence, finished assets and onboarding, supported recovery, multiplayer/migration/interruption checks and full workloads on documented supported configurations. |

The immediate direction is to close remaining P1/P2 prerequisites and deliver
the useful-storage demonstration. Physical banks and explicit sided connector transfers have local headless evidence;
external catalog/claims, stable endpoint context and actual chunk/restart
verification remain P2/P3 work. Dependencies determine slice order. Keep A04 and other unfinished criteria
open even when the first item-storage loop works. Do not restart completed P0
work or defer all player feedback until the full backend is built.

Maintain [ASSETS.md](ASSETS.md) alongside each feature: models/textures/icons,
relevant device states, screens, translatable messages and client evidence.
Establish the visual style with the starter devices; implement their finished
presentation in the same feature sequence. P6 closes remaining inventory gaps
and P7 verifies them. Asset implementation and client verification are separate
statuses; placeholders cannot count as release-complete.

For each milestone, append evidence here with revision/environment, exact
commands, setup/actions, expected and observed outcomes, artifact/test paths and
remaining failures. Never infer a playtest pass from a recipe-loading test.
The copyable full-release continuation task is in [AGENT_HANDOFF.md](AGENT_HANDOFF.md).

## Acceptance ownership and evidence

Maintain these rows as implementation proceeds. Replace “Not implemented” with concrete evidence and status; do not replace the IDs. Tests may support several criteria.

| Criterion | Owning stages | Evidence/status |
| --- | --- | --- |
| A01 | P3 | Partial: headless BufferImportGameTests exercise no-code real hopper-to-cell imports, exact components, full cell/buffer/hopper retention and resumption. Import/Paused and owned-cleanup player controls are wired. Eight TerminalGameTests now add the physical hopper/buffer/cell/search/menu/player loop, exact deposit/withdraw, stale/full/revoked/closed sessions and recipe evidence. The graphical fixture adds actual search/Take1/paging/deposit-payload and scale2–4 evidence; manual/remaining client, multiplayer, chunks/full-server restart remain pending. |
| A02 | P2, P6 | Partial foundation: ResourceAccountingTest verifies capacity/claim preservation, safe downgrade rejection, infinite-item technical limits and exact aggregates. Ten StorageDriveGameTests cover physical item/fluid cells, sequential module upgrades retaining stock/claims/components, player consumption, recipe costs and earned Infinite. Survival progression and complete network usability remain pending. |
| A03 | P1, P2, P3 | Partial: ResourceAccountingTest verifies staged partial transfers, shared competing reservations, duplicate backing references and 30,000 seeded conservation operations. TransferRecoveryTest adds known-partial snapshot recovery and pending-call quarantine without replay/refunds; TransferRegistryTest and TransferPersistenceTest add bounded canonical records, exact owned ledger/staging disk recovery and strict schema-four migrations. Seven WorldStorageTransfersGameTests add scheduled physical cell/bank endpoints, exact unreserved/partial FE moves, queued revocation/disconnection/stale denial, private-child scope and cancellation. Nineteen ConnectorGameTests add explicit physical foreign transfers, overlap/alias rejection and uncertainty; indexed backing invalidation preserves staging and releases capability references. Eight saved-staging recovery scenarios additionally prove validated delivery/owned cleanup, legacy and principal rejection, a 250-item two-step quantum and actual chest block-entity NBT reacquisition. Bounded scheduled external stock observations have exact-key/pagination/nonmutation and queued VIEW-revocation evidence. Portable ExternalClaims/ExternalClaimPort tests add shared hold bounds, shortages, exact actual debit and generated accounting; canonical schema-six foreign hold persistence now has fixed fixtures/disk reload/strict rejection evidence; scheduled principal holds/protected extraction now have item/fluid/FE and uncertainty evidence; bounded live exact-key catalog discovery now has metadata/world privacy/cursor evidence; bounded global priority/persisted round-robin routes now have conservation, cancellation, revocation, uncertainty and migration/reload evidence; the nine-slot buffer/NeoForge handler now has component/backpressure/conservation evidence, canonical schema-eight ownership/migration and physical hopper/movement/copy/recipe evidence; explicit buffered item endpoints now have scope, rate, partial staging and recovery evidence; automatic Import/Paused/owned-cleanup controls have hopper/backpressure/revocation evidence; bounded all-key server discovery/search now has exact component/hold, BigInteger, scope and quota evidence; terminal frontends, remaining presets, crafting job claims and full server restart/interruption integration remain pending. |
| A04 | P2, P3, P6 | Partial: the standalone [Fuel Generator](decisions/0027-fuel-generator.md) now converts vanilla fuel to FE with exact remainders, a shared 80 FE/tick cap and conserved bank push; automatic machine item/mB/FE Import/Export/Stock now has bounded shared runtime and native conservation/staging evidence; player preset authoring and actual Stock client dispatch now have evidence, with selected tanks/multiport/release recovery still open. Foundation: PlatformResourceTest checks exact component identity, mB/FE units and actual NeoForge handler limits. [EnergyBankAccessTest and EnergyBankOwnershipTest](modules/ENERGY_BANKS.md) add finite bank rates, identity-based movement budgets, simulation/claim preservation, seeded conservation and portable bank transitions. Six EnergyBankGameTests add actual bank capabilities, shared faces/rates, claims, populated upgrades, break drops, reload/copy quarantine and recovery/recipe loading. Scheduled digital cell/bank routes now have WorldStorageTransfersGameTests evidence; sided connector routes now have nineteen real item/mB/FE/side/access/rate/overlap/fault cases. Bounded all-key discovery/priority routing and typed ResourceFilter/StockTarget component evidence now exist. Filtered Import now has canonical policy/rate storage and native sample/tag/clock controls with component/world evidence. Automatic filtered Export/Stock now has bounded priority discovery, physical deficit/rate caps and native compass/chest evidence. Native buffer Settings/Inventory now has headless draft, authority and partial/full inventory evidence; actual buffer and machine settings controls now have scales2–4 evidence in the 2026-10-17 client record. Other resource settings, permissions UI and actual chunks/restart remain pending. |
| A05 | P2 | Partial: Physical MachineInterface local labels and gateway-local/direct endpoint isolation now have native evidence; player binding/preset controls and small native restart/root-unload evidence now exist; selected tanks, broader recovery and program integration remain pending. NetworkTopologyTest/NetworkAuthorityTest verify local labels, inherited intersections, stale/foreign scopes, invalid graphs and descendant suspension. Four NetworkNodeGameTests verify actual directional ports, private player commissioning, move/reload and clone quarantine. [WiredDiscoveryTest and two WireDiscoveryGameTests](modules/WIRE_DISCOVERY.md) add bounded physical reciprocal probes, real private views and nonloading chunk evidence. [WorldNetworksGameTests](modules/WORLD_NETWORKS.md) verify production post-tick activation, immediate disconnection, coalescing, invalid roots/independent regions, private views and queued revocation. Gateway-only/staged chunk lifecycle and program integration remain pending; see [network contract](modules/NETWORK_AUTHORITY.md). |
| A06 | P4 | Partial foundation: [pattern items and Crafting Units](decisions/0030-patterns-and-crafting-units.md) with terminal encoding and a segment-scoped pattern index (four CraftingUnitGameTests); portable [pattern model and bounded recursive planner](decisions/0029-crafting-planner.md) with eight CraftingPlannerTest cases (cycles, shortages, priority alternatives, surplus/byproduct reuse, limits). [Persisted jobs](decisions/0031-crafting-jobs.md) now reserve once, stage and attribute outputs, run through the shared scheduler at Crafting Units, wait on missing patterns/grants/storage, and cancel with exact cleanup (six CraftingJobGameTests, CraftingJobTest, CraftingJobPersistenceTest). A terminal Jobs mode now previews, starts, tracks and cancels jobs (TerminalJobGameTests). [Processing patterns and Crafting Interfaces](decisions/0032-processing-patterns.md) now lease machines, require clean outputs and attribute collected outputs (five ProcessingJobGameTests with a real blast furnace). Branch publication and [keep-stock rules](decisions/0034-keep-stock-requests.md) now exist (BranchCraftingGameTests, KeepStockGameTests). Fluid processing, graphical and multiplayer checks are missing. |
| A07 | P4 | Partial: [published branch recipes](decisions/0033-branch-recipe-publication.md) — a root job uses a child branch pattern with one root reservation and no branch storage/handles; a gateway without branch CRAFT withholds them (two BranchCraftingGameTests). Player gateway-policy editing and graphical/multiplayer checks remain. |
| A08 | P4, P5 | Partial: crafting jobs reserve once in the shared ledger and processing dispatches lease machines; a second job waits `busy` with an actionable reason (ProcessingJobGameTests). Script competition is not implemented. |
| A09 | P5 | Partial foundation: D05 friendly language is frozen ([ADR 0035](decisions/0035-friendly-language.md)) with a compiler and bounded evaluator (FriendlyCompilerTest, ProgramEvaluatorTest). [Program Controllers](decisions/0036-program-controller-runtime.md) deploy, invoke, pause on lost grants, trap and persist (five ProgramGameTests). A native editor with chunked drafts, diagnostics, controls and seven templates exists (ProgramEditorGameTests). Production Wasm (`factory_v1`, ADR 0037) with a Rust SDK matches the friendly twin command-for-command (WasmProgramTest); in-game upload and deployment of the Rust guest works (WasmProgramGameTests). Persistent guest state (ADR 0044, schema 3) has JVM and native evidence. Graphical editor checks are missing. |
| A10 | P1, P5, P7 | Partial foundation: production `WasmProgram` bounds instructions/host calls/frames/operands/time per callback and fails instances on traps (WasmProgramTest); friendly rules bound steps/commands (ProgramEvaluatorTest); FairSchedulerTest verifies bounded queues, deadlines, independent shares, coalescing, cancellation and failure isolation. Six WasmProbeTest tests execute real Rust and enforce instruction/host/memory/stack bounds, malformed admission and trap discard. Production host/event/log limits and world overload integration remain pending. |
| A11 | P3, P4, P6 | Partial prerequisite: a native server-owned [manual crafting grid](decisions/0028-terminal-manual-crafting.md) now crafts from the player inventory with storage gathering, exact remainders and bounded batches (four TerminalCraftingGameTests); bounded server registry-text search and stable exact-count pages are implemented; physical item terminal/menu controls and headless flows are implemented; partial graphical item-screen evidence is [recorded](evidence/terminal-client-v1-2026-10-13.md); remaining R08 checks are pending; [asset inventory](ASSETS.md) records placeholders and missing content. |
| A12 | P2, P6 | Partial: private current grants, separate operations, explicit ownership transfer, operator administration and execution-time queued revocation pass core tests. [NetworkPersistenceTest](modules/NETWORK_PERSISTENCE.md) verifies canonical schema-two identities/grants, fixed bits, admission/reclamation, ownership transfer/revocation on compressed disk reload and schema-one migration. NodeRegistryTest/NodePersistenceTest and four required NetworkNodeGameTests add physical controller/gateway leases and preserve grants across movement. Live wired scope/activation and execution-time scheduled physical transfer permissions/private segments are implemented with seven WorldStorageTransfersGameTests. Connector cases additionally verify source/target current grants, denied parent/private-child transfers, config revocation and stale provider handles. Player permissions UI and multiplayer checks remain pending. |
| A13 | P2, P4, P6, P7 | Partial: [audited canonical cell/bank conflict repair](decisions/0023-canonical-cell-repair.md) and [buffer repair](decisions/0024-buffer-conflict-repair.md) now support exact original holders without unresolved transfers, preserving owned quantities/configuration and bank rate spending; repaired buffer presets need authorized reapply. [Controller/gateway repair](decisions/0025-network-node-repair.md) now retains grants/policy and logical identities under fresh topology validation. [Connector/machine repair](decisions/0026-foreign-port-repair.md) preserves local configuration and foreign contents/holds and requires authorized machine redeployment; unresolved/unscoped legacy staging remains open; cell independent-save mismatches now have operator adoption ([ADR 0046](decisions/0046-independent-save-adoption.md)) with two-JVM injection evidence for older SavedData, while bank/buffer/node mismatches remain open. operator inspection, audited original-owner known cleanup and empty completed-record retirement now have server command/native evidence; full ownership reconciliation remains open. required dedicated two-JVM recovery now preserves accepted known/uncertain staging and verifies delivery/return without replay; independent saves/administration remain open. a separate-JVM orderly integrated-server reopen now preserves complete canonical/physical state and resumes item Stock (see 2026-10-17 restart evidence); a native one-chunk unload/reload also preserves ownership and stops offline mutations; independent saves/interrupted calls remain open. Exact ledger/schema recovery, generation-based owner registry, real SavedData disk save/reload and corrupt-file preservation. Ten required StorageDriveGameTests cover physical lease transitions, block-entity lifecycle reload, copied blocks, break drops and preserved future schemas. Schema-three metadata preserves exact offline stock/claims/grants through legacy migration and disk reload, with canonical structural policies and cloned-node quarantine. Seven EnergyBankGameTests add bank lease/reload/copy/recovery paths, with a strict bank schema fixture in the JVM suite. TransferRegistryTest and TransferPersistenceTest add schema-four canonical staged-resource disk recovery and preserved uncertain intents. Connector schema-one fixtures, compressed structural lease reload, movement/future-data cases and indexed in-call backing invalidation add partial evidence. World-scoped provider identity markers have compressed chest NBT and copied/future identity checks; accepted operation context now persists under schema five; explicit saved-staging progress/cleanup now has headless evidence including chest NBT reacquisition; automatic recovery dispatch, persisted jobs, interrupted external-write/save integration and the supported administrative recovery workflow remain pending. |
| A14 | P0, P3, P6 | Partial: P0 client/dedicated server loaded with only Minecraft, NeoForge and Factory Core on macOS arm64; see work log. Real vanilla/NeoForge resource handler tests and headless storage-drive GameTests pass with optional mods absent. Client/optional-mod UI and complete network integration remain pending. |
| A15 | P1, P7 | Partial: [portable core baseline](evidence/core-v1-2026-10-03.md) records bounds, timing outliers and a slight large-throughput miss. [Wasm probe measurements](evidence/wasm-v1-2026-10-03.md) record cold/warm startup, allocations and execution. Full server/client/production-guest measurements remain pending. |
| A16 | P6 | Partial: access point + handheld terminal range, binding, power loss, permissions and action cost (WirelessGameTests); paired links range, pairing permission, power loss and gateway-bounded joining (WirelessLinkGameTests). Per-pair rate caps, unload GameTests and throughput measurement open. |
| A17 | P6, P7 | Not implemented. Prerequisite only: a vanilla-recipe Fuel Generator now supplies FE without optional mods. In-game onboarding, complete recipe dependency/power/upgrade playtest and working endgame factory evidence remain pending. |

## Suggested implementation instruction

> Implement this repository according to AGENTS.md, SPEC.md, docs/IMPLEMENTATION.md and docs/ASSETS.md. Resume from the earliest incomplete prerequisite, preserving existing P0–P2 work, then continue through P7. Deliver the playable milestones with finished presentation alongside each feature. Preserve the full first-playable scope. Resolve decision gates with evidence and ADRs, keep docs and meaningful tests with each behavior change, run required checks, and maintain acceptance/asset evidence. Make routine choices within the contract autonomously; report genuine blockers and never claim unrun checks passed. See docs/AGENT_HANDOFF.md for the full continuation task.

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


## P2 live wired coordination work log

2026-10-03, same macOS arm64 / Apple M1 / Java 21.0.12; parent `f0474b8`:

- Selected ADR 0011 before integration. Added indexed per-server region ownership,
  shared scheduler post-ticks and block/entity/chunk callbacks. Edits cancel
  publication immediately; repeated callbacks coalesce until charged replacement.
  Disconnected old geometry and unfinished sources transfer incrementally; roots,
  indexes and four-slot availability prepare before final scope publication.
- Added execution-time registry routing predicate, preserving offline total/claims
  and cleanup. Current root/gateway canonical leases and shared authority/grants
  prevent use of copied structural owners or revoked grants. Runtime admission/
  failures suspend affected ownership without taking independent published regions
  offline. Quarantined registry files remain preserved, with no new empty authority.
- Added capture listener and bounded admission failure tests. Raised seed/probe
  ceilings to 8,192/32,768 for full-size replacements with repeated anchors/source
  transitions; graph capacity remains 4,096 and per-visit work is unchanged.
- Five new required WorldNetworksGameTests use production callbacks/post-ticks for
  automatic activation, exact disconnection/reconnection claims, 100 coalesced
  notifications, invalid multiple roots/recovery, independent regions, private
  inherited storage views, queued grant revocation and remote copied-root denial.
  A typed chunk-load adapter regression covers adjacent-index invalidation with a
  loaded fixture; it does not claim actual chunk unload/reload. Existing ownership
  fixtures now construct valid roots before seeding stock.
- Final interruption-path review found retained source nesting was not separately
  bounded. Added portable DiscoverySeeds with depth 32, 8,192 source slots, frozen
  borrowed streams and one-cursor ownership. Four regressions cover preservation,
  empty transitions, admission, cycles/sharing. Adoption failure preserves the old
  source; scheduled replacement failures no longer strand other queued rebuilds.
- Updated block inspection and player/module contracts. No persistence schema,
  dependency, build/CI policy or release scope changed. Complete terminal/resource
  transfer endpoints and chunk/restart/multiplayer/performance evidence remain pending.

Verification (final continuation 2026-10-11):

- Initial `./gradlew spotlessApply compileJava pmdMain` caught an overloaded event
  listener ambiguity; renamed the listener. Static checks caught Minecraft-owned
  borrowed resources; localized ownership annotations preserve all PMD rules.
  Compilation caught mismatched test API names; corrected against existing APIs.
- `./gradlew spotlessApply build` passed before and after routing-failure isolation,
  including 75 JVM tests and 19 required GameTests. The first canonical
  `python3 scripts/verify.py` passed 24 tooling tests and a 20-GameTest run before
  the final adjacent-chunk regression.
- The final sandboxed `python3 scripts/verify.py` passed documentation/acceptance
  and 24 tooling tests, then failed opening Gradle's external cache lock. Escalated
  canonical retries were interrupted before a result; they are not passes.
- Direct approved-wrapper `./gradlew spotlessApply build` verifies the final source
  separately. Exact final results are appended after completion. The canonical
  command remains blocked by cache access pending a completed approved retry.
- `git diff --check` passed; no generated worlds or unrelated changes were added.
  Actual chunk unload/reload/full restart, clients, multiplayer and representative
  server measurements remain unrun.

P2 and A05/A10/A12/A13 remain partial; banks/connectors, transfers, terminal, crafting,
programs, wireless and full release acceptance remain in the active goal.

Final separately verified source, 2026-10-11:

- `./gradlew spotlessApply build` passed after the deterministic west-neighbor
  fixture and bounded-source fixes: 79 JVM tests and 21 required Minecraft
  GameTests, zero failures/errors/skips. Formatting, PMD, compilation, architecture,
  packaging and required GameTest report gates passed. Unchanged Rust inputs
  remained verified/up to date.
- `python3` calling `scripts.verify.verify(Path.cwd())` and
  `scripts.verify.run_tooling_tests(Path.cwd())` passed with no documentation/
  acceptance errors and 24 tooling tests. This separate diagnostic execution
  does not relabel the cache-blocked canonical subprocess as passed.
- `git diff --check` passed. The final canonical cache-access block remains
  recorded above; no checker, policy or CI requirement was changed to avoid it.


## P2 finite energy-bank access prerequisite

2026-10-11, same macOS arm64 / Java 21.0.12; parent `6018045`:

- Selected ADR 0012 before coding. Reused ADR 0006's finite capacities and rates;
  ENERGY remains canonical DeviceRegistry stock with existing leases/claims.
- Added portable tier progression and shared identity-based input/output budgets.
  Multiple callers and moved generations cannot reset spending; simulation preserves
  resource/claim/rate state. Backwards clocks and mismatched tier definitions reject.
- Added RATE_LIMIT as an appended operation reason, with no serialized enum/schema
  change. Actual acceptance alone spends rate credits; offline and reserved stock
  remain unavailable to physical extraction.
- Six tests cover example/failure behavior and 4,000 generated operations with
  seed 0xE0B001. Canonical snapshot reload remains offline with exact claims.
- `./gradlew spotlessApply test pmdMain pmdTest` passed. Required full build and
  documentation results are recorded after completion. This prerequisite adds
  no physical bank or capability and is not full A04/A13/A14 evidence.

Next is physical bank integration with current wired activation, NeoForge ports,
consumed portable leases, upgrades/recovery, recipes and real GameTests. P2 and
all remaining release stages stay in the active full-mod goal.

Energy-bank prerequisite final checks, 2026-10-11:

- `./gradlew spotlessApply build` passed with 85 JVM tests and a fresh 21 required
  Minecraft GameTests, zero failures/errors/skips; all format/static/architecture/
  packaging gates passed. Unchanged Rust inputs retained verified up-to-date results.
- Separate `scripts.verify.verify(Path.cwd())` and
  `scripts.verify.run_tooling_tests(Path.cwd())` passed document/acceptance checks
  and 24 tooling tests. The canonical command's earlier external Gradle cache
  access block remains unresolved; separate checks are not a canonical-command pass.
- `git diff --check` passed. No dependency, schema, CI/checker or player-visible
  resource endpoint changed. Physical banks and their required world checks remain
  unfinished, and the complete release goal remains active.

## Release presentation and handoff review — 2026-10-11

Documentation-only update: made R08 presentation/onboarding and R09 recovery
expectations explicit, extended A11/A13/A17 without changing their IDs, added
playable demonstration checkpoints and the asset inventory, and reconciled stale
wired-network status summaries. Added a copyable full-release continuation task.
No gameplay or asset implementation is claimed; no stage/acceptance row was closed.
No skill, contributor policy or CI gate was changed.

Verification on local macOS arm64 / Java 21:

- `python3 scripts/verify.py`: initial sandbox run passed 24 tooling tests but
  could not open Gradle's cache lock outside the workspace. Authorized rerun
  passed documentation/acceptance checks, all 24 tooling tests and Gradle build.
  All 21 headless Minecraft GameTests executed and passed; JVM/Rust and other
  unchanged tasks were up-to-date, not newly executed.
- `git diff --check`: passed. Final documentation validation also passed after
  updating this evidence and A13's P6 recovery ownership.
- No new gameplay tests were added because only documentation changed. No client,
  survival, multiplayer or performance checks were run for this edit; those
  release evidence gaps remain open.

## Energy-bank ownership transitions — 2026-10-11

Continued from the earliest unfinished P2 physical-bank prerequisite on macOS
15.3.1 arm64 / OpenJDK 21.0.12. Added `EnergyBankOwnership` for tier-checked
place/reload/remove and sequential portable upgrades against the canonical
registry. Four new behavioral tests prove populated movement/upgrades retain
stock and claims, shared tick spending follows the backing, copied placed leases
quarantine, invalid admission rejects without mutation, and snapshot reload stays
offline. The service has no independent energy copy or new persistence schema.

- `./gradlew spotlessApply test`: passed; new ownership tests executed.
- `python3 scripts/verify.py`: passed documentation/acceptance checks, all 24
  tooling tests and Gradle build/check. All 21 required headless Minecraft
  GameTests executed and passed. JVM tests were up-to-date in this build after
  the preceding test run; unchanged Rust verification was also up-to-date.
- Format/static/architecture/packaging gates passed; `git diff --check` passed.
- Physical blocks/items/capabilities, client visuals, actual chunks/full restart,
  multiplayer and survival checks were not implemented or verified by this slice.

Next prerequisite remains the physical bank adapter: register three tier blocks
and lease-consuming items, wire shared server-tick energy access and bounded wired
availability, preserve malformed/future data on break, add sequential module
application, recipes/finished assets, and real capability/ownership GameTests.
P2 and A04/A13/A14 remain partial; the A01–A17 full release goal remains active.

## Physical finite energy banks — 2026-10-11

Implemented three tier blocks/items and their NeoForge energy capabilities.
Lease-consuming placement, exact single break drops, sequential portable module
upgrades, wired validation and shared server-tick rate budgets use existing core
ownership/accounting. Bank chunks/items carry strict version-one references only;
canonical schema three is unchanged. Malformed/future references survive save,
break and placement without replacement stock. Added player inspection, tooltips,
native tier models and vanilla Starter recipe/unlock; player/asset/module docs
track implementation separately from client evidence.

Six required EnergyBankGameTests exercise the real block/capability/recipe and
ownership paths. Two EnergyBankHandleDataTest cases add a committed schema-one
fixture and strict malformed/future-input rejection. These support A04/A13/A14;
no release acceptance row is complete.

Verification on macOS 15.3.1 arm64 / OpenJDK 21.0.12:

- Initial `./gradlew spotlessApply compileJava` passed.
- Intermediate `./gradlew spotlessApply build` passed the first five new tests
  (26 total GameTests) plus the JVM suite; unchanged Rust tasks were up-to-date.
- Expanded build executed all 27 GameTests successfully, but failed three of 91
  JVM tests on asynchronous NeoForge read-cleanup racing JUnit temporary-directory
  deletion. Added owner-lifecycle drains in the three affected disk-fixture classes;
  no test assertion/check was removed or weakened.
- Final `./gradlew spotlessApply build`: passed 91 JVM tests and 27 required
  GameTests with zero failures/errors/skips; format/static/architecture/packaging
  gates passed. Rust verification retained its unchanged up-to-date result.
- `python3 scripts/verify.py`: passed document/acceptance validation, 24 tooling
  tests and the complete Gradle build/check; all 27 GameTests ran afresh. JVM/Rust
  and other unchanged checks were up-to-date after the preceding build.
- `git diff --check` and final focused documentation validation passed.

Actual chunk unload/full-world restart, independently interrupted saves, client
model/interaction checks, multiplayer and survival/generator progression remain
unverified. P2 stays in progress. Continue bank world-lifecycle/recovery evidence
and physical connectors, then the bounded hopper/terminal path through P3 and all
remaining full-release stages; this checkpoint does not narrow the A01–A17 goal.

## Physical populated-bank movement evidence — 2026-10-11

Added `EnergyBankGameTests.brokenPopulatedBankUpgradesAndReplacesItsPhysicalOwner`
for the remaining P2 movement/upgrade path. The actual world breaks a bank seeded
through its capability, upgrades the one dropped portable item, places the
Expanded bank at another cable-connected position, and waits for production
wired revalidation. It checks backing identity, exact energy/claims, rejection by
the retained removed entity, offline replacement backpressure, claim-owner
extraction and the new tier's 4,000 FE input ceiling. This strengthens A04/A13
physical evidence; actual chunk unload and full-world restart remain unverified.

The initial same-position replacement version passed `./gradlew spotlessApply build`
with all 28 required GameTests; JVM/Rust tasks were up-to-date. The final test
moves to a distinct position; its canonical verifier result is recorded below.
Full first-playable scope and remaining P2–P7 obligations stay unchanged.

Final `python3 scripts/verify.py` passed documentation/acceptance checks, 24 tooling
tests, and the required build/check. All 28 GameTests executed afresh, including
the distinct-position move; unchanged 91-test JVM and Rust tasks were up-to-date.
`git diff --check` and focused final documentation validation passed. No client,
chunk-unload, full-world restart, multiplayer or survival check was run here.

## Transfer recovery prerequisite — 2026-10-11

External world endpoints require owned persistent staging before mutation. Extended
existing `StagedTransfer` with immutable validated snapshots, pending call/amount,
pre-call and post-accounting journal notifications, and exact-backing restore.
Known partial remainders preserve accounting and current authorization. Any pending
external extraction/delivery/return restores quarantined without replay/refund;
recording failure before extraction stops mutation. Diagnostics are bounded to
512 characters. No independent resource copy, player endpoint, disk schema or
crash-durability claim is introduced.

Four TransferRecoveryTest cases capture interruption records from inside handlers
that mutate before throwing, and verify partial recovery/conservation, denied
fresh authority, inert completed records, endpoint mismatch, journal failure,
malformed snapshots and overflow. `./gradlew spotlessApply compileJava` and
`./gradlew spotlessApply test` passed. The initial canonical verifier passed 24
tooling tests and all 28 GameTests; JVM/Rust tasks were up-to-date after the test
run. Final stricter snapshot-validation checks are recorded below.

Next prerequisite is a bounded canonical staging-record manager and versioned
save/migration/recovery integration, then physical connectors and the hopper/
terminal loop. World interruption/chunk/restart, client and all other release
obligations remain open; A03/A13 gain only portable component evidence here.

Final `python3 scripts/verify.py` passed 24 tooling tests, 95 executed JVM tests
and 28 executed required GameTests, with zero failures/errors/skips. Formatting,
PMD, architecture and packaging passed; unchanged Rust verification remained
up-to-date. `git diff --check` and focused final documentation validation passed.
No world staging/restart/interruption, client, multiplayer or survival check was
claimed by this core-only slice. The full release goal remains active.

## Canonical owned transfer records — 2026-10-11

Implemented TransferRegistry as the single bounded owner of staging runtime
instances. Admission precedes extraction and reserves maximum record metadata;
record IDs expose immutable snapshots, binding is explicit after reload/unload,
and uncertain records cannot retire, replay or refund. Added strict local transfer
schema one and canonical world schema four alongside ledgers/grants/nodes.
Schemas one/two/three migrate by adding empty missing tables while preserving
existing data exactly. Unknown schemas/types/codes/fields/duplicate/overflowed
records quarantine the entire original save instead of resetting storage.

Four TransferRegistryTest and three TransferPersistenceTest cases add core
lifecycle/limits/reentrancy and real compressed disk debit/credit/staging recovery,
plus a committed schema-four pending-intent fixture. Existing migration tests now
expect schema four and explicitly preserve old owned data; unknown-version tests
advance to schema five. This reflects the new versioned contract, not relaxed
checks. No physical transfer endpoint or release criterion is marked complete.

`./gradlew spotlessApply compileJava`, `./gradlew spotlessApply test` and the
initial `./gradlew spotlessApply build` passed; the full build executed all 28
required GameTests and the JVM suite. Final admission/reentrancy regression and
canonical verifier outcomes are recorded below. Rust inputs remained unchanged.

Next is scheduled/authorized world endpoint integration and physical connectors,
then the hopper/terminal path. Real world restart/chunks, interrupted independent
external saves, administrative reconciliation, client, multiplayer, performance
and all P4–P7 release obligations remain unfinished. The full A01–A17 goal stays
active.

Final `python3 scripts/verify.py` passed 24 tooling tests, 102 executed JVM tests
and all 28 executed required GameTests, zero failures/errors/skips. Formatting,
PMD, architecture and packaging passed. Unchanged Rust verification was
up-to-date. `git diff --check` and focused final documentation validation passed.
No client, full-world restart/chunk, multiplayer, survival or independent external
save interruption pass is claimed. The schema-four disk tests are component
integration evidence, not release-wide persistence acceptance.


## Scheduled digital world transfer checkpoint — 2026-10-11

Added `WorldStorageTransfers`, shared through the wired runtime, for bounded
queued cell/bank moves using authenticated principals, current physical holders,
shared permission/segment visibility, scheduler fairness, registry simulation,
unreserved extraction and canonical schema-four transfer ownership. Requests are
limited to 128 items or 1,000 mB/FE, 16 credits each, 256 tickets and 2 MiB estimated
queued payload; completed/staged records still use the separate canonical quotas.
Execution-time record quota rejection is typed TECHNICAL_LIMIT before extraction.
Individual cancellation preserves unrelated work; externally cancelled scheduler
tickets are reclaimed on next admission with a bounded scan. Neither completion
nor operator status widens segment scope.

Added three JVM behavioral tests (ledger/registry simulation and targeted scheduler
cancellation) and five real GameTests (partial unreserved transfer, permission
revocation, cable disconnection, removed cell and parent/private-child denial).
`./gradlew spotlessApply test compileGameTestJava` passed, executing the JVM tests.
`./gradlew spotlessApply build` passed all 33 actual required GameTests and static
checks; JVM/Rust tasks were cached after the earlier run. Further verification
below records the final revision after admission/queue cleanup review.

P1/P2 and A03/A12 remain partial: digital world transfer API is implemented, but
external inventory/fluid/energy connectors, terminal/player packet paths,
coordinated world restart/rebind/refund, actual chunk unload, client/multiplayer
and representative performance evidence remain incomplete. No full release
acceptance criterion or implementation stage is marked complete. Next prerequisite
is physical connector integration through shared authorization/scheduling/accounting,
including conservative foreign-outcome recovery and endpoint lifecycle ownership.
See [world transfer contract](modules/WORLD_STORAGE_TRANSFERS.md).


Final `python3 scripts/verify.py` passed 24 tooling tests, 105 executed JVM tests
and all 35 executed required GameTests, with zero failures/errors/skips. Formatting,
PMD, architecture and build/check passed; Rust guest verification was UP-TO-DATE.
The two additional world scenarios verify individual/whole-principal cancellation
admission reclamation and a physical bank move limited to 300 FE of destination
space with exact canonical conservation. Final diff review and `git diff --check`
found no whitespace errors, disabled tests or incidental generated artifacts.
Actual full restart/chunk unload, client/multiplayer, external handlers and
performance evidence are not claimed. The full-release goal remains active.


## Sided connector and external transfer checkpoint — 2026-10-11

Implemented a leased physical Storage Connector with six-way socket/model/shape,
five cable faces, item/fluid/FE kind, read/write policy, priority, bounded item
window and dual provider/connector rate accounting. Copper/redstone/hopper recipe,
short translated tooltip controls and native chat inspection ship alongside it.
Configuration requires current shared EDIT permission, does not consume material,
and invalidates queued handles. The connector registers a foreign view rather
than copying contents into a digital ledger. Canonical string kind CONNECTOR
extends existing schemas; a fixed local schema-one fixture and compressed lease
movement/reload checks preserve old fields and create no network grants.

Vanilla wrappers require actual container identity and normalized chest halves;
opaque providers require explicit complete backing descriptions. Same-kind
registration overlaps reject, including different faces, disjoint windows or
shared handlers. All backing chunks must be loaded. Only supported generic
NeoForge APIs are imported; no optional dependency is loaded. Real third-party
identity adapters and external catalog/reservation/selection remain unfinished.
The same shared service admits foreign endpoint types, checks exact key encoding,
current scopes/grants/ownership/access/rates, and journals mutations through
canonical staging. Quarantined outcomes never retry/refund. Recursive calls,
self-moves, bad keys and stale providers have observable rejection coverage.

Backing invalidation indexes at most 1,024 live/in-progress records with two
links each, releasing capabilities without a network scan or losing saved
counts. The regression invalidates during extraction/delivery, retains actual
known staging and isolates unrelated records. Quarantine remains UNCERTAIN
without binding; the old pending-intent disk fixture still requires OFFLINE
until validated bind transitions it, with no changed expected fixture result.
Cleanup avoids creating new services during recovery or shutdown.

Nineteen new required GameTests cover real chest/NeoForge handlers, partial exact
item/mB/FE moves, reservations, revocation, private children, overlap/double chests,
rate/settings/backing-description changes, rotations, physical controls, movement,
future data, opaque identity, faults/reentry, slots/orientations and self-moves.
Three ConnectorDataTest cases, compressed NodePersistenceTest, the sparse last-int
slot/negative-slot regression and an indexed transfer-lifecycle test add JVM
coverage. Bounds remain explicit; representative performance is unmeasured.

During implementation, the first build passed world tests but failed three PMD
borrowed-world annotations; another test compile identified a missing test import.
Two new world assertions initially inspected replacement/rotation before required
topology revalidation. Corrected scenarios now require immediate suspension and
fresh-provider behavior after revalidation, rather than accepting any reason.
An initial cleanup change altered legacy unbound-pending failure classification;
restored the existing OFFLINE-until-bind fixture contract without editing its
assertion. Focused JVM verification passed after those corrections. Final
canonical results below cover the resulting revision.

P1/P2/P3 and A03/A04/A12/A13/A14 remain partial. Next prerequisites: persistent
source/destination/origin context and stable provider identity; validated world
resume/cancellation/unload/restart; indexed authorized external catalog and shared
claims/selection; then the hopper/buffered-preset/terminal loop. P4–P7 and complete
assets/onboarding/compatibility/multiplayer/performance acceptance remain required.
No full release, stage or acceptance criterion is marked complete. Contract:
[CONNECTORS.md](modules/CONNECTORS.md); rationale: [ADR 0013](decisions/0013-external-connectors.md).


Final `python3 scripts/verify.py` passed 24 tooling tests, 111 executed JVM tests
and all 54 executed required GameTests, zero failures/errors/skips. Formatting,
PMD, architecture, compiler and build/check passed; pinned Rust guest checks were
UP-TO-DATE (unchanged). The final suite includes nineteen connector world cases,
the fixed connector SNBT fixture, indexed in-call capability unbinding and the
last-int/negative-slot regression. Final `git diff --check` and documentation/
acceptance validation passed after evidence recording. Scope review found no
removed/skipped/weakened test, policy/CI bypass or accidental generated artifact;
all prior work remains uncommitted and preserved. Real client, multiplayer,
chunks/full restart, stable foreign endpoint context/recovery, catalog/claims,
automatic routes, complete onboarding/progression and performance remain open.
The full first-playable goal remains active.


## Persistent provider identity prerequisite — 2026-10-11

Added reference-only marker schema one in NeoForge persistent block-entity data:
world UUID and component UUID, never copied resource contents. Stable group IDs
include kind and sorted component identities. Validate all existing parts before
initialization, require loaded persistent block entities, preserve unknown/foreign
markers, and reject concurrent copied identities at separate coordinates. Rate
budgets now follow persistent backing identity while connector identity still
prevents group/reconfiguration bypass. Lifetime tokens remain transient so old
queued handles cannot serve a replaced/reloaded provider. Existing global save,
connector and transfer formats/fixtures remain unchanged; legacy transient
transfer endpoints cannot be guessed into new markers.

Two codec/identity tests and three world cases cover strict/future/foreign data,
group semantics, compressed real-container NBT content/identity reload, replacement
and copied-identity rejection. The existing metadata-group rate scenario now has
real block entities for all declared parts, keeping its original assertions.
`./gradlew spotlessApply build` passed 56 world cases at the intermediate revision;
final verification below covers copied-marker and diagnostic/rate updates.
No full world/chunk restart or transfer continuation is claimed. P2/P3 and A03/
A04/A12/A13 remain partial. Next: persist source/destination/origin context and
implement validated resume/cancellation, then external catalog/claims/selection
and the hopper/terminal loop. The full release objective remains active.


Final `python3 scripts/verify.py` passed 24 tooling tests, 113 executed JVM tests
and all 57 executed required GameTests, zero failures/errors/skips. Formatting,
PMD, architecture, compiler and build/check passed; unchanged Rust guest checks
were UP-TO-DATE. Final whitespace/documentation/acceptance checks passed after
recording this evidence. Existing changes and assertions are preserved; no CI or
verification rule was weakened and no generated world artifact was added to git.
Stable provider identities are implemented/verified at codec and compressed
block-NBT scope. Source/destination/origin operation context and recovery driver
remain unfinished, as do actual chunk/full world restart, client, multiplayer,
external stock/claims, automatic presets/terminal and the remaining release
criteria. The full first-playable goal remains active.


## Accepted transfer context — 2026-10-11

Implemented immutable portable TransferContext and strict nested codec, attached
before resource calls in the single canonical staging owner. Save world/network/
segment/origin and both device/backing/lease/configuration references; save no
operator authority or handler. Global world schema five and transfer entry schema
two retain all old quantities/claims/grants/identities/intents, with null context
for older records. Unknown or mismatched context retains full recovery files.
Shared world transfers now capture original root/segment at queue admission and
deny migration to a replacement root before mutation, even for the same owner.

The fixed context fixture and four behavioral/disk tests prove exact scope/type
round trips, pre-mutation mismatch denial, compressed context/count/grant recovery,
foreign/malformed preservation and unbound restore. World tests prove attached
references and old-root queue denial. Initially extending every record's fixed
allowance failed the unchanged 1,024-record admission test; corrected to a separate
1,024-byte context charge without weakening counts/byte limits or tests. Existing
migration fixtures now target schema five and normalize only declared version
changes before asserting all prior data remains exact. Schema six is the new
unknown-format case. `./gradlew spotlessApply build` passed 58 actual GameTests and
the JVM/static gates at the intermediate final-code revision; canonical results
below cover completed docs and checks.

P2/P3 and A03/A12/A13 remain partial. Next is a bounded recovery API/scheduler that
reacquires current endpoints, validates accepted root/segment, actor ownership,
current generations/policy/grants and stable provider markers, and progresses only
known staging or owned cleanup. Never repeat extraction; unknown/pending records
remain quarantined and legacy missing context requires explicit reconciliation.
Then prove real chunks/full restart/interruption boundaries, implement external
stock/claims/selection and deliver the hopper/preset/terminal loop. The full A01–
A17/asset/onboarding/performance objective remains active; no stage is complete.


Final `python3 scripts/verify.py` passed 24 tooling tests, 117 executed JVM tests
and all 58 executed required GameTests, zero failures/errors/skips. Formatting,
PMD, architecture, compiler and build/check passed; unchanged Rust checks were
UP-TO-DATE. The first canonical attempt caught a newly lengthened Javadoc's
formatter requirement; applied formatting and reran the canonical command to a
pass. No assertion, quota, policy or CI rule was weakened. Final documentation/
acceptance validation and `git diff --check` passed after recording this evidence;
prior changes remain preserved and uncommitted. Implemented/verified: context
storage, schema migrations and replacement-root queued rejection. Still incomplete:
validated world progress/cleanup, actual chunk/full restart/interruption evidence,
client/multiplayer, external stock/claims and all remaining first-playable scope.
The active goal is not complete; resume with the recovery API/scheduler prerequisite.

## Explicit saved-staging progress checkpoint — 2026-10-12

Implemented `WorldStorageTransfers.progress` for the recorded principal to queue
bounded delivery or owned cleanup using shared fairness, credits and limits.
Accepted origin/root/segment and endpoint references are revalidated; foreign
leases, configuration generations and persistent backing markers must match.
Delivery checks current grants before provider probes. Cleanup returns only known
staging after revocation and does not require the old destination. Ports reject
extraction. Pending/quarantined and missing-context records are never replayed.
Retirement or context changes before dispatch produce denial. Queue footprint now
reserves 1,536 bytes plus exact key payload; existing count/byte gates are retained.

Six new headless scenarios prove no repeated extraction, revoked delivery plus
owned return, stale source retention, destination-independent cleanup, other
principal/operator denial and legacy missing-context preservation. The initial
build failed two assertions because cell removal temporarily rebuilt the origin
scope; fixtures now wait for valid topology before testing endpoint recovery.
No expectations were weakened. `./gradlew spotlessApply build` passed with 64
executed required GameTests and JVM/static gates; canonical verification follows.

P2/P3 and A03/A12/A13 remain partial. Automatic recovery dispatch, actual chunk/
full server restart and interruption evidence, external stock/claims/selection,
client operations and all remaining first-playable features/assets still remain.
Next: exercise foreign recovery and larger staged quantities, then real lifecycle
recovery and the external stock/preset/terminal prerequisite. No stage is complete.

Canonical `python3 scripts/verify.py` passed 24 tooling tests and all JVM/build/
formatting/static/documentation gates. Its 64 required GameTests executed and
passed without skips; JVM test and unchanged Rust tasks were UP-TO-DATE. The
preceding `./gradlew spotlessApply build` executed the JVM suite (117 tests) and
64 GameTests successfully. `git diff --check` passed. No full world restart,
client, multiplayer or release performance evidence is claimed. The full-release
goal remains active.


## Recovery quantum and external reacquisition evidence — 2026-10-12

Added a 250-item known-staging scenario: the first scheduled call delivers 128
and retains 122, then a second call completes 250 exactly while source stock stays
50 throughout. Added an external-source scenario using a real chest capability,
exact named item identity and actual extraction into canonical staging. After
provider invalidation and replacement with its saved block-entity NBT, scheduled
progress reacquires the stable backing and delivers 64 from staging while the
reloaded chest stays empty. This proves block-entity NBT endpoint reacquisition,
not a full server restart or atomic cross-file persistence guarantee.

`./gradlew spotlessApply build` passed with 66 executed required GameTests and
formatting/static gates; JVM and unchanged Rust tasks were UP-TO-DATE. Previous
turn produced 117 executed passing JVM tests. A03/A12/A13 remain partial. Next
prerequisite: bounded foreign catalog observations and shared claims for automatic
selection/presets, while full chunk/restart/interruption and administrative
reconciliation remain required. No acceptance criterion or stage is complete.

Canonical `python3 scripts/verify.py` passed 24 tooling tests, all 66 executed
required GameTests with no skips, and JVM/build/static/formatting gates. JVM tests
(117 passing in the retained report) and unchanged Rust verification were
UP-TO-DATE; no fresh execution of those cached tasks is claimed. Documentation/
acceptance validation and `git diff --check` passed. Previous goal turn and this
turn both changed authoritative state and added passing behavioral evidence;
the full-release goal remains active.


## Scheduled external stock observation checkpoint — 2026-10-12

Implemented bounded advisory item/fluid/FE pages behind shared scheduling and
VIEW/segment validation. Each call reads at most 16 configured positions, returns
exact immutable keys/counts and explicitly unknown item insertion capacity, and
never simulates transfers or reserves/caches foreign resources. Queue scope is
pinned and revalidated; provider read faults quarantine without partial rows.
Acceptance A03/A04/A12 remain partial: shared claims, aggregate selection, presets,
terminal/client operations, actual chunks/restart and performance are still absent.

Three JVM scenarios cover component identity, configured-window pagination,
nonmutation, fluid/FE capacities, immutable results and a provider reporting
Integer.MAX_VALUE slots with exactly bounded reads. Two new world scenarios check
queued VIEW revocation hides rows, authorized observation returns exact named-item
stock without mutation, and a second-slot provider fault discards the entire
partial page while preserving stock and persisting quarantine. Authority is also
rechecked after provider reads before any page is published. Canonical verification results follow.

Final `python3 scripts/verify.py` passed 24 tooling tests, 120 executed JVM tests
and all 68 executed required GameTests with zero failures/errors/skips. Compiler,
formatting, PMD, architecture and build/check gates passed; unchanged Rust guest
verification was UP-TO-DATE. `./gradlew spotlessApply build` also passed during
implementation; no failed expectations were weakened. `git diff --check` passed.
This is backend observation evidence; no finished aggregate catalog, claims,
terminal, client/multiplayer, full world restart or performance result is claimed.
The goal remains active; next implement shared foreign claims and execution-time
stock revalidation, then aggregate selection and automatic storage presets.

## Portable shared foreign claim prerequisite — 2026-10-12

Implemented `ExternalClaims` metadata accounting and `ExternalClaimPort` fresh
execution-time stock validation. Canonical backing/owner/exact-key holds are
additive, all-or-nothing and bounded to 4,096 records/4 MiB. Shrinking foreign
stock never exposes another owner's hold; known partial extraction consumes only
actual units, unknown outcomes leave holds intact. Immutable restore retains
orphans and rejects duplicates, overflow and quota violations without resources.
A shared guard rejects reentrant claim mutation/extraction across wrappers.

Six JVM scenarios include 10,000 generated reserve/consume/release operations
(seed 0xEC1A1), competing shortage/background access, partial/faulty extraction,
restore/count/byte bounds and shared reentrancy. A03/A04/A12/A13 remain partial:
this portable prerequisite is not exposed by world transfers. Next integrate the
registry into canonical SavedData with versioned codec/migration fixtures, then
scheduled reserve/release/claimed extraction and current provider revalidation.
No live world reservation, persisted job recovery or complete stage is claimed.

Final `python3 scripts/verify.py` passed 24 tooling tests, 126 executed JVM tests
and all 68 executed required GameTests, zero failures/errors/skips. Compiler,
formatting, PMD, architecture and build/check passed; unchanged Rust guest checks
were UP-TO-DATE. Intermediate `./gradlew spotlessApply build` commands also passed.
`git diff --check` and post-evidence documentation/acceptance checks passed. No
world claim behavior, full restart/client or performance verification is claimed.
This turn adds portable accounting code and behavioral evidence; the full-release
goal remains active with canonical claim persistence as the next prerequisite.

## Canonical foreign claim persistence checkpoint — 2026-10-12

Added one ExternalClaims registry to DeviceSavedData with bounded dirty callbacks,
nested schema-one strict/deterministic codec and global schema-six migration.
Old versions one through five add an empty table; existing counts, grants, node
leases and accepted transfer contexts remain exact. Orphan holds survive absent
foreign providers/stock. Malformed/future/duplicate/overflow/oversized claims and
owned-identity collisions quarantine the whole authority and preserve raw tags.
New fixed external-claim and schema-five world fixtures protect exact identities,
large long quantities, pending-call evidence and accepted context.

Six new JVM scenarios exercise exact/deterministic fixture encoding, actual
compressed SavedData disk reload, dirty state, schema-five migration with populated
prior tables/context, malformed/future/type/duplicate/overflow raw preservation,
identity collisions and metadata excess. Earlier migration assertions now expect
six, assert the new table is empty, then remove only that declared addition before
comparing prior full data. Future-global tests now use seven; nested schemas stay
unchanged. Initial focused runs caught the new fixture's wrong storage constructor,
an asynchronous write observed too soon, and a remaining schema-five expectation;
corrected setup/wait and version expectation without weakening data assertions.

P2/P3 and A03/A04/A12/A13 remain partial. World claim mutations/claimed extraction
still await shared scheduled admission, current provider stock validation and
job/uncertainty reconciliation. Full server restart/chunk interruption, player
routes, assets and remaining A01–A17 release scope remain unfinished. Next wire
bounded scheduled reserve/release and all foreign extraction through this registry.

Final `python3 scripts/verify.py` passed 24 tooling tests, 132 executed JVM tests
and all 68 executed required GameTests with zero failures/errors/skips. Compiler,
formatting, PMD, architecture and build/check passed. Unchanged Rust guest checks
were UP-TO-DATE. `git diff --check` and post-evidence documentation/acceptance
validation passed. This proves canonical hold persistence and migration, not live
world reservations or full server restart. The full-release goal remains active.

## Scheduled foreign holds and protected extraction — 2026-10-12

Wired canonical schema-six holds into shared scheduled principal reserve/release
and claimed transfers. All connector background extraction respects unclaimed
stock; claimed extraction revalidates complete bounded fresh totals and consumes
only actual known units. Reservation checks current grants/visible scope/access/
lease/config/backing before and after reads. Queue admission pins root/segment.
Revocation permits only unused metadata cleanup; a matching retained uncertain
transfer blocks release, including after capability unbinding/disk restore.
Complete totals are bounded to 64 configured item positions/64 fluid tanks/one FE
provider. Larger tank catalogs remain paginated but cannot create/use holds until
bounded complete stock validation exists. Unheld transfers retain bounded calls.

Five headless scenarios prove item competition, background protection, principal
nonadoption, claimed actual debit, revoked cleanup/new-reservation denial, unknown
extraction/hold retention/release blocking and exact fluid/FE conservation. One
JVM regression checks uncertain backing/owner/key lookup after unbound restore.
Tests stage competing request admission deterministically without assuming actor
fairness order or bypassing the scheduled release path. No failed requirement,
quota, expectation or gate was weakened.

A03/A04/A12/A13 remain partial: these principal-owned utility holds are not crafting
job identities/reconciliation. Aggregate catalogs/selection, automatic presets,
player deposit/withdrawal, lifecycle/client/multiplayer/performance evidence and
all remaining A01–A17 presentation/onboarding scope remain unfinished. Next build
bounded aggregate storage selection then the hopper/preset/terminal loop; preserve
full restart/interruption and job recovery as outstanding release requirements.

Final `python3 scripts/verify.py` passed 24 tooling tests, 133 executed JVM tests
and all 73 executed required GameTests with zero failures/errors/skips. Compiler,
formatting, PMD, architecture and build/check passed; unchanged Rust guest checks
were UP-TO-DATE. Intermediate `./gradlew spotlessApply build` commands passed.
`git diff --check` and post-evidence documentation/acceptance validation passed.
These results verify scheduled principal holds and live protected item/fluid/FE
extraction; no aggregate selection, crafting-job ownership, client/multiplayer,
full world restart/interruption or performance evidence is claimed. The full
first-playable goal remains active; resume with aggregate selection/presets.

## Bounded live exact-key catalog prerequisite — 2026-10-12

Added scheduled live digital/foreign catalog pages using metadata-only UUID-indexed
registry enumeration. Each step examines eight records; no full ledger snapshot,
unbounded network scan, chunk load or observed-stock cache is used. Current VIEW,
root/segment, physical leases and inherited visibility precede provider discovery.
Rows retain total/held/available, current endpoints, priority and a separate
withdrawable flag; backing IDs are deduplicated and each page sorts priority/ID.
Server-only continuation captures actor/root/segment/key and rejects adoption or
owned replacement-root scope changes. Publication rechecks current visible scope.

Two JVM tests prove bounded stable metadata paging, immutable results, current
moved generations and unchanged dirty state. Four headless scenarios cover mixed
catalog counts/holds/discovery, other-root exclusion, parent/operator private-child
nonenumeration before marker creation, queued revocation and cursor actor/key/root
binding. Added and ran a reproducible standalone metadata benchmark: 1,024 devices /
65,536 keys, existing full-save snapshot baseline versus eight-handle index page;
recorded hardware/runtime, workload, warmup/samples, allocation/timing results and
limitations in docs/performance/CATALOG_METADATA.md. No world throughput claim.

P2/P3 and A03/A04/A12 remain partial. This is an exact-key live discovery API, not
all-key search/global distribution or a finished terminal. Next implement bounded
collected priority selection and persisted rotation, then automatic presets and
the hopper/storage/terminal/withdrawal loop. Keep all full restart/interruption,
crafting/job ownership, client/multiplayer, presentation/onboarding and P7 evidence
requirements outstanding; no stage or acceptance criterion is complete.

Final `python3 scripts/verify.py` passed 24 tooling tests and all 77 executed
required GameTests with zero failures/errors/skips. JVM test task was UP-TO-DATE;
the preceding canonical run executed all 135 JVM tests successfully (retained XML
report: zero failures/errors/skips). Compiler/formatting/PMD/architecture/build
checks passed; unchanged Rust guest verification was UP-TO-DATE. Intermediate
`./gradlew spotlessApply build` checks passed. Benchmark command compiled via
`./gradlew spotlessApply testClasses` and ran explicitly on Java 21; exact results,
workload and limitations are recorded in the performance document. `git diff --check`
and post-evidence documentation/acceptance validation passed. No client, full
world restart or release throughput evidence is claimed. The full-release goal
remains active; next connect bounded collected global selection to presets.

## Reviewed storage checkpoint and next milestone — 2026-10-12

User requested committing the accumulated changes and reviewing the next steps.
Prepare one coherent storage checkpoint (physical banks/connectors, canonical
staging/context/foreign holds and bounded catalog discovery); use smaller feature
commits for subsequent work. No release criterion or incomplete stage is promoted
to complete. Global routing work is deferred until after this checkpoint review.

The session's new restricted sandbox prevented fresh Gradle startup: default
wrapper access to ~/.gradle's lock was denied; an isolated copy-on-write cache in
/private/tmp allowed cache access but Gradle's local file-lock contention socket
was denied (SocketException: Operation not permitted). No verification/CI rule,
Gradle distribution or test was modified to bypass the restriction. Last canonical
evidence remains 24 tooling tests, 135 executed JVM tests and 77 executed required
GameTests, with the cache distinctions recorded above. Current documentation/
acceptance and staged whitespace checks passed; staged Java sources passed a
standalone check with the configured google-java-format 1.28.0 (long-string reflow
excluded to match the normal formatter step). A full fresh Gradle run remains
blocked by the environment, not recorded as passing.

Recommended next milestone: deliver the playable hopper -> storage -> searchable
terminal -> withdrawal loop, including bounded routing, basic import/export presets,
server-authorized player operations and usable native UI/assets. Commit each
coherent feature slice. Then close remaining recovery/restart/multiplayer and
performance evidence alongside crafting, production scripting, powered/wireless
progression and final A01–A17/asset/onboarding release audit. An optional user
preference on playable-loop versus restart/recovery priority is pending; the full
release goal remains active.


## Global priority routing prerequisite — 2026-10-12

Storage checkpoint committed by the user as `22be4e9` after sandbox Git writes
prevented agent commit creation. Subsequent routing work remains a separate slice.
Implemented explicit scheduled TO_STORAGE/FROM_STORAGE exact-key priority routes:
bounded eight-record discovery pages collect a global descending-priority/stable-ID
order, recheck accepted scope and grants, then attempt one actual move quantum.
Unavailable/full candidates are skipped only before any resource mutation or
uncertainty; staged/partial/uncertain results preserve their actual durable move ID.
Catalog mutation flags distinguish deposit permission from VIEW/WITHDRAW.
Requests have documented count, metadata, candidate and page limits; every step
uses existing actor/network fairness and accounting. Persisted round-robin,
automatic presets, all-key search and terminal/player operations remain missing.

Direct Java 21 compilation of changed main/core/world-test sources passed.
Standalone Jupiter execution of the portable core package passed all 86 tests,
including three new global-order/duplicate/extreme-priority/quota tests; this is
not the full JVM suite. An isolated launch of the pinned NeoForge GameTest runtime
using cached artifacts and updated compiled classes executed all 80 required
world tests successfully, with zero failures/errors/skips and server exit zero.
Command: `python3 /private/tmp/factorycore-route-worldcheck/launch.py`.
Report: `/private/tmp/factorycore-route-worldcheck/results.xml`; launch log beside
it. Existing canonical build reports were not overwritten. Two initial failures
came from the new fixture's unsupported north-side capability query; correcting
connector placement to its supported west provider face preserved all assertions.
Tests prove preferred global import, exact preferred partial withdrawal and full
preferred-target fallback. They do not prove restart/client/multiplayer behavior.

Fresh canonical Gradle/format/PMD/build gates remain blocked by sandbox lock/socket
restrictions. No gate was weakened. P2/P3 and A03/A04/A12 remain partial; the full
A01–A17 release objective remains active. Next finish route failure/cancellation
coverage and persisted round-robin, then connect presets and the player terminal.


### Priority route failure-path evidence — 2026-10-12

Added three real scheduled scenarios: only the owning actor can cancel queued
route discovery (no callback/mutation), revoked VIEW/transfer grants prevent
queued discovery from mutating either endpoint, and a foreign post-extraction
exception returns the durable quarantined operation without fallback/refund.
Assertions check real source/destination/ledger state rather than mock calls.
Direct Java compilation passed; configured standalone formatter applied.
The isolated pinned world suite now collected 83 required tests. First run:
82 passed, existing `uncertainClaimedExtractionBlocksHoldRelease` timed out waiting
for its second callback. All new route scenarios passed. Immediate repeat via
`python3 /private/tmp/factorycore-route-worldcheck/launch.py` passed all 83, exit
zero. The intermittent existing callback timeout is unresolved and remains a
verification concern; no timeout/assertion was weakened. Latest XML/log remain
in the isolated directory. Canonical Gradle gates remain environment-blocked.
Next investigate that callback/admission failure and implement persisted rotation
before wiring the automatic import/export and player terminal loop.


### Claimed cleanup test admission race — 2026-10-12

Traced the intermittent second-callback timeout to the fixture submitting cleanup
while provider quarantine had invalidated the origin's wired topology.
`ConnectorEntity.quarantine()` calls `changed()`; claim admission correctly returns
OFFLINE for an invalid origin and rejected work never calls completion. The test
ignored that admission result. It now waits for VALID origin membership before
submitting the cleanup and asserts OK admission before waiting for its callback.
All existing uncertain-journal, retained-hold and exact physical-stock assertions
remain unchanged; no timeout, scheduler limit or production authorization rule was
weakened. This corrects fixture sequencing to the documented API contract.

Verification: direct Java 21 compilation and configured standalone formatting
passed. Three consecutive isolated pinned NeoForge world launches each executed
83 required tests, server exit zero, with zero failures/errors/skips confirmed
from XML. Commands used the same `/private/tmp/factorycore-route-worldcheck/launch.py`
launcher; separate `repeated-1.xml` through `repeated-3.xml` and matching logs retain
each result. This supports the sequencing correction, without claiming absence
of all flakiness or substituting for blocked canonical Gradle gates.


## Persisted global distribution checkpoint — 2026-10-12

Implemented explicit global priority and persisted stable-ID round-robin routing
for exact-key digital/foreign endpoints. Shared bounded discovery and transfer
steps retain current root/segment/principal checks, resource quanta and quotas.
One actual move quantum may be partial; fallback requires no moved/staged/returned
resources or pending uncertainty. Cancelled/revoked discovery never mutates stock.
Route callbacks expose the actual durable operation identity for recovery.

Round-robin preferences persist under canonical world schema seven, nested cursor
schema one. Scope includes accepted root/segment/principal, stable deployment,
fixed backing, kind and direction. Selection advances before dispatch, skipping
ineligible/full endpoints without treating the cursor as resource ownership.
At most 1,024 fixed-size scopes are retained; admission/dispatch enforce limits.
Schemas one through six migrate with empty cursors and exact existing tables.
Fixed nested-cursor and nonempty schema-six fixtures protect compatibility.
Updated prior migration/future tests target seven/eight while retaining all old
ownership, conservation and raw-tag comparisons. Unknown/malformed/duplicate or
over-limit preferences preserve and quarantine the whole authority.

Four portable cursor/rotation tests and four platform persistence tests cover
stable wrap/absent IDs, duplicate admission, count/reclaim, dirty/no-op behavior,
scoped restore, immutable snapshots/reentry, compressed reload, strict rejection
and exact migration. Eight world routing scenarios cover preferred import/partial
withdrawal, full-target fallback, cancellation, revocation, uncertainty, repeated
rotation and persisted full-target skipping. The previously intermittent foreign
claim cleanup fixture now waits for quarantine-triggered topology revalidation
and asserts admission, preserving its existing journal/hold/resource assertions.

After the user restored unrestricted session permissions, normal
`./gradlew spotlessApply build` passed (38 seconds): all 146 JVM tests and 85
required GameTests executed with zero failures/errors/skips. Java compiler,
Spotless, all PMD source sets, architecture and packaged-artifact checks passed.
`python3 scripts/verify.py` then passed 24 tooling tests and a fresh 85-GameTest run;
JVM/format/PMD/build inputs were UP-TO-DATE, supported by the preceding executed
146-test run. Rust guest verification was unchanged/UP-TO-DATE. Direct fallback
compilation and 128 targeted core/storage JVM tests also passed before normal
permissions returned; those results are narrower evidence, not substitutes for
canonical gates. Earlier sandbox failures remain historical; fresh local required
verification is no longer blocked. Final whitespace/scope/docs review passed.

P1/P2/P3 and A03/A04/A12 remain partial. Next implement the Buffered Interface's
owned inventory/import preset and server-authorized player terminal/all-key search
for the hopper -> storage -> terminal -> withdrawal loop. Full restart/interrupted
saves, crafting/job ownership, production scripting, powered/wireless progression,
client/multiplayer, finished presentation/onboarding and P7 acceptance/performance
remain required; the full A01–A17 release goal stays active.


## Buffered inventory/automation boundary prerequisite — 2026-10-12

Selected one canonical slot-inventory/ResourcePort boundary in ADR 0014 for the
R02/D06 nine real item slots. Implemented ItemBuffer with exact components, current
stack limits capped at 64, bounded slot/bulk operations, detached immutable
snapshots, dirty-on-positive-mutation and callback reentry rejection. Physical
automation and scheduled transfers use the same quantities, never a mirrored
ledger. BufferedItemHandler provides real IItemHandler simulation/remainders and
stale loaded-lease predicate denial, without exposing mutable owned stacks.

Five portable behavior tests exercise full-source/storage backpressure, partially
accepted staging and blocked cleanup without loss, exact nonstackable/components,
changed limits, simulation/reload/dirty/reentry and 5,000 generated operations
(seed 0xB0FFE901). Two actual Minecraft/NeoForge handler cases cover partial
admission, caller-stack immutability, detached reads, shared port quantities and
stale capability denial. An oversized NBT-name regression initially exposed an
UncheckedIOException before insertion; adapter key encoding now returns the full
remainder for that expected unsupported input and preserves buffer/dirty state.

This does not publish a globally available buffer or add a world schema yet. Next
implement canonical buffer slots/physical generation leases and a distinct
authorized buffer endpoint, then the registered Buffered Interface block and
bounded import preset. Real hopper/headless-world/restart/client evidence remains
required. P3/A01/A03/A04 remain partial; the full A01–A17 release goal is active.

Verification: final `./gradlew spotlessApply build` passed in 31 seconds, executing
all 153 JVM tests and 85 required world tests with zero failures/errors/skips;
compiler, formatting, PMD, architecture and packaged-artifact checks passed.
`python3 scripts/verify.py` passed 24 tooling tests and a fresh required world run;
JVM/format/PMD inputs were UP-TO-DATE, supported by the preceding executed test
run. Unchanged Rust guest gate was UP-TO-DATE. The world cases cover existing
features, not the unregistered buffer handler. Final documentation/acceptance and
whitespace/scope review passed. No client or full-restart claim is made.


## Canonical buffered ownership prerequisite — 2026-10-12

Implemented the ADR 0014 canonical inventory boundary: BUFFERED_INTERFACE
structural leases and one authoritative nine-slot ItemBuffer table. Runtime
handle/position/owner-token bindings reject simultaneous borrowers, stale/moved
leases and copies; load restores every inventory unbound with exact slot contents.
No chunk or portable reference owns a duplicate stack list. Metadata admission
preflights both slot simulation and mutation through proposed immutable state.
At most 256 inventories share 16 MiB estimated metadata; extraction reclaims
capacity, and failed buffer placement cannot allocate an orphan structural lease.

Canonical world schema eight adds required nested buffer schema one. Save/restore
requires a one-to-one buffer/node binding and retains normal canonical staging/
grant/slot snapshot boundaries. Schemas one through seven migrate with empty
buffers, preserving old quantities, claims, leases, journal context and rotation.
Existing migration tests now target eight and require empty buffers while their
old full-table equality/conservation assertions remain; unknown future tests use
nine. Fixed sparse-slot and nonempty schema-seven world fixtures protect exact
compatibility. Unknown/malformed/duplicate/unowned/missing/over-limit tables
quarantine the whole raw authority, never fabricate an empty inventory.

Four core registry tests exercise movement/bind/unload/clone, exact ownership
restoration, count limits and pre-mutation metadata reclamation. Five platform
persistence tests cover actual compressed disk reload, exact schema-seven
migration, strict sparse slots, bad/future/duplicate records, count/byte bounds
and failed placement without dirty/orphan state. ItemBuffer remains the shared
slot/transfer port, with optional proposed-state quota admission. The normal
server service installs the registry-aware stack-limit resolver.

Physical block/capability registration, a distinct world transfer buffer endpoint,
break/drop/chunk integration and bounded import/export presets remain missing.
Next implement those together with the native Buffered Interface assets/recipe
and real hopper import/backpressure evidence, then all-key terminal/player flows.
A01 and P3 remain incomplete; the full A01–A17 release scope, real restart/
interruption/client/multiplayer, crafting/scripting/power/wireless/onboarding and
P7 performance/acceptance evidence stay active obligations.

Verification: `./gradlew spotlessApply build` passed the initial ownership slice
(161 JVM tests and 85 existing required GameTests); after the added serialized
metadata/no-orphan regression and final source changes, `./gradlew spotlessApply`
and `python3 scripts/verify.py` passed. The final canonical run executed all 162
JVM tests and 85 required world tests with zero failures/errors/skips, plus 24
tooling tests. Compiler, formatting, all PMD source sets, architecture and
packaged-artifact checks passed. Unchanged Rust guest verification was UP-TO-DATE.
Existing world scenarios remain regression coverage, not physical buffer evidence.
Final documentation/acceptance, whitespace and focused-diff review passed.


## Physical Buffered Interface and hopper inventory slice — 2026-10-12

Registered the Buffered Interface block/item/entity and six-face IItemHandler,
using the canonical nine-slot inventory from schema eight. Local schema-one
reference records world/ID/generation, server placer provenance and configuration
counter only; no chunk/item stock copy exists. Break validates the current holder,
unbinds/detaches its lease and drops one populated portable reference; creative
placement consumes it. Retained capabilities recheck current loaded chunk/entity,
lease/token/conflict and never load chunks. Lifecycle reload rebinds exact slots;
copied placed references quarantine access without creating/dropping quantities.
Unsupported reference payloads remain raw through load/break/replacement.

The device joins live wired geometry without entering shared stock enumeration.
Direct or declared foreign provider groups cannot alias an owned buffer. Actual
vanilla hoppers can insert exact component-aware items into nine slots, retain
input against full slots and resume after space returns. Empty-hand inspection
checks current VIEW before reporting item/occupied-slot counts. Local mechanical
access works independently of a valid network and cannot withdraw network stock.
The saved principal is provenance, not authority; upcoming presets must establish
server-owned deployment ownership and current grants rather than trust item data.

Selected a distinct starter recipe: four iron, two glass, two redstone, one hopper
(IGI/RHR/IGI), recipe-book unlocked by hopper. Native original JSON model uses the
bank palette with a recessed tray and nine raised slot markers; item/blockstate,
creative listing, tooltips and translated inspection are wired. Asset inventory
records actual paths and pending client inspection. Player guide documents what
works and explicitly retains automatic network import/export as unfinished.

Two BufferDataTest cases freeze strict six-field local schema one. Seven required
BufferedInterfaceGameTests prove actual hopper insertion/backpressure/resumption,
six faces, wired discovery, creative placement/break/movement, stale capability,
lifecycle clone isolation, raw future data, foreign group rejection and exact
recipe/unlock. `./gradlew spotlessApply build` passed initially; after adding the
nonloading physical-holder guard, `./gradlew spotlessApply` and
`python3 scripts/verify.py` passed. Final canonical verification executed all 164
JVM tests and 92 required world tests with zero failures/errors/skips, plus 24
tooling tests. Compiler, formatting, all PMD, architecture and packaged-artifact
checks passed. Unchanged Rust guest verification was UP-TO-DATE. Documentation/
acceptance, focused scope and whitespace review passed.

This verifies hopper -> real buffer, not hopper -> network storage -> terminal.
Next add the distinct authorized buffer transfer endpoint and bounded import
preset through shared scheduling/accounting, then all-key search and player
terminal operations. A01/P3 and full A01–A17 release remain incomplete. Actual
chunks/server restart/independent interruptions, client/multiplayer, finished
preset/terminal UI/onboarding, crafting/scripting/power/wireless and P7 remain
required release work; no headless result substitutes for that evidence.


## Explicit shared buffer transfer prerequisite — 2026-10-12

Added typed Buffered(handle, configuration) source/destination endpoints to the
existing WorldStorageTransfers and priority/round-robin fixed-endpoint routes.
They use current loaded canonical references/configuration, actor grants and
origin/target segment visibility; buffers stay excluded from shared stock rows.
Native item identities must round-trip; fluids/FE fail before queueing. Full slot,
metadata and shared rate admission precede source mutation. Network input/output
each share 64-item allowances per buffer backing/tick across every wrapper; native
mechanical hopper inventory remains local. Typed ItemBuffer.admitInsert identifies
metadata TECHNICAL_LIMIT separately from FULL, with no mutation by simulation.

Removal, reference reload and placement release indexed transfer bindings only
for the matching handle/token owner, preserving staging/journal data and releasing
live entity references. Stale copies cannot invalidate replacement holders.
Explicit progress rebinds accepted buffered endpoints, checks supported native
identity and only delivers/returns existing staging, never re-extracting refills.
The saved reference principal remains provenance, not preset authorization.

World schema nine adds no owned table: it permits BUFFER endpoints in context
schema two, with equal device/backing and positive lease/configuration versions,
and item staging only. Context two exclusively describes an operation involving
a buffer; old digital/foreign context-one bytes are unchanged. Schemas one through
eight migrate without changing existing quantities, tables or intent evidence.
Legacy migration/future tests now target nine/ten, preserving prior comparisons.
Fixed context-two/nonempty world-eight fixtures and compressed buffer-source/
staging disk recovery add compatibility evidence. Unknown/invalid/future union,
kind or schema retains raw quarantined authority data.

Nine real world cases cover exact partial buffer-to-cell movement, full-buffer
preflight, revocation, old configuration after valid topology rebuild, shared
source/input budgets and independent directions, preserved staging delivery,
private-child denial even for parent/operator and global priority routing from
a fixed buffer. The rate fixtures advance at most sixteen queue steps within one
fixed Minecraft tick to separate rate behavior from scheduler wall-clock cutoffs;
production scheduling is unchanged. Three platform persistence cases prove exact
fixture/version behavior and orderly compressed owned source/staging recovery.

Verification: `./gradlew spotlessApply compileGameTestJava` passed. Final
`python3 scripts/verify.py` passed: 24 tooling tests, 167 JVM tests, 101 required
Minecraft GameTests, formatting, static/architecture checks and the JVM build.
The canonical Gradle build completed in 34 seconds; cached Rust guest verification
was up-to-date. These headless results do not prove client or release acceptance.

Next establish canonical authorized preset deployment, then bounded automatic
buffer import through the shared route/transfer services. Reference provenance
alone cannot authorize automation. Automatic presets, searchable terminal player
operations, crafting, production scripting, power/wireless, onboarding and full
restart/client/multiplayer/performance evidence remain incomplete. The full
A01–A17 goal remains active.


## Canonical buffered preset authority prerequisite — 2026-10-12

Added one server-owned fixed-size PAUSED/IMPORT deployment per buffer with accepted
principal/root/segment/lease and monotonically increasing configuration revision.
The loaded holder authenticates EDIT/DEPLOY and enabling IMPORT additionally
requires VIEW/DEPOSIT/WITHDRAW. Operator/provenance flags cannot substitute for
those grants. Denied requests preserve state; authorized replacement invalidates
old transfer bindings while retaining staging. Moving a holder preserves its
old accepted lease and therefore requires reconfiguration before future dispatch.
Nested buffer schema two persists records with strict field/ownership/scope/
future-generation checks; no records retain exact schema-one output. Fixed v2 and
compressed persistence, core replacement/overflow/offline/movement and real world
permission/revocation cases provide evidence. Background dispatch and player
controls remain missing; IMPORT stores intent only in this prerequisite.

Next implement bounded background import with exact canonical/reference/lease/
scope matching, current deployer grants checked at queued mutation, one active
request per buffer, and retained owned staging blocking new extraction. Add real
hopper-to-cell/backpressure/revocation/removal tests and simple player controls.
Then all-key search and terminal operations. A01–A17 release remains incomplete.

Verification: `./gradlew spotlessApply test` passed the initial JVM slice;
`./gradlew spotlessApply compileGameTestJava` passed the world fixture compilation.
Final `python3 scripts/verify.py` passed in a 32-second Gradle build: 24 tooling,
170 JVM (zero failures/errors/skips), 102 required GameTests, formatting/static/
architecture gates. Rust guest verification was up-to-date. Actual client controls,
background transfers, server restart and full release acceptance are unverified.


## Automatic buffer import and native controls — 2026-10-12

Implemented an active-only server import coordinator through shared scheduled
preparation, paged priority routes and conserved staging/progress. It registers
only matching current holders (256 maximum), visits/admission-schedules at most
eight metadata entries per tick and charges sixteen credits per preparation;
at most nine slots select one exact-key request per buffer, up to 64 items. Empty
rules sleep until positive real mutation/configuration; simulations do not wake.
Blocked rules use twenty-tick backoff, with current deployer DEPLOY/resource grants,
exact canonical/local revision/lease and accepted root/segment before each queued
mutation. Disconnects can resume under the same scope; movement/save divergence
cannot implicitly adopt authorization. Fresh/legacy buffers remain paused.

Added an unresolved canonical backing index retaining all known staging and
unknown/pending evidence across unbind/restore. It blocks fresh automatic extraction.
Current matching deployments can retry known delivery without touching refills;
known finished callbacks retire records. No new persistence table/schema is added.
Import/Paused native crouch-use controls, authorized inspection and translated
blocked states/tooltips are wired. Crouch-use with offhand redstone returns only
the original actor's known staging without consuming redstone. Owned return may
use a newer preset configuration after pause/replacement, but exact original
world/backing/structural lease and accepted origin/root/segment still apply;
normal delivery retains exact accepted configuration/current grants. Uncertainty,
moved leases and incompatible scopes never replay/refund resources.

Twelve new world scenarios and an additional portable multi-record conservation
case cover the new behavior and failures; player/module/persistence/ADR/asset docs
are updated. Initial builds found two fixture/signature errors and one erroneous
level helper reference; those were corrected without changing expected behavior
or disabling checks. Two prior complete builds passed eight, then eleven new world
cases; cleanup-inclusive GameTests passed, and final canonical verification is recorded below.
A PMD borrowed-level warning after extracting the physical helper was fixed by
applying the existing narrow Minecraft-owned-level annotation to that helper;
Minecraft owns this level and closing it would be incorrect. No PMD rule was disabled.

Next implement all-key bounded storage search and server-authorized terminal
inventory operations/screens, then remaining filter/export/stock presets. This
closes part of A01/A03/P3, not the full playable release. Full restart/independent
files, actual chunks, client/multiplayer/GUI scales, crafting/production scripting,
power/wireless, finished assets/onboarding and P7 evidence remain incomplete.

Verification: final `python3 scripts/verify.py` passed: 24 tooling tests, 171 JVM
tests (zero failures/errors/skips), 114 required Minecraft GameTests, formatting,
static/architecture checks and the JVM build. The canonical Gradle build completed
in 33 seconds (7 executed/15 up-to-date tasks); Rust guest verification was
up-to-date. `./gradlew spotlessApply` and `git diff --check` also passed. No actual
client/multiplayer/chunk/full-server restart or independent-file interruption pass
is claimed; those remain required release evidence.


## Bounded all-key storage discovery and server search — 2026-10-12

Added owner-thread ledger key pages and an incremental exact-count accumulator.
The selected design in ADR 0015 retains hash accounting with a derived sorted
occupied-key index, sixteen-row/128-KiB key pages and no quantity/schema duplication.
It applies each source/key's full hold once across slot pages; global totals,
available/reserved and observable FE capacity use BigInteger as ADR 0002 requires.
Maximal metadata/deleted cursors always advance, and current lease/offline stock
remain explicit. A 5,000-step generated sequence records seed 0xCA7A106.

WorldStorageCatalog reads at most eight metadata owners and one source page per
shared sixteen-credit scheduled task. It supports current loaded digital and
registered item/fluid/FE sources, excludes buffers and rejects oversized supported
provider windows instead of silently truncating a complete view. Source/root/
actor/full-topology/grant generation checks precede provider reads/publication.
WorldStorageSearch collects registry-text-filtered exact variants incrementally
and provides stable bounded pages behind server-owned IDs. Sixteen searches share
sixteen MiB, per-session count/page/backing ceilings and 200-tick idle expiry;
fault/cancellation/close/expiry release retained metadata. Partial/error stock
never publishes, and a cached result cannot survive actor/grant/topology changes.

Six portable cases and eleven live world scenarios cover identities/holds/large
aggregates, deletion/mutation/restore, private providers, queued revocation,
retained-view invalidation, fault reporting, counts/close/expiry, utility units/
capacity and real shared heavy-metadata quota reclamation. Initial world checks
exposed a handler fault masked by its own quarantine invalidation; error-only
failed sessions now preserve that typed reason for the original actor without
retaining any partial stock. A utility fixture's missing settings import was
corrected; no expected results/checks were weakened. Prior complete builds passed
the initial seven, then nine world scenarios; the final metadata-quota-inclusive canonical pass is recorded below.

The first sorted-contents experiment reduced query copying but regressed quantity
updates. Its raw benchmark is retained separately. The final derived index keeps
hash lookup on hot quantities and measures type-churn/query tradeoffs against
the actual prior d36268b ledger in separate temporary JVMs. Final raw measurements
and their limits are recorded below when available; full server/terminal/D07
workload evidence remains pending.

Next implement the physical storage terminal, server-owned menu/session/key-token
boundaries, item inventory deposit/withdrawal through shared accounting, and
bounded client search/results/screens with progress/staleness/quota feedback.
All-key server search alone does not satisfy the player loop or complete A11.
Remaining filters/export/stock, client/multiplayer/restart/interruption, crafting,
production scripting, power/wireless, assets/onboarding and P7 remain required.

Verification: final `python3 scripts/verify.py` passed: 24 tooling, 177 JVM
(zero failures/errors/skips), 124 required Minecraft GameTests, formatting/static/
architecture checks and the JVM build in 33 seconds. Seven tasks executed/fifteen
were up-to-date, including Rust guest verification. `./gradlew spotlessApply`,
`./gradlew spotlessApply compileTestJava` and `git diff --check` passed.

[Component evidence](evidence/storage-search-v1-2026-10-12.md) records Apple M1/8-GiB
macOS 15.3.1 / JDK 21.0.12 workload, baseline d36268b and raw samples. Registry-only
existing-key mutation medians were 0.167/0.125 µs (old/new); shared 4-KiB prefix
medians 1.709/1.792 µs. Type churn costs more (0.166/0.375 and 3.791/6.917 µs),
while bounded first-sixteen pages use 0.625/2.334 µs and about 1.5 KiB allocation
versus whole copied/sorted baseline 1539.625/191913.417 µs. These are component
observations with documented noise/tradeoffs, not complete query/server throughput
or P7 latency evidence. Actual terminal/client/multiplayer/restart/independent-file
acceptance remains pending. The full A01–A17 goal remains active.

Final diff review found that physical membership alone could include a transiently
inactive digital ledger: the aggregate would then advertise loaded availability.
Loaded-only source rows now filter the ledger's actual loaded flag. An additional
world regression retains a valid physical lease/15 owned items while availability
is inactive, verifies no loaded search row appears, and preserves the quantities.
The final verification below supersedes the earlier 124-GameTest checkpoint.

Final availability-regression-inclusive `python3 scripts/verify.py` passed: 24
tooling tests, 177 JVM tests with zero failures/errors/skips and 125 required
Minecraft GameTests, plus formatting/static/architecture/build gates. Gradle
completed in 34 seconds (10 executed/12 up-to-date tasks); Rust guest verification
was cached. Post-evidence documentation checks and `git diff --check` passed.
The benchmark source digest still matches the final ResourceLedger; the last
guard affects platform availability filtering, not the measured ledger code.


## Authenticated player inventory transfer prerequisite — 2026-10-12

Added a typed PLAYER endpoint/factory for exact live PlayerList bodies, stable
world/player inventory-v1 backing, positive connection nonce and source slot0–35/
whole36-slot destination. Captured ports recheck actor/body/connection/range/
dimension; armor/offhand/cursor/crafting slots are excluded. Simulation/actual
matching-first insertion scan at most two36-slot passes, respect native limits
and share128 input/output units per identity/tick. Native source key/count and
known actual results revalidate; unsupported/wrong-kind, stale source, unauthorized
principal, full/offline/rate/distance/logout requests preserve current owners.
Local source ownership does not require network WITHDRAW for depositing own items;
network DEPOSIT/WITHDRAW and catalog VIEW remain separate. Existing shared queues,
scheduler/preflight/staging/accounting are used; player inventories never become
global stock/claim rows. Unresolved evidence blocks fresh player-backed transfers.

World schema ten admits context-three PLAYER without rewriting old context-one/two
bytes or owned tables. Validate principal/derived backing/item kind/slot schema
before record mutation. Schemas one through nine migrate exactly; future eleven
preserves raw authority. Explicit original-owner progress borrows current same-
player connection for existing known staging without extracting refills or changing
accepted context. Logout unbinds only matching bodies/native references, retaining
canonical evidence. Unknown pending calls remain frozen; independent native player
files/crash atomicity/reconciliation are still unverified D03/P7 work.

Two fixed/migration/compressed persistence cases and eleven real mock-login world
cases cover the boundary and failures. Initial checks found one future-envelope
array still using ten (now current; changed to eleven) and an incorrect rate-fixture
slot expectation. The latter was corrected to the required matching-first layout:
returning64 fills the17-item matching stack with47, then adds17 to an empty slot,
without weakening conservation/rate assertions. Later world builds passed all
eleven cases; final logout-hook-inclusive canonical verification is recorded below.

Next implement physical terminal/meta/menu ownership, authenticated client command
and server-owned selection revisions/tokens, native inventory controls and bounded
search UI/status/assets. This is a transfer prerequisite, not a completed player
terminal/demo/release. All A01–A17 work including remaining presets, crafting,
production scripting, power/wireless, onboarding/assets and P7 remains active.

Verification: final `python3 scripts/verify.py` passed: 24 tooling tests, 179 JVM
tests (zero failures/errors/skips), 136 required Minecraft GameTests and formatting/
static/architecture/build gates. The canonical Gradle build completed in36 seconds
(11 executed/11 up-to-date tasks); Rust guest verification was up-to-date.
`./gradlew spotlessApply` and post-evidence docs/acceptance/link checks plus
`git diff --check` passed. Native menu/client actions, full reconnect/server restart,
independent player/chunk files and multiplayer remain unverified release work.


## Checkpoint — Physical item terminal and authenticated native menu (2026-10-12)

Earliest applicable slice: P3/A01/A03, after bounded all-key search and authenticated
player endpoints. ADR 0017 selects an access-only shell using the existing TERMINAL
wire role; no stock/grants/portable lease or world schema change. TerminalBlock,
TerminalEntity and the common TerminalMenu capture loaded physical identity and a
random per-open session. Server selections retain exact keys under a view revision;
commands carry only bounded action/row/own-slot/text/amount and SHA-256 deposit
identity fingerprint. Shared searches/priority routes/player ports retain authority,
scheduling, rate and accounting. One action is in flight; stale/closed/replaced/
remote/revoked sessions cannot perform queued mutations. Known completed operations
retire; retained staging has explicit owned return and uncertainty remains frozen.

The client-only native item screen provides eight-item pages, registry-text search,
Home/Next, Take 1/64, shift-deposit, known return, exact decimal count hover, translated
status/help, focusable buttons and keyboard row selection. Native four-facing
casing/screen/contact assets, item parent, tooltip, loot, mining tags, creative entry
and affordable recipe/glass unlock accompany it. Docs/player guide/assets distinguish
implementation from client evidence. Full component payloads/cursors/topology/actor
choices never cross the selection boundary; projected icons omit metadata. Rich
metadata tooltips and display-name/tag search/sorts remain future terminal work.

Verification: `./gradlew compileJava spotlessApply`, `./gradlew compileGameTestJava
pmdMain spotlessApply`, and `./gradlew spotlessApply test runGameTestServer` passed
after resolving pinned API differences. Initial world launch failed because native
GameTest mock login lacked NeoForge payload-channel negotiation. Test-only explicit
`NetworkRegistry.configureMockConnection` fixes that fixture without weakening the
production packet boundary. No actual network handshake/client interaction is claimed.
Three TerminalPacketsTest cases cover real bounded codecs, exact counts beyond long,
detached/count-normalized fingerprints and invalid/oversized payload rejection.
The compiled architecture gate now prohibits client dependencies outside the isolated
client package. Eight native TerminalGameTests cover exact menu deposit/withdraw,
full main inventory, wrong session/revision/changed source, close/replacement,
range/revocation, real hopper -> buffer -> cell -> searchable menu -> player inventory,
and exact recipe/unlock. All conservation/permission assertions pass.

Canonical `python3 scripts/verify.py` passed: 24 tooling tests, 182 JVM tests and
144 required GameTests, zero failures/errors/skips, plus formatting/static/architecture/
build gates. Gradle completed in36 seconds (9 executed/13 up-to-date tasks);
Rust guest verification was up-to-date. Reports: build/test-results/test and
build/reports/gametest/results.xml. Final model review separated casing/bezel/panel
front planes to avoid coplanar faces; appearance still requires actual client evidence.

A01/A03/P3 and the full A01–A17 release remain partial. Next verify the physical
terminal screen/payloads in the actual supported client at required scales and
continue remaining import/export/stock/filter controls. Manual crafting/fluid/energy/
job tabs, production crafting/Wasm/DSL, powered/wireless progression, other finished
assets/onboarding, administrator recovery and all P7 restart/independent-file/
multiplayer/performance/survival evidence remain open. The full-release goal stays active.


## Checkpoint — Actual graphical terminal and native opening fix (2026-10-13)

Continuation from 7265aba made authoritative progress: an isolated clientTest mod
and runTerminalClient now exercise the actual supported graphical client/vanilla
integrated world, instead of relying on menu objects alone. It has bounded stages/
timeouts, uses its own generated world/reports, and is compiled/static-checked by
the canonical build while excluded from ordinary runs and the packaged artifact.
No new dependency versions or gameplay platform/persistence choices were introduced;
lockfile configuration coverage was regenerated and reviewed.

Actual physical opening exposed a production defect: native initMenu invokes the
broadcast hook before ServerPlayer assigns containerMenu. The old guard closed the
previous inventory menu, sending an old-menu close after the new open payload.
The client received both and immediately dismissed the terminal. The fixed hook
leaves broadcasts for another current menu passive; current-session execution
guards remain intact. A ninth TerminalGameTest captures real outgoing close packets
and requires no close during initialization. Initial embedded-pipeline naming in
that fixture failed; the corrected adapter uses its actual connection-handler name.
Existing conservation and permission coverage was preserved.

The graphical run also prompted visible empty-slot borders and search preservation
across GUI-scale reinitialization. Its production character/Enter, row-number and
button mouse handlers, real deposit payload, exact hopper conservation and normal
logout/save are verified at scales2–4. Four original framebuffer captures, full log,
reproduction, hardware and limits are in [the evidence record](evidence/terminal-client-v1-2026-10-13.md).
Earlier unsuccessful fixture attempts were not counted as passes; the evidence
explains onboarding/setup/artifact/normal-logout fixes and the narrow PMD borrowed-
resource annotations. Graphical test code contains no production mock authentication.

`./gradlew spotlessApply build --write-locks` passed in34 seconds (8 executed/
18 up-to-date tasks): 182 JVM tests, 145 required GameTests, client-fixture compile/
PMD plus normal formatting/static/architecture/build gates. Final
`./gradlew runTerminalClient --write-locks` passed in35 seconds (1 executed/
8 up-to-date tasks), produced all four captures and normal native shutdown/save.
Production artifact checks now also exclude clienttesting classes. Final canonical
verification is recorded below after the complete evidence/documentation update.

A01/A03/A11/A14/P3 and the full A01–A17 goal stay partial/active. Manual Shift gesture,
hover/focus completeness, additional terminal states/resolutions/OSes, remote
multiplayer/TCP, actual restart/chunks/independent save interruptions and survival/
performance remain unverified. Next finish export/stock/filter presets and their
player controls, then remaining crafting/programming/powered/wireless/onboarding/
assets/P7 obligations; additional client coverage follows each affected flow.

Final `python3 scripts/verify.py` passed after the evidence/documentation update:
24 tooling tests, 182 JVM tests, 145 required GameTests, client fixture compile/
PMD, formatting/static/architecture/packaging/build gates; no failures/errors/skips.
Gradle completed in35 seconds (11 executed/14 up-to-date tasks); Rust guest gate
was up-to-date. `git diff --check` passed. No remote CI, additional OS or full
release acceptance is inferred from these component results.

A final stricter `./gradlew runTerminalClient` artifact check passed in34 seconds
(3 executed/6 up-to-date tasks): all four named nonempty captures are required.
Archived evidence now uses this final run. Its log includes an OpenAL Stop invalid-
name error during shutdown; process exit and UI/network assertions passed, while
audio teardown/playback remains unverified rather than being claimed error-free.

Post-artifact-gate canonical verification also passed in20 seconds (1 executed/
24 up-to-date Gradle tasks): 145 GameTests reran; 182 JVM results and Rust guest
verification were cached. The 24 tooling tests executed. Source hashes match the
archived graphical evidence; final diff review found no disabled checks or fixture
classes in the distributed artifact.


## Checkpoint — Bounded preset selectors and stock deficits (2026-10-13)

Previous goal turn made verified progress with native terminal/client behavior
and committed8dda69f. Earliest next P3/A04 prerequisite is a shared selector and
stock-goal model before persistent export/stock deployment and player editing.
ResourceFilter now provides immutable ALL/INCLUDE/EXCLUDE, registry-default, exact
component and live tag terms, restricted by kind and16-term/256KiB metadata bounds.
PlatformFilterTags resolves current actual item/fluid holders only on the owning
server thread, without inventory scans or quantities. StockTarget computes bounded
physical deficits including known inbound staging without overflowing long sums;
reserved target stock still counts as resident and uncertainty must block callers.
The public [contract](modules/RESOURCE_FILTERS.md) records units, admission, lifecycle,
example and required authority/accounting/reload integration.

Four filter and three stock-planner JVM cases include5000 and10000 generated
operations with recorded replay seeds. Three native world cases exercise actual
vanilla item/fluid tags, exact component distinctions, missing tags and off-thread
rejection. The first build with two native cases passed; the final thread-owned
adapter and its third native case are verified below after documentation updates.

This is component implementation only, not filtered automation or a preset UI.
Existing buffer modes and schemas remain unchanged. Next integrate these models
into canonical bounded deployment metadata, authenticated player settings and
shared scheduled filtered import/export/stock, including queued reconfiguration,
known/uncertain staging, fair partial requests, reload wake and conservation.
A04/P3 and the full A01–A17 goal remain partial/active; all crafting/programming/
power/wireless/onboarding/assets and P7 obligations retain their full scope.

Final `python3 scripts/verify.py` passed:24 tooling tests,189 JVM tests and148
required GameTests with zero failures/errors/skips, plus formatting/static/
architecture/client-fixture compile/packaging/build gates. Gradle completed in37
seconds (11 executed/14 up-to-date tasks); Rust guest verification was up-to-date.
`./gradlew compileJava spotlessApply` and the initial `./gradlew spotlessApply build`
also passed. No deployed preset/client/reload-wake/performance result is claimed.


## Checkpoint — Canonical filtered Import and shared network rate (2026-10-13)

Previous goal turn made verified progress and committed59c92d0. P3/A04's next
prerequisite now consumes those selectors in actual scheduled Import. BufferPolicy
adds item selection and1–64 items/tick shared network I/O, independently per direction.
Default ALL/64 retains buffers-v2 bytes; nondefaults use strict buffers-v3/policy-v1.
World schema ten/physical reference one are unchanged. Pause/resume preserves policy,
replacement advances revision only after combined slot/policy16MiB admission, and
malformed/future policy retains original canonical world evidence. ADR0018 records
alternatives, default/extended metadata charge and ownership/limits.

WorldBufferImports filters nine owned slots and rechecks current selection in queued
route/progress guards. Reconfiguration cannot adopt old/uncertain staging or extract
refill twice. Public native tag rebinding wakes at most256 loaded metadata entries;
normal preparation remains eight visits/tick. Buffer ports retain actual per-backing/
server-tick spent input/output even after rate/config edits, clamping negative remaining
allowance to zero; known cleanup shares input rate and preserves partial staging.
All source/target/root grants, leases, shared scheduler, canonical actual quantities
and priority routing remain in force. Physical hoppers still accept any real item;
selection controls automatic draining, independently authorized moves share rate only.

Authenticated native sample/name-tag/clock/paper controls provide registry/exact/tag
include/exclude selection, rate cycling and reset, with no material consumption.
They retain current mode; existing redstone cleanup has precedence. Translatable
inspection/tooltip feedback and player instructions are included. Multi-rule/advanced
settings, actual new-control client checks, export/stock and other resource interfaces
remain unfinished. No phase/full acceptance completion is claimed.

Two registry tests cover quota/pause/restore/reclamation/no-mutation rejection, two
policy codec cases freeze all term bytes and legacy/default/strict malformed/disk
behavior, and one canonical persistence case verifies compressed policy/slots plus
raw malformed/future preservation. The prior future nested fixture advances3→4
because3 is now supported; conserved stock/raw assertions are unchanged. Seven
world tests cover actual variant imports, retained rejected stock, queued replacement/
resource revocation, shared configured rate, real sample/clock/paper/tag controls,
real registry tag rebinding/wake and known partial return after changed exclusion.
Initial tag-test compilation used a nonpublic holder method and deprecated item
holder lookup; the corrected test uses public registry rebinding and an isolated
fixture item while retaining all vanilla memberships. The test-only item/tag is
excluded from production packaging. Source compilation and focused tests passed;
final canonical verification is recorded below.

Next deliver export/stock modes using the same canonical/scheduled policy and
stock-deficit model, then full authenticated settings/client flows. P3/A04 and the
full A01–A17 goal remain active, including crafting/programming/power/wireless,
finished assets/onboarding and all P7 survival/restart/independent-save/multiplayer/
performance obligations.

Final `python3 scripts/verify.py` passed with24 tooling tests,194 JVM tests and
155 required GameTests, zero failures/errors/skips, and all formatting/static/
architecture/packaging/client-fixture compilation/build gates. Gradle completed
in20 seconds (5 executed/20 up-to-date tasks); JVM and Rust guest results were
cached from the passing focused checks/build. GameTests reran, including the
same-tick lowering regression that forbids budget reset/negative allowance.
`git diff --check` and documentation/acceptance/link checks passed. Actual new
physical-control client/tooltip evidence remains pending, along with all listed
export/stock/full-editor and full-release obligations.


## Checkpoint — Automatic buffered Export/Stock (2026-10-13)

Previous turn made verified progress and committedf62ce3f. The shared coordinator
now supports Export and Stock using scheduled bounded all-key pages and existing
exact global priority routes. It retains at most256 transient cursors under a2MiB
shared charge and32768-page pass bound, never exposes buffer contents as shared
stock, and polls shortages/full/goals at20 ticks with activity/tag/config wake.
Current private scope/grants, reservations, leases, selection and rates remain
mandatory at execution. Known inbound staging blocks fresh work and progresses
under original accepted ownership; unrelated/uncertain work is never adopted.

Stock counts all matching physical slot units toward0–576, using overflow-safe
StockTarget planning and live network insertion caps to prevent over-withdrawal.
Late target arrival can retain known staging. Physical hoppers remain ordinary
access and can exceed a best-effort goal without resources being removed/voided.
Matching explicit network insertion shares the goal cap; original-owner buffer
source return bypasses only goal caps, preserving rate/physical ownership. Export
source cleanup now supports the buffer as the recorded destination. Schema table
four/policy two encode new modes/goals under unchanged world-ten/reference-one;
old Import/Paused defaults/policies retain v2/v3/v1 bytes and strict version unions.

Native compass mode-cycle and chest goal-cycle controls retain filter/rate; other
edits retain target, and translated feedback/help/player contracts are updated.
Full multi-rule settings and actual new-control client interactions remain missing.
Seven new world cases cover real hopper export, excluded source retention, Stock
stop/replenishment, full/vacancy wake, live goal insertion, known inbound/no-refill
re-extraction, paused/zero goals and competing reserved supply. The known-staging
fixture initially passed an owner to unreserved ledger extraction, creating a
zero SHORTAGE operation; it now explicitly extracts unreserved stock with null
claim owner. Its native delivery/refill/conservation assertions remain intact.
Policy codec and canonical compressed persistence add strict v2/v4 migration/future
preservation; future nested fixtures advance4→5 because4 is now supported. Existing
native controls additionally prove compass/chest behavior and setting retention.

Next complete the authenticated multi-rule settings/client controls and then the
remaining machine labels/interfaces, crafting/programming/powered/wireless/assets/
onboarding/P7 work. These are component steps toward the full A01–A17 goal, which
stays active. Actual external-provider mode recovery, client, multiplayer, chunks/
full restart, independent saves, survival and performance remain unverified.

Final `python3 scripts/verify.py` passed:24 tooling tests,196 JVM tests and162
required GameTests, zero failures/errors/skips, plus formatting/static/architecture/
packaging/client-fixture compilation/build gates. Gradle completed in34 seconds
(8 executed/17 up-to-date tasks); Rust guest verification was up-to-date. Final
review moved cursor revision cleanup into the common mode step so switching to
Import cannot retain an old export cursor. No checks were disabled; byte fixtures/
conservation assertions remain. `git diff --check` and doc/acceptance/link checks
passed. New-control client, full editor and the listed release checks stay open.


## Buffer settings and inventory menu checkpoint — 2026-10-13

P3/A01/A03/A04/A12 partial evidence: normal authenticated nearby empty-hand use
opens native Settings/Inventory tabs. Server-owned drafts configure mode,
ALL/INCLUDE/EXCLUDE, sixteen registry/tag/exact rules, rate1–64 and goal0–576;
Apply uses canonical deployment, Reset discards, external configuration changes
invalidate stale edits. Exact samples do not consume items. Inventory Take1/Take64,
shift-deposit payloads and known staging Return use guarded shared transfer paths.
Opening nonce, captured lease/scope, current physical VIEW, edit/deploy checks,
bounded projections, selection revisions and menu-close cancellation are enforced.
No persistence schema change was needed.

Four BufferSettingsGameTests exercise actual physical menu opening, draft/apply/
reset/external invalidation, wrong nonce, ghost sample conservation, stale empty
selection, scheduled exact round trip and out-of-range apply/closure. Three
BufferSettingsPacketsTest cases cover real bounded codecs, detached SHA256
selection digests and oversized/malformed inputs. The native screen and translated
help are implemented; no actual settings-screen client run is claimed.

Verification: `./gradlew spotlessApply build --console=plain` passed (35s;
12 executed/14 up-to-date tasks;199 JVM and165 GameTests before the final range
regression). `python3 scripts/verify.py` then passed with24 tooling tests and cached
JVM/static/Rust tasks. Final `python3 scripts/verify.py` passed (20s Gradle;5 executed/20 up-to-date tasks),
with24 tooling tests,199 cached JVM tests and166 freshly executed required GameTests.
Formatting/static checks passed; Rust guest checks were up-to-date. No test expectations, limits or verification gates were weakened.

Next: actual buffer Settings/Inventory client interactions at supported GUI scales,
including rule paging, ghost samples and shift gestures; then remaining machine
interface bindings/labels and P3 integration. P1/P2/P3 and full A01–A17 remain
partial; crafting, programming, power/wireless progression, finished assets/
onboarding and P7 verification remain required.


## Buffer client verification attempt and partial feedback — 2026-10-13

The prior goal turn made committed progress (3b9485b), not a wait or loop. This
turn extends the isolated actual client fixture for native buffer mode/rate/goal,
registry/tag/rule paging, exact samples, Apply, Stock replenishment/full deposit,
Paused inventory transfer and GUI scales2–4. The command now requires all13
terminal/buffer captures and a PASS result. **New buffer steps remain unexecuted**:
`./gradlew runTerminalClient --console=plain` failed before Minecraft/fixture startup
because NeoForge could not find a primary monitor; CoreGraphics confirmed zero
active displays. The same failed run's dialog was stopped and its process exited1.
A short display wake did not restore a monitor. Exact
[blocked launch evidence](evidence/buffer-client-attempt-2026-10-13.md) is archived.
No new screenshot/client success or multiplayer/restart proof is inferred.

Independent progress closes a player feedback gap: Take64 under rate8 can complete
with only8 actual units, so the menu now projects moved/requested counts and a
Partial status rather than an unqualified completion. Full preflight reports0
and preserves source ownership; configuration changes clear old counts. Buffer
payload negotiation advances to2 with no world/policy schema change. A fifth
native settings case reproduces8/64, full-goal0/1, paused8/8 and exact nine-unit
conservation. Packet round trips preserve the added bounded fields.

`./gradlew spotlessApply build --console=plain` passed (36s,13 executed/13
up-to-date tasks), with199 JVM and167 required GameTests. Final `python3 scripts/verify.py` passed after the configuration-result reset/
protocol changes (34s Gradle,10 executed/15 up-to-date tasks):24 tooling,199 JVM
and167 required GameTests; Rust guest checks were up-to-date. R08/A01/A03/A04/
A11/A12/P3 remain partial; graphical validation requires an active desktop.
Next independent prerequisite is physical machine-interface side/slot/label
bindings and shared resource routes. Full P4 crafting, P5 programming, P6 powered/
wireless progression/assets/onboarding and P7 verification remain required. The
full goal stays active; one unavailable check does not block independent work.


## Local machine binding checkpoint — 2026-10-17

P3/A04/A05/A12/A13/A14 prerequisite: distinct MACHINE_INTERFACE structural leases,
physical socket casing/recipe, local case-sensitive labels, selected item windows
and whole sided mB/FE bindings reuse shared connector identities, overlap exclusion,
rates/holds, authorization and scheduled exact staging. Machines are omitted from
storage catalog enumeration and direct machine access additionally requires the
same segment; inherited storage permission cannot widen machine visibility.
Renamed offhand paper/name tags set labels; sneak-paper clears without consumption.
Relabeling invalidates provider handles and wired publication before queued work.

World11 admits the new kind; reference schema2 adds Label for machines only, while
connector schema1 stays unchanged. Old-world new-kind and future-world raw rejection
are retained. Existing migration expectations advance10→11, future fixtures11→12;
no stock/claims/recovery assertion is removed. The resumed failure was the old
future-schema11 fixture, now a supported schema. Earlier native label setup also
needed to wait for the first relabel's wired rebuild before authorizing the second
edit; the test now checks both edit decisions explicitly.

[ADR 0019](decisions/0019-local-machine-bindings.md) and the
[module contract](modules/MACHINE_INTERFACES.md) document ownership, API, units,
threading, persistence and limits. Three JVM/six native cases add compressed
recovery, selected item conservation, catalog exclusion, local labels/authority,
queued invalidation, sided mB/FE, actual authenticated controls and recipe evidence.
`./gradlew spotlessApply build --console=plain` passed in35s (7 executed/19
up-to-date tasks), with202 JVM and173 required GameTests. Final
`python3 scripts/verify.py` passed:24 tooling tests,202 cached JVM tests and173
fresh GameTests; Gradle19s,1 executed/24 up-to-date tasks. Formatting/static and
Rust guest checks were up-to-date. `git diff --check` passed. Native visuals are
implemented; actual new client evidence, restart/chunks, multiplayer, performance
and survival balance remain unverified.

Next: machine settings/presets, coordinated multiport/selected-tank adapters and
processing prerequisites; resume the prepared buffer graphical check when an
active display is available. P1/P2/P3 and the full A01–A17 goal remain partial.
Crafting, production programming, powered/wireless progression, finished assets/
onboarding and full P7 acceptance remain required.


## Machine settings and actual settings client checkpoint — 2026-10-17

P3/A04/A11/A12 partial progress: native Machine Settings exposes complete local
label/resource/access/priority/item-window/rate forms. Drafts stay client-local;
Reset/Cancel do not publish. Apply validates one bounded form using actual player,
nonce, expected generation, current range/VIEW/EDIT and captured lease/scope/face,
then invokes existing configuration once and closes intentionally. Stale edits
reload current values; lost rights/range cannot save. No provider resource calls,
additional staging or new persistence schema were introduced. Four native menu
and two codec cases add failure/authority and full-form evidence.

Actual `./gradlew spotlessApply runTerminalClient --console=plain` passed in45s
(4 executed/8 up-to-date tasks), requiring17 captures and PASS. Buffer rules,
paging, ghost samples, mode/rate/goal Apply, Stock replenish/full preflight and
8/64 partial moves conserve named cell14+buffer9+player2=25. Machine Reset/Cancel/
Apply, saved complete form and draft retention across GUI scales2–4 passed over
the real integrated-server connection. Representative captures were inspected;
[full evidence and files](evidence/settings-client-v1-2026-10-17.md) are archived.
The earlier no-active-display condition is no longer the latest state. Fixture
focus/stack-merge assumptions were corrected with native callback input and exact
merge counts; no resource/authority assertions were weakened.

Final `python3 scripts/verify.py` passed (Gradle21s;5 executed/20 up-to-date
tasks), with24 tooling tests,204 cached JVM tests and177 freshly executed required
GameTests. Formatting/static checks passed; Rust guest checks were up-to-date.
An earlier canonical run caught a comment-wrap formatting violation, corrected
with `./gradlew spotlessApply` before the passing rerun. `git diff --check` passed. Physical Shift/manual
accessibility, complete model/state checks, multiplayer/TCP, chunks/restart/
independent saves, machine presets/selected tanks/multiport coordination,
crafting/production programming, powered wireless, finished onboarding and P7
remain required. The full A01–A17 goal stays active and incomplete. Next independent
prerequisite is authenticated machine preset deployment and shared scheduled routes.


## Canonical machine preset authority checkpoint — 2026-10-17

Previous turn was committed verified progress (afb73e9). This slice implements
P3/A04/A12/A13's deployment prerequisite, not automatic machine execution.
MachinePresets stores bounded canonical mode/filter/goal, original principal,
structural/configuration generation, selected face, revision, root/segment and grant generation.
Native deployment requires current permissions; captured-record guards reject
changed records, revoked/regranted grants, new principals/roots and stale handles.
Physical binding edits pause intent, so loading old chunk settings cannot reactivate
it. Shared resource/claim/transfer ownership remains unchanged and unadopted.

World12 adds strict MachinePresets schema1 with256-owner/16MiB charges. Legacy
world1–11 migration adds an empty table and preserves other fields. Tests compare
legacy bytes after removing only that new empty table; future fixtures move12→13.
The new fixed selector fixture/compressed round trips and malformed/future whole
raw preservation checks are retained. Four core, three persistence and four
native cases cover admission/reclaim/overflow, immutable identity and real old
chunks/grants/root changes and provider-face rotation. Final review added an
explicit portable face guard so rotating a binding cannot silently retarget intent. An initial migration comparison needed to account for
the newly required empty table; its prior owned-resource assertions remain.

[ADR 0020](decisions/0020-machine-preset-authority.md) records alternatives, units,
limits, recovery and remaining integration. `./gradlew spotlessApply build
--console=plain` passed in33s (7 executed/19 up-to-date tasks). Final
`python3 scripts/verify.py` passed:24 tooling,211 cached JVM and181 freshly run
required GameTests; Gradle20s,3 executed/22 up-to-date tasks. Formatting/static
checks passed; Rust guest checks were up-to-date. `git diff --check` passed.
No new player flow/assets or actual client/preset execution pass is claimed here.
Next: bounded machine preset observation/dispatch, priority routing, live goal/
known-inbound admission and original-owner staged progress, then player controls.
Machine presets remain incomplete; the full A01–A17 goal stays active, including
crafting/programming, power/wireless, finished onboarding and P7 verification.


## Scheduled machine presets checkpoint — 2026-10-17

The previous turn was verified committed authority work (0487aa8). This P3/A04/
A12/A13/A14 slice now attaches loaded-only automatic item/mB/FE Import, Export and
Stock through the trusted server API and shared scheduler/priority/staging paths.
Current filters/rights/leases/configuration guard every phase. Stock counts matching
physical units and live-caps network insertion; known staging prevents re-extraction,
unknown calls freeze, and original-owner returns bypass only goal caps. Runtime
bounds are256 loaded holders, eight visits/tick,16-row observations,64-position
live Stock reads,2MiB cursors,32768 unsuccessful pages and20-tick backoff. Larger
fluid handlers explicitly reject Stock rather than scan unboundedly.

Review exposed a counter-reuse risk after old-chunk replay plus metadata removal.
MachinePresets schema2 therefore retains per-node canonical configuration marks,
including after removal. Active deployment advances beyond native/canonical marks;
Pause retains endpoint configuration for owner cleanup. Admission prepares before
physical writes and commits only the expected successor. Schema1 migration derives
marks from deployments and accepted contexts; current references may raise them.
World12/reference2/context3 remain unchanged. These fences close accidental adoption
of old/manual requests without inventing a new resource or permission path.

Nineteen native runtime cases cover all kinds/modes and failures/conservation,
including explicit recovery after removal/replay. Core/persistence checks add
prepared successors, quota/overflow and compressed retained marks. Early quota and
uncertainty fixtures incorrectly assumed a private SavedData; they now account
for shared-world capacity, clean up temporary rows and inspect their own backing.
No production quota, test gate or expected resource total was weakened.

[ADR 0021](decisions/0021-machine-preset-dispatch.md) and the
[module contract](modules/MACHINE_INTERFACES.md) record current behavior, budgets,
compatibility and limits. `./gradlew spotlessApply build --console=plain` passed
in37s (13 executed/13 up-to-date tasks). Final `python3 scripts/verify.py` passed:
24 tooling,214 cached JVM and200 freshly executed required GameTests; Gradle20s,
3 executed/22 up-to-date tasks. Formatting/static passed; Rust guest checks were
up-to-date. `git diff --check` passed.
Player preset authoring and actual client dispatch are next; selected tanks,
multiport coordination, real chunks/restart/independent saves, multiplayer,
performance, crafting/programming, power/wireless and finished onboarding/P7
remain required. Full A01–A17 remains active and incomplete.

## Player machine presets checkpoint — 2026-10-17

P3/A04/A12/A13/A14 adds Binding/Presets tabs to the existing authenticated physical
menu. Mode/filter/goal/rules are server drafts; explicit Deploy uses the canonical
preset API and current grants. Exact item/contained-fluid/FE samples consume
nothing. Known original-owner staging returns through guarded shared progress,
including VIEW-only cleanup after deployment-grant revocation. A separate bounded
unresolved-device index avoids scanning the transfer registry on every UI poll.
Wrong nonces/revisions/configurations cannot overwrite current intent. World12,
machine reference2, preset table2 and transfer context3 remain unchanged.

Two packet tests and one index regression raise the JVM suite214→217. Four menu
cases and one actual scheduled menu-return case raise required GameTests200→205.
They cover draft isolation/publication, nonce/stale guards, utility samples,
unsupported selectors and live DEPLOY revocation. Existing authority, uncertainty,
conservation, quotas and persistence assertions remain intact.

`python3 scripts/verify.py` passed24 tooling,217 JVM and205 required GameTests;
Gradle34s,6 executed/19 up-to-date tasks. Formatting/static checks passed; Rust guest
checks were up-to-date. `./gradlew spotlessApply runTerminalClient --console=plain`
passed50s,3 executed/9 up-to-date tasks, with20 captures. The actual player authors
Stock/exact sample/goal8 and deploys through native screen/C2S handlers; exactly8
items reach selected slot2, conserving cell6 + machine8 + buffer9 + sample2 =25.
Representative scales2/4 captures were inspected without observed clipping.
[Graphical evidence](evidence/machine-preset-client-v1-2026-10-17.md) archives seven
machine captures, full run log/result and checksums. `git diff --check` passed.

The UI conservatively exposes only the first UUID-sorted unresolved device record;
another owner's earlier operation can hide later cleanup until resolved. Broad
recovery administration and actual restart/independent saves remain unfinished.
Selected tanks, coordinated disjoint ports, permissions UI, multiplayer, physical
Shift/accessibility, representative performance, crafting, production scripting,
power/wireless and finished onboarding/P7 are open. P0 remains locally verified,
P1/P2/P3 partial and full A01–A17 active. Next: close the earliest remaining storage/
preset prerequisite and add real restart evidence before dependent crafting work.

## Nonempty orderly world restart checkpoint — 2026-10-17

P2/P3/A01/A03/A04/A12/A13/A14 now has a real two-JVM integrated-world experiment.
`runTerminalRestartClient` depends on the full graphical seed run, which adds a
mixed fluid cell,400mB original-principal hold and500FE bank. The second process
requires the seed JVM to have exited, opens the existing world normally and
compares complete canonical SavedData before mutation. Native player/provider/
buffer contents, identities, configuration, grants and availability also match.
It then removes3 physical chest items into the player and requires saved Stock
to resume without redeployment, conserving25 items. The original1200mB water
(400 held/800 available),300mB lava and500FE survive the normal save/reopen.
This is primitive reservation evidence, not implemented crafting-job recovery.

The default second window revealed a production layout bug: the272-pixel preset
panel clipped at a427×240 GUI. The panel is now300×236, four visible rules with
Prev/Next across all sixteen, and a shared Cancel/Reset/Deploy/Return footer.
The actual client requires every visible control to fit the screen; the final
native capture was inspected without observed clipping. Goal tooltip units also
use items/mB/FE rather than rate units. No resource/persistence schema changed.
An initial fixture-only bank fill failed before wired availability; setup now
waits for current loaded membership without bypassing or weakening that guard.

`./gradlew spotlessApply runTerminalRestartClient --console=plain` passed67s,
7 executed/8 up-to-date tasks: seed PID23720 exited before restart PID23774; all20
seed captures plus the native reopened capture exist. No ERROR appears in the
final combined log. [Evidence and limitations](evidence/world-restart-v1-2026-10-17.md)
archive expected observations, restored canonical SNBT, results/log/checksums and
screenshot. `python3 scripts/verify.py` passed24 tooling,217 JVM and205 required
GameTests; Gradle36s,9 executed/16 up-to-date tasks, Rust guest checks up-to-date.
Formatting/static/architecture checks and `git diff --check` passed.

This closes the blanket lack of any nonempty actual restart evidence, not D03 or
A13. Independent chunk/player/controller saves, forced unload, interrupted foreign
writes, unknown-call administration, dedicated-server restart, separate worlds,
multiplayer and representative performance remain open. P1/P2/P3 remain partial;
crafting, production scripting, power/wireless, assets/onboarding and P7 still
prevent full release completion. Next: actual chunk lifecycle and independent-save/
recovery prerequisites, then selected tanks/coordinated ports and crafting.


## Native one-chunk lifecycle checkpoint — 2026-10-17

P2/P3/A03/A04/A12/A13/A14 extends the actual restart fixture with a native
vanilla distance-driven unload/reload. The second JVM disables spawn retention
only in its generated fixture world and teleports the real player1024 blocks away.
Native events plus getChunkNow absence and removed holders prove actual unload.
For40 ticks, digital stock is unavailable, item/fluid/FE ownership and holds remain,
cached capabilities reject mutation, complete canonical state is unchanged and
no probe reloads the chunk. Returning creates fresh physical holders with the
same leases/provider backing and validated original preset authority. Old
capabilities remain inert. A real one-item chest removal then triggers saved
Stock, conserving cell2 + machine8 + buffer9 + player6 =25. No production lifecycle
method or synthetic event is used to imitate the transition.

`./gradlew spotlessApply runTerminalRestartClient --console=plain` passed69s,
5 executed/10 up-to-date tasks. Seed PID34423 exited before restart PID34481;
observed departure45/unload51/return91/reload92/completion113 server ticks included
one native unload and one native load. No ERROR appears in the final combined log.
[Evidence](evidence/chunk-lifecycle-v1-2026-10-17.md) archives expected/observed state,
results, native screen, log and checksums. This is a small functional experiment,
not representative performance evidence. `python3 scripts/verify.py` passed24
tooling,217 cached JVM and205 freshly executed required GameTests; Gradle20s,
4 executed/21 up-to-date tasks. Formatting/static/architecture checks passed,
Rust guest checks were up-to-date, and `git diff --check` passed.

This is one network contained within one chunk. Root/branch split across chunks,
staged/queued unload, independent player/chunk/controller saves and uncertain
external-write administration remain required recovery work. Next: cross-chunk
root suspension and staged unload/save-order prerequisites, then remaining
selected-tank/coordinated-port work and crafting. Full A01–A17 remains active;
P1/P2/P3 partial, production crafting/scripting/power/wireless/onboarding/P7 unfinished.


## Flat discovery queues and loaded-child/root-unload checkpoint — 2026-10-17

P2/P3/A03/A04/A05/A12/A13/A14 now has a native loaded-child/root-unload case.
A child gateway/drive/machine remains physically loaded while the upstream
controller and part of its path actually unload. For40 ticks, child stock/holds
remain owned, availability is zero, claimed extraction and cached machine
mutation reject, saved Stock does not replenish and probes never reload the root.
Rejoining the original controller preserves its lease and revalidates saved child
Stock. Gold conservation finishes at cell13 + machine8 + player3 =24, hold4 retained.
The fixture explicitly releases administrative tickets and returns to the main
network/native screen. No production chunk loading is introduced.

The initial48-cable setup exposed a real production defect: recursive retained
DiscoverySeeds hit depth32 under repeated finite region merges and left the
network rebuilding. An exclusively owned flat frame queue now transfers pending
iterators in constant metadata work, preserves charged completion anchors and
empties/freezes the previous producer. Combined admission checks8,192 frames
before any transfer; it never polls callbacks or discards rejected cursors. This
removes obsolete recursive-depth rejection while strengthening aggregate bounds;
region replacement-depth32 and existing discovery tick/seed/probe/vertex budgets
are unchanged. ADR0011 records alternatives/consequences. Five core cases now
include512 transfers and combined admission without lost positions or callback
execution. JVM count217→218; no unrelated assertion, quota or CI gate was weakened.

Fixture development also established vanilla generation-dependency retention:
four chunks of separation did not unload the root, and a player near only one
end did not initially load the longer cable path. The final root chunk111 and
child chunk129 use272 cables, explicit initial endpoint retention and a midpoint
player. Only the child remains retained during root loss. Root departure146/
unload155 and112 total split-lifecycle server ticks are observations, not measured
performance claims. Seed PID35817 exited before restart PID35873.

`./gradlew spotlessApply runTerminalRestartClient --console=plain` passed76s,
5 executed/10 up-to-date tasks; the existing restart, one-chunk and20 seed/native
capture checks also passed. Final log has no ERROR. [Evidence](evidence/split-root-v1-2026-10-17.md)
archives the original depth failure, final log/results/expectations/SNBT/native
capture and checksums. Failed/aborted setup attempts are not counted as passes.
`./gradlew spotlessApply test --console=plain` passed18s,6 executed/8 up-to-date;
final `python3 scripts/verify.py` passed24 tooling,218 cached JVM and205 freshly
executed required GameTests, Gradle20s,2 executed/23 up-to-date tasks. Formatting/
static/architecture checks passed, Rust guest checks were up-to-date, and
`git diff --check` passed. Final docs/acceptance and archived checksum checks passed.

Full A01–A17 remains active; P1/P2/P3 partial. Next: accepted staged operations
through unload/restart, independent save ordering and supported recovery; remaining
selected tanks/coordinated ports and crafting follow those prerequisites. Gateway-
only/deeper/multiple-branch faults, actual dedicated/multiplayer, performance,
production scripting/power/wireless and finished assets/onboarding/P7 stay open.

## Required native staged-operation restart checkpoint — 2026-10-17

P2/P3/A03/A13/A14 now has actual accepted staged operations across a dedicated
server save/restart. A shared excluded provider fixture uses optimistic simulation,
actual partial acceptance and post-insert fault injection. Three real scheduled
DIGITAL→EXTERNAL submissions debit8 from each20-unit exact key and physically
insert3: two retain known staging5/delivered3; the third retains QUARANTINED
pendingDELIVER8/delivered0 after the provider throws. No direct registry start,
fabricated snapshot/context or expected-state restore creates these records.

A separate JVM opens the saved world and requires complete canonical equality,
persisted native inventories/identities/counters/backing markers and source debits
before progress. Existing known staging delivers5 without extraction (12+8=20);
known cleanup returns5 to its original source (17+3=20) without another destination
call. Complete empty records retire. Uncertain delivery/return reject, cannot
retire, preserve evidence and make no further native calls. The pending8 is not a
proven refundable balance additional to physical3. A fixed trusted synthetic
program principal exercises server authority; no player-login/session/UI claim.

`runStagingRecoveryServer` is now required by Gradle check/canonical CI, with a
fresh isolated seed world and separate restore JVM, explicit valid flat settings,
ephemeral loopback server and normal save/exit. Test-mod common/server registration
is separated from client-only setup; actual dedicated startup proves no screen
class loading. Production packaging excludes all fixture classes/resources.
The graphical restart driver shares the same provider/logic but its changed setup
hook and staging steps remain unverified: GLFW could not find a primary monitor,
and macOS CoreGraphics returned activeDisplays=0/result0 twice. That blocked
attempt was terminated; it is not counted as a passed client check. Independent
work continued through the new headless native gate rather than removing graphics
requirements.

An initial dedicated run passed native recovery but exposed a flat-settings
fallback; explicit layers/biome corrected it. A canonical attempt caught formatting
of an added identity assertion; formatting was applied, without weaker checks.
Final `python3 scripts/verify.py` passed24 tooling,218 cached JVM,205 freshly
executed required GameTests and the three-case/two-JVM staging probe. Gradle45s,
31 tasks:7 executed/24 up-to-date; Rust guest checks up-to-date. Formatting/static/
architecture/packaging checks passed. Seed PID36849 exited before restore PID36862;
final combined log contains no ERROR. [Evidence](evidence/staged-server-v1-2026-10-17.md)
archives reports, expected/restored state, verifier log, blocked graphical launch,
display observation and checksums. `git diff --check`, final documentation/
acceptance validation and archived checksum checks passed.

This is orderly dedicated recovery of known and post-mutation-uncertain operations,
not an interruption between foreign mutation and journal return, independent
player/chunk/controller save-order proof, staged chunk unload or completed recovery
administration. Those, graphical verification, multiplayer and performance remain
open. Next: supported recovery inspection/known cleanup and independent-save
boundaries, then remaining selected tanks/coordinated ports and crafting. Full
A01–A17 stays active; P1/P2/P3 partial and production crafting/scripting/power/
wireless/finished assets/onboarding/P7 remain unfinished.


## Recovery inspection and audited cleanup checkpoint — 2026-10-17

P2/P3/R09/A03/A12/A13/A14 adds operator-only `/factorycore recovery list [after]`
and `show <id>`, original authenticated player `return <id>`, and operator-only
`retire <id>` for known complete/no-pending/empty records. Pages are immutable,
exclusive UUID order,1–16 rows, without provider calls or whole-registry copies.
Show gives exact counts, principal/scope/origin/device IDs, bounded provider
messages and a safe next action. UUID/status arguments are stringified for real
Minecraft translatable messages. No raw components go into chat.

Cleanup checks command source identity as well as entity, live PlayerList body
and original owner, preventing console/execute-as/other-owner adoption. It uses
existing shared scheduled progress with accepted scope/endpoint/rate/access
checks, never extraction. Before queueing it preserves an exact content-addressed
schema1 SNBT audit snapshot. Completed records remain until explicit operator
retirement archives completion first. IO/quota/corruption failure blocks mutation;
uncertain/live states cannot retire/refund/replay. World12/transfer-entry2/context3
remain unchanged. Audit limits are512 files/512KiB each/32MiB total, bounded scans/
writes, owner20-tick throttling and server retirement throttling. Synchronous force
latency may delay a command and is not a P7 throughput claim. ADR0022 records
ownership, evidence lifecycle, alternatives and remaining recovery boundaries.

Core/audit cases raise JVM218→221. Two native dispatcher cases raise GameTests
205→207, including unsuppressed positive feedback, source/entity authentication,
audit-before-queue, exact15-unit return, explicit empty retirement and denied
other-owner/uncertain work. Required dedicated seed/restore additionally exercise
real console list/show against accepted uncertain records. Initial validation
caught the borrowed-server PMD annotation, distinct audit envelope/world schemas
and invalid UUID/enum translation arguments; all were corrected without weaker
gates or assertions. [Evidence](evidence/recovery-commands-v1-2026-10-17.md)
archives exact before/completed audit files, native output and checksums.

Final `python3 scripts/verify.py` passed24 tooling,221 JVM,207 required GameTests
and both dedicated staging JVMs (seed38214/restore38226). Gradle59s,31 tasks:
13 executed/18 up-to-date; Rust guest checks up-to-date. Formatting/static/
architecture/packaging checks passed, final log has no ERROR, and `git diff --check`
passed. Final documentation/acceptance and artifact checksum validation passed.

This is inspection/known cleanup/empty admission reclamation, not full physical-
ownership conflict repair or uncertain external reconciliation. Next: independent
save-order/interruption boundaries and supported ownership-conflict cases, then
remaining selected tanks/coordinated ports/crafting. Client chat layout, actual
remote multiplayer, IO/overload performance and full A01–A17 are unverified.
P1/P2/P3 remain partial; production crafting/scripting/power/wireless, finished
assets/onboarding and P7 remain unfinished. The full goal stays active.

## Audited canonical cell repair checkpoint — 2026-10-17

P2/R09/A03/A12/A13 now supports operator `/factorycore recovery cell list [after]`,
`show <backing>` and `repair <backing> <expected-generation>`. Repair requires the
loaded original canonical drive slot, exact current physical reference/tier and no
unresolved transfer. It preserves full ledger/claims/physical stack evidence before
consuming the generation. Quantities, reservations and canonical location stay
unchanged; old clones become stale. Normal network revalidation precedes routing.
Wrong holders/tiers/generations, unreadable references, overflow, audit failures and
unresolved transfers refuse without changing authority or fabricating resources.

[ADR0023](decisions/0023-canonical-cell-repair.md) records trusted API boundaries,
server-thread ownership, separate bounded binary schema1 audit, limits and alternatives.
World12/ledger1/cell-reference1 remain unchanged. Device audits allow512 files,
9MiB each/32MiB total with bounded serialization/directory/write checks and one
explicit repair attempt per20 ticks/server; existing transfer SNBT limits remain.
Missing/stale canonical holders and interrupted independent-save mismatches remain
inert and unsupported by this command. Other owner kinds and unknown external
outcomes are not repaired. No crash atomicity or IO latency claim is added.

Two core and three audit cases raise JVM221→226; three native cases raise
GameTests207→210. Tests cover exact100/60-unit preservation, canonical selection,
wrong tier/holder/generation, old-copy and old-chunk replay rejection, normal network
reactivation, binary evidence reuse/corruption/size/output failures and an accepted
97-source/3-staging unresolved transfer blocking rotation. Full results and exact
native audit are archived in [evidence](evidence/canonical-cell-repair-v1-2026-10-17.md).

Final `python3 scripts/verify.py` passed24 tooling,226 JVM,210 required GameTests
and dedicated seed39018/restore39053. Gradle1m1s,31 tasks:13 executed/18 up-to-date;
Rust guest checks up-to-date. Formatting, PMD, architecture and packaging passed.
Final log has no ERROR; final documentation/checksum validation and `git diff --check`
passed. No required gate or assertion was weakened.

Next prerequisites remain supported recovery for other ownership kinds and
independent-save/interruption boundaries, selected tanks/coordinated ports and
remaining P1/P2 integration. P1/P2/P3 stay partial; crafting, production scripting,
powered/wireless progression, finished assets/onboarding, remote multiplayer and
P7 release verification remain unfinished. The full goal stays active.

## Audited canonical energy-bank repair checkpoint — 2026-10-17

P2/R09/A04/A12/A13 extends canonical recovery to finite energy banks. Operator
`/factorycore recovery bank list [after]`, `show <backing>` and
`repair <backing> <expected-generation>` share cell command permission/throttle
and metadata infrastructure. Kind-filtered pages inspect at most16 handles and
can return zero matches with a next cursor. Show/repair reject the other kind.
No provider reads or chunk loading are added.

The loaded original bank adapter validates exact raw/parsed lease, finite ENERGY
tier/capacity/location/slot0 and no unresolved transfer. It archives complete
canonical ledger/claims and native block state/reference before the existing
canonical generation transition. FE, claims and location stay unchanged. Normal
network validation precedes availability. The same world EnergyBankAccess service
retains same-tick spending by backing ID; clones/cached clone capabilities stay
inert. Unknown/stale/missing/raw holders, unresolved transfers and evidence failure
remain denied. No independent-save mismatch is silently reconciled.

Standalone bank audit schema2 adds HolderKind/BlockState/BlockEntity; existing
cell audit schema1, world12 and bank reference1 stay unchanged. Quotas and the
explicit20-tick repair throttle are shared across cell/bank actions. ADR0023,
module/player contracts and translatable operator feedback are updated together.
This does not add a restore/import path or claim crash/IO latency guarantees.

One audit JVM case raises226→227; three native cases raise210→213. Actual command
checks preserve900FE/200 claimed, permit only100 more input in the same tick,
then800 unreserved extraction, and test stale cached capabilities, repaired
reference/canonical reload and old snapshot inertness. Audit failure leaves exact
physical/canonical state; a real400-bank/100-staged unresolved transfer blocks
rotation unchanged. [Evidence](evidence/canonical-bank-repair-v1-2026-10-17.md)
archives the full final log, exact before-state bank NBT, JVM and dedicated reports.

`./gradlew spotlessApply compileJava` and
`./gradlew spotlessApply test runGameTestServer` passed (227 JVM/213 required native).
Final `python3 scripts/verify.py` passed24 tooling,227 JVM (up-to-date),213 executed
GameTests and dedicated seed39572/restore39587. Gradle47s,31 tasks:10 executed/
21 up-to-date; Rust guest checks up-to-date. Formatting/PMD/architecture/packaging
passed; final log has no ERROR. Final docs/checksums and `git diff --check` passed.

Next: buffer/structural ownership recovery and independent-save/interruption
boundaries, then selected tanks/coordinated ports and remaining P1/P2 integration.
Crafting, production scripting, powered/wireless progression, finished presentation/
onboarding, remote multiplayer and P7 remain unfinished. Full goal stays active.

## Audited canonical buffer repair checkpoint — 2026-10-17

P2/P3/R09/A03/A12/A13 adds operator `/factorycore recovery buffer list [after]`,
`show <backing>` and `repair <backing> <expected-generation>`. Pages examine at most16
structural handles and filter buffer kinds; empty matching pages retain a next
cursor. Show reports exact owned total and accepted original deployment metadata
without providers/chunk loading. Repair requires the loaded original holder with
its exact raw/parsed reference and no unresolved transfer.

The reusable trusted NodeRegistry transition consumes the conflicted generation,
retains ID/kind/position/policy and changes no permission network record. Public
physical exposure is limited to Buffered Interfaces. BufferRegistry removes old
binding only on success; slots, accepted deployment and metadata charge remain
unchanged. The physical adapter audits first, rotates and binds the new lease.
Old handlers/queued work become stale. The original deployment still accepts its
old lease, so it stays inactive until a currently authorized player explicitly
reapplies. Operator repair never adopts the deployer or bypasses revoked grants.
Local hopper access retains existing physical ownership behavior.

[ADR0024](decisions/0024-buffer-conflict-repair.md) records scope, typed failures,
owner-thread ownership and alternatives. Standalone audit schema3 preserves one
structural record, exact nine-slot state/admitted limits/components, optional full
deployment and native physical state/reference. Existing cell1/bank2 audit formats,
world12 and buffer reference1 remain unchanged. Shared9MiB/file/512-file/32MiB
archive limits and20-tick cell/bank/buffer throttle remain bounded. No import path,
background thread, crash atomicity or IO latency claim is introduced.

Four core and two audit cases raise227→233 JVM tests. Three native cases raise
213→216 GameTests, including real console permission/kind/generation/holder checks,
exact19-unit retention, distinct physical principal/accepted deployer, retained
policy, old queued-work/handler refusal, physical/canonical reload and stale chunk
replay. A registered ordinary player opens the actual settings menu: revoked DEPLOY
refuses Apply, restored grants permit fresh Apply under the retained original
principal/policy and shared Import conserves buffer0/cell19. Audit failure retains
all canonical/physical evidence; known16-buffer/3-staged ownership blocks repair.
[Evidence](evidence/canonical-buffer-repair-v1-2026-10-17.md) archives the final
log, exact schema3 before-state NBT and core/audit/dedicated reports.

`./gradlew spotlessApply compileJava` and
`./gradlew spotlessApply test runGameTestServer` passed. First canonical verification
passed native checks but flagged the new borrowed-server throttle helper in PMD;
a narrow resource-ownership annotation fixed it without changing rules/assertions.
Final `python3 scripts/verify.py` passed24 tooling,233 executed JVM,216 required
GameTests and dedicated seed40326/restore40340. Gradle59s,31 tasks:10 executed/
21 up-to-date; Rust checks up-to-date. Formatting/static/architecture/packaging passed,
final log has no ERROR, and final docs/checksums/`git diff --check` passed.

Next: other physical structural holders, ownership conflicts combined with known
accepted staging, and independent-save/interruption reconciliation. Selected tanks/
coordinated ports, remaining P1/P2 integration, crafting, production scripting,
powered/wireless progression, finished presentation/onboarding, multiplayer and P7
remain unfinished. This is verified recovery progress, not full D03/A13 or release
completion. Full goal stays active.

## Audited controller/gateway repair checkpoint — 2026-10-17

P2/R09/A03/A05/A12/A13 adds operator `/factorycore recovery node list [after]`,
`show <id>` and `repair <id> <expected-generation>` for controllers/gateways only.
List visits at most16 structural handles and filters supported kinds; empty
matching pages retain a continuation cursor. Show presents original controller
owner/grant metadata or canonical gateway policy without providers/chunk loading.

The trusted physical adapter checks the exact loaded original holder/raw reference,
kind/generation/position and absence of unresolved records using the node itself
as backing/endpoint device. Audit precedes the existing structural lease transition.
Logical network/segment identity, controller owner/grants/permission generation,
gateway policy/orientation and resource endpoint leases stay unchanged. Normal
budgeted topology publication precedes routing; old scopes/copies are stale.
Ordinary accepted transfer evidence is untouched and remains subject to current
authority/loaded-endpoint/uncertainty guards; repair makes no provider/progress call.

[ADR0025](decisions/0025-network-node-repair.md) records alternatives, package/thread
boundaries and standalone audit schema4: WorldSchema12/World/HolderKind, one Nodes
record, complete controller Networks record or empty gateway list, native BlockState
and BlockEntity. World12/reference1/audit1–3 remain unchanged. Shared9MiB/file/
512-file/32MiB archive limits and20-tick server throttle remain enforced. No restore
path, arbitrary survivor choice or independent-save atomicity is introduced.

Two audit cases raise233→235 JVM tests; three native cases raise216→219 GameTests.
They preserve90 source/10 known staged units with60 claimed and no additional port
calls across root repair, retain view-only grant denial and fresh topology, and
preserve gateway segment/policy/orientation/50 stored/20 claimed/private views.
Restricted-policy preservation is core/codec evidence; native gateway policy is
the existing default. Both holder kinds retain exact state on failed audit.
The root staging setup is low-level native accounting, not new scheduled context
resumption evidence. [Evidence](evidence/canonical-network-node-repair-v1-2026-10-17.md)
archives both exact schema4 snapshots, final log, JVM/GameTest/dedicated reports.

Initial native verification exposed fixture contention on the existing global
repair throttle. Positive cell/bank/buffer/node fixtures now use dedicated batches
with cooldown; node cases retry the public command until admitted. Every amount,
permission assertion, required test and production limit remains intact. A focused
rerun passed219 native cases; final canonical verification includes all isolation
and fixture changes. `./gradlew spotlessApply compileJava` and focused
`./gradlew spotlessApply test runGameTestServer` were exercised. Final
`python3 scripts/verify.py` passed24 tooling,235 executed JVM,219 required GameTests
and dedicated seed41080/restore41098. Gradle1m1s,31 tasks:13 executed/18 up-to-date;
Rust checks up-to-date. Formatting/PMD/architecture/packaging passed, final log has
no ERROR, and final documentation/checksums/`git diff --check` passed.

Next: connector/Machine Interface holders, conflicts combined with accepted known
staging and interrupted independent-save reconciliation. Selected tanks/coordinated
ports and remaining P1/P2 work precede crafting/production scripting, powered and
wireless progression, finished presentation/onboarding, multiplayer and P7. Full
D03/A13 and A01–A17 remain incomplete; full goal stays active.

## Audited connector/Machine Interface repair checkpoint — 2026-10-17

P2/P3/R09/A05/A12/A13 adds operator `/factorycore recovery port list [after]`,
`show <id>` and `repair <id> <expected-generation>` for connectors and Machine
Interfaces. List visits at most16 structural handles; show adds the accepted machine
deployment and canonical fence without provider calls or chunk loading.

The physical adapter requires the loaded original holder with exact raw/parsed
reference, no persisted provider fault, no unresolved record naming the node, and
for machines a positive canonical fence equal to physical configuration. A matching
remembered registration (owner/configuration/token/kind/face) blocks on unresolved
records for its backing; without one, any legacy record lacking context blocks,
checked by a read-only bounded1024-record scan. Audit schema5 precedes the lease
transition. Settings/label/face/configuration, foreign contents/identity/holds and
accepted presets/fences stay unchanged; old registrations/ports/cache/topology are
invalidated and a new provider token issued. Machines resume only after explicit
authorized native redeployment. World12/references/audit1–4 remain unchanged.

One audit class and one registry case raise235→238 JVM tests; five native cases
raise219→224 GameTests. [Evidence](evidence/canonical-port-repair-v1-2026-10-17.md)
archives both schema5 snapshots, reports and logs. Final `python3 scripts/verify.py`
passed (24 tooling,224 GameTests, dedicated seed53367/restore53379); its JVM test
task was build-cache restored, so a forced no-cache rerun executed238 JVM and224
GameTests with zero failures. Final documentation/checksums/`git diff --check` passed.

Next: conflicts combined with unresolved/unscoped staging (known-staging
reconciliation), missing/stale holders and interrupted independent-save
reconciliation. Selected tanks/coordinated ports and remaining P1/P2 work precede
crafting/production scripting, powered and wireless progression, finished
presentation/onboarding, multiplayer and P7. Full D03/A13 and A01–A17 remain
incomplete; full goal stays active.

## Fuel Generator checkpoint — 2026-10-17

R02/R11/D06 adds the standalone [Fuel Generator](decisions/0027-fuel-generator.md)
([module](modules/FUEL_GENERATOR.md)): portable `FuelGenerator` FE/burn accounting
and a ticking platform block with fuel/remainder slots, extract-only FE capability,
bounded adjacent push, player insert/inspect/take, facing/lit stand-in models,
recipe/unlock, exact break/place component state and retained future schemas.
ADR 0006 numbers are unchanged; the recipe (ICI/CFC/IRI) is new.

FuelGeneratorTest adds four JVM cases (238→242) and FuelGeneratorGameTests add
eight native cases (224→232). `./gradlew spotlessApply test runGameTestServer`
passed with all 232 required GameTests; an earlier run caught a test that placed a
bank without a network (banks are correctly offline until validated) and vanilla
block-entity data merging into retained future data, fixed by a dedicated retained
component. Final `python3 scripts/verify.py` passed (tooling, docs, PMD/format/architecture,
242 JVM, 232 GameTests, dedicated staging seed/restore). No screen, client visual check or survival power playtest is claimed.

Next: P4 crafting begins with the terminal manual crafting grid, then patterns,
Crafting Units and recursive jobs. Recovery gaps from earlier checkpoints remain open.

## Terminal manual crafting grid checkpoint — 2026-10-17

P4/R08/A11 adds the session-owned ghost grid ([ADR0028](decisions/0028-terminal-manual-crafting.md),
[terminal module](modules/STORAGE_TERMINAL.md#manual-crafting-grid)): portable
`GridRequirement`, platform `TerminalCrafting`, separate bounded grid packets, menu
gathering through the existing priority withdraw route, and a widened (344-pixel)
native screen with ghost cells, result preview, Row→/Craft/×64/Clear and C/Shift+C.

GridRequirementTest and TerminalCraftingPacketsTest add five JVM cases (242→247);
four TerminalCraftingGameTests add native cases (232→236). Development runs caught
a test helper crash when the terminal opened before network validation, early
assertions against asynchronous gathering, and the shared two-tick throttle dropping
same-tick ghost edits (now a separate 32-per-second edit budget). Final
`python3 scripts/verify.py` passed (247 JVM, 236 GameTests, PMD after removing an
empty catch, dedicated staging seed/restore).

`./gradlew runTerminalClient` passed once on this host with the widened screen
(existing terminal/buffer/machine stages and all prior captures). A new graphical
grid stage (cell click, own-slot ghost, Row→, right-click clear, actual Craft
button, scale 4/2 captures) was then added; its run could not start because no
display was available ("Failed to find a primary monitor"), so the grid's graphical
behavior is compiled but unverified. JEI transfer and patterns remain open.

Next: patterns and Crafting Units (P4), then recursive autocrafting jobs.

## Crafting planner checkpoint — 2026-10-17

P4/R06 adds portable `Pattern` and `CraftingPlanner` ([ADR0029](decisions/0029-crafting-planner.md),
[module](modules/CRAFTING.md)). Eight CraftingPlannerTest cases raise JVM tests
247→255; no native behavior changed. A first byproduct test expectation was wrong
(steps aggregate crafts, so a step cannot reuse its own returned buckets); the test
now documents that conservative rule and checks reuse by a later sibling step.

Next: physical pattern items encoded from the terminal grid and a Crafting Unit
that discovers patterns in its segment, then persisted jobs with reservations.

## Pattern items and Crafting Unit checkpoint — 2026-10-17

P4/R06/R08 adds Blank/Crafting Pattern items, terminal Encode, the Crafting Unit block
(CRAFTER topology role, nine-pattern handler, retained unsupported data) and the
bounded per-query `WorldCrafting` index ([ADR0030](decisions/0030-patterns-and-crafting-units.md)).
PatternContentsTest adds three JVM cases (255→258); CraftingUnitGameTests add four
native cases (236→240). Development runs exposed a wrong test assumption about which
duplicate wins (now asserted from stable position order) and a timing-dependent
follow-up craft in an earlier grid test that could land inside the menu's two-tick
throttle (the test now idles; the throttle is unchanged).

Next: persisted crafting jobs with single reservations, Crafting Unit execution,
cancellation/cleanup and a terminal request preview.

## Crafting jobs checkpoint — 2026-10-17

P4/R06/D03 adds persisted crafting jobs ([ADR0031](decisions/0031-crafting-jobs.md)):
core `CraftingJob`/`CraftingJobs`, a lazy stock lookup for `CraftingPlanner`, world
schema 13 with nested `CraftingJobCodec` and a load-time ledger cross-check, a
trusted `DeviceRegistry.reservation` inspection, `WorldStorageTransfers.digitalAllowed`,
and the `WorldCraftingJobs` runtime on the shared scheduler. Seven new JVM cases
(258→265) and six native cases (240→246). Eighteen existing persistence tests were
updated for the new current schema (requirement change, not weakened checks).

Limits: digital cells only, crafting-table patterns only, own segment only, one
craft per scheduler task, no request UI yet. Next: terminal Jobs view (preview,
start, progress, cancel), then processing patterns/Crafting Interface and published
branch jobs.

## Terminal jobs view checkpoint — 2026-10-17

P4/R08 adds `TerminalJobPackets` and a terminal Jobs mode (craftable rows, ×1/×8/×64
preview, Start, a four-job progress column with text state glyphs, Cancel).
TerminalJobPacketsTest adds two JVM cases and TerminalJobGameTests one native
end-to-end case (preview/stale start/start/finish/missing/cancel). The cancel step was
first written against a 3-craft job that could finish before Cancel; it now uses a
24-craft job. Graphical verification is pending a display.

Next: processing patterns with a Crafting Interface bound to machine ports (port
leases, clean outputs, return attribution), then gateway-published branch jobs and
keep-stock requests.

## Processing patterns checkpoint — 2026-10-17

P4/R06 adds processing patterns (`ProcessingContents`), the Crafting Interface
(`PatternHolderEntity` base shared with Crafting Units), grid processing mode with
counts and output cells, nested job schema 2 with persisted dispatches, and dispatch/
collect/cleanup execution ([ADR0032](decisions/0032-processing-patterns.md)). JVM tests
267→271 after the jobs view (ProcessingContentsTest two cases, one CraftingJobTest and one
CraftingJobPersistenceTest case, plus updated packet bounds); native 247→252. A shared-
world isolation bug in three earlier job tests (asserting every job in the world was
gone) surfaced once concurrent processing jobs existed; they now assert per principal.

Next: published branch jobs through gateways (A07) and keep-stock requests, then P5.

## Branch publication checkpoint — 2026-10-17

P4/R04/R06/A07 adds derived recipe publication through gateways
([ADR0033](decisions/0033-branch-recipe-publication.md)), `WorldNetworks.ancestors`,
`Offer.published` and trusted `NodeRegistry.setPolicy`. One JVM case (271→272) and two
native cases (252→254); one earlier native assertion was updated because default
gateways now publish upward. Next: keep-stock crafting requests, then P5 programming.

## Keep-stock checkpoint — 2026-10-17

P4/R06 adds keep-stock rules ([ADR0034](decisions/0034-keep-stock-requests.md)):
`KeepStockRule`, registry bounds in `CraftingJobs`, nested job schema 3 (`Rules`, job
`Rule`), scheduler-driven evaluation with one job per rule, and a terminal Keep toggle.
JVM 272→275 and native 254→256 (plus Keep steps in the terminal jobs test).

P4 now has manual crafting, crafting-table and processing patterns, recursive jobs
with single reservations, cancellation/cleanup, branch publication and keep-stock,
all with headless evidence. Remaining P4 gaps: fluid processing, JEI transfer,
graphical/multiplayer verification. Next: P5 programming (D05 grammar, Program
Controller, production Wasm host API).

## Friendly language checkpoint — 2026-10-17

P5/D05 freezes the friendly language ([ADR0035](decisions/0035-friendly-language.md),
[module](modules/PROGRAMS.md)) and adds portable `FriendlyCompiler`, `ProgramIr`,
`ProgramEvaluator` and `ProgramCommand`. Seven JVM cases (275→282). Two first-draft
test expectations were wrong (a command count and a recovery case whose junk words
are correctly skipped as one error) and were corrected.

Next: Program Controller block with draft/deployed source, persisted deployments,
scheduled invocation through the shared scheduler and command execution through
existing routes, presets-style stock and crafting jobs.

## Program Controller checkpoint — 2026-10-17

P5/R07 adds the Program Controller block and `WorldPrograms` runtime
([ADR0036](decisions/0036-program-controller-runtime.md)): DEPLOY-gated lifecycle,
scheduler-driven rule invocation with FE costs, traps, coalesced bounded command
queues executed through existing routes/connectors/crafting jobs, per-outcome logs and
block-entity persistence. `ProgramEvaluator.evaluate` now reports steps. Native tests
256→261. Next: the editor screen (draft editing with inline diagnostics, deploy/pause/
stop, logs, templates), then production Wasm with a Rust SDK.

## Program editor checkpoint — 2026-10-17

P5/R08 adds `ProgramMenu`, `ProgramPackets` (chunked drafts), `ProgramScreen` and
`ProgramTemplates`. JVM 282→285, native 261→262. Next: production Wasm programs with
a Rust SDK on the same command set (D04 production, A09 parity, A10 bounds).

## Production Wasm checkpoint — 2026-10-17

P5/D04/R07 promotes Chicory to an embedded dependency (jarJar, lockfile), adds
`WasmPreflight`, `WasmProgram` and the `factory_v1` ABI ([ADR0037](decisions/0037-wasm-factory-v1.md)),
and the `sdk/rust` workspace (SDK crate + smelter example) verified by
`verify_guest.py`. JVM 285→289; tooling 24→25. Next: deploy uploaded Wasm artifacts on
Program Controllers (chunked upload, persistence, FE per instruction).

## Wasm deployment checkpoint — 2026-10-17

P5 adds Wasm deployments to Program Controllers (artifact chunks, `deployWasm`,
block-entity schema 2, fresh instances on load/resume, per-instruction FE) and an
Upload Wasm editor button. Native 262→263. P5 now covers both authoring paths with
shared commands; remaining P5 gaps: persistent guest state, graphical editor checks,
JEI insertion into the editor (P6). Next: P6 — terminal fluid and energy views.

## Terminal fluids and energy checkpoint — 2026-10-18

P6/R08 adds the terminal kind toggle and `TerminalContainers` fill/empty/charge/
discharge ([ADR0038](decisions/0038-terminal-fluids-energy.md)). Native 263→264.
Next: wireless access point and rechargeable handheld terminal (A16).

## Wireless access checkpoint — 2026-10-18

P6 adds the Wireless Access Point and Wireless Terminal ([ADR0039](decisions/0039-wireless-access.md)),
reusing `TerminalMenu` through `TerminalAccess`. Native 264→265. Next: paired wireless links.

## Paired wireless links checkpoint — 2026-10-18

P6 adds Wireless Link Endpoints as reciprocal topology edges between gateways
([ADR0040](decisions/0040-paired-wireless-links.md)). Native 265→266. Next: JEI integration
(optional, isolated) or gateway policy editor.

## Gateway policy editor checkpoint — 2026-10-18

P6 adds the sneak-use gateway policy editor ([ADR0041](decisions/0041-gateway-policy-editor.md)).
Native 266→267. Next: in-game guide/onboarding, then JEI integration.

## In-game guide checkpoint — 2026-10-18

P6 adds the Factory Guide and the first-login gift ([ADR0042](decisions/0042-in-game-guide.md)).
JVM tests +2, native 267→268. A17 still needs a survival playtest. Next: JEI integration
(optional, isolated).

## JEI integration checkpoint — 2026-10-18

P6 adds optional compile-only JEI (recipe transfer into the terminal grid, editor
ingredient drops; [ADR0043](decisions/0043-jei-integration.md)). JVM +2 classes, native
268→269. JEI client callbacks are unverified (no graphical client). Remaining P6:
finished assets, recipes/balance (D06); then P5 persistent guest state, P4 fluid
processing, recovery gaps and P7 verification.

## Persistent guest state checkpoint — 2026-10-18

P5 adds quota-limited, callback-transactional persistent Wasm state with SDK support,
a counter example and controller schema 3 ([ADR0044](decisions/0044-persistent-guest-state.md)).
JVM +2, native 269→270. Next: P4 fluid processing.

## Fluid processing checkpoint — 2026-10-18

P4 adds fluid inputs and outputs to processing patterns, Crafting Interface fluid ports,
and fluid crafting requests ([ADR0045](decisions/0045-fluid-processing.md)). JVM +2, native
270→273. Remaining P4 gap: graphical checks. Next: recovery gaps (unresolved or unscoped
staging, independent-save interruption).

## Independent-save adoption checkpoint — 2026-10-18

P7/A13 adds `DeviceRegistry.adopt`, `factorycore recovery cell adopt`, cell audit schema 3
and a required two-JVM independent-save injection ([ADR0046](decisions/0046-independent-save-adoption.md),
[evidence](evidence/save-order-v1-2026-10-18.md)). JVM +3, native 273→274. Open:
banks/buffers/nodes, the converse order across JVMs, unresolved/unscoped legacy staging.
