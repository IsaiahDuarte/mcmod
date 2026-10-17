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

## Ledger component recovery

Four TransferRecoveryTest cases exercise immutable transfer snapshots and
interrupted extraction/delivery/return using fake handlers that mutate before
capturing the pending intent. They require quarantine without replay/refund,
known-partial conservation, current authority after restore, strict backing
rebinding, completed-record inertness, journal failure before mutation and
malformed/overflow/bounded-diagnostic behavior. Canonical world staging saves and
real interrupted-save/connector evidence remain pending.

The initial persistence suite uses a committed version-one hex fixture from
test resources, integrity/schema failures and 2,000 generated save/reload
operations (seed 0x5A7E001). It exercises exact stock and reservation recovery
offline. See [component contract](modules/PERSISTENCE.md); world interrupted
save/move/break/clone and job/staging recovery tests remain mandatory P2/P4 work.

Ownership/adapter tests include a committed registry SNBT fixture, seven
DeviceRegistryTest cases (3,000 generated accounting operations, seed 0xD031001)
and four DeviceSavedDataTest cases with actual compressed disk save/reload.
They cover consumed/stale/foreign leases, conflicting copies, admission and
offline claims, and preventing replacement after a swallowed corrupt-file read.
Block placement/unload, multiplayer authority and full staging/job saves remain
separate checks; see [ownership contract](modules/DEVICE_OWNERSHIP.md).

## Portable core benchmark

`./gradlew coreBenchmark` runs the version-one small/large/overload/idle harness
using a separate 2 GiB JVM. It is a manual evidence command, not a CI latency
assertion. See [scheduler contract](modules/SCHEDULING.md),
[target ADR](decisions/0003-scheduling-workloads.md), and
[initial measured evidence](evidence/core-v1-2026-10-03.md). Guest/runtime, live
world and client measurements remain required in their owning stages.

## Required physical storage GameTests

Gradle `check`/`build` now depends on `runGameTestServer`; the canonical verifier
and existing Linux/Windows workflow therefore require the headless world tests.
`./gradlew runGameTestServer` is the focused command. Separate `gameTest` sources
load a test-only mod alongside Factory Core. Client/server/JVM runs explicitly
load only the main mod, and the packaged-JAR test rejects test classes/resources.
Formatting, compiler warnings and PMD also cover this new source set; dependency
locks include its configurations.

The server uses `build/gametest-run`; each run resets only its generated `world`
folder and clears the prior XML report. The original empty structure fixture is
scaffolding for real block/action assertions. All registered tests must complete;
zero collection, a missing/stale report, failures/errors/skips or nonzero server
exit fail the build. Reports are in `build/reports/gametest/results.xml`.

Ten StorageDriveGameTests exercise placement/removal/break drops, four-slot/full
and malformed rejection, copied/stale/foreign leases, module consumption and
component/claim preservation, finite fluid capacity, block-entity lifecycle
reload, unknown drive schema recovery, the version-one lease fixture and exact
vanilla recipe costs/advancement loading. Ownership fixtures now construct valid physical roots and dispatch bounded
coordinator ticks for stock seeding; separate production post-tick tests are below.
See [physical contract](modules/STORAGE_CELLS.md) for their limits. Actual chunk
unload/world restart, abrupt independent save boundaries, client visuals,
multiplayer and survival progression remain required later evidence.

## Portable topology and authority

Seven NetworkTopologyTest and five NetworkAuthorityTest cases cover invalid
graphs, loaded membership, scopes/policies, current grants and bounded query/
validation work. A 4,096-node/3,072-gateway fanout advances one visit per call;
300 generated gateway unload/recovery sequences use seed 0x70F0106. A real
FairScheduler queue verifies revocation and topology changes before mutation.
These run in the required JVM suite. They do not replace physical port/chunk,
structural lease persistence, multiplayer or live routing evidence; see
[network contract](modules/NETWORK_AUTHORITY.md).

## Canonical network persistence

Five NetworkPersistenceTest cases cover fixed permission-bit/schema-two fixtures,
real compressed disk owner transfer/revocation, malformed/colliding/unknown
metadata preservation, global binding admission/reclamation and network/principal
ceilings. DeviceSavedDataTest preserves its schema-one fixture as an exact
migration check, including stock/claims/lease generations.
NetworkPermissionBudgetTest verifies admission and overflow before mutation or
accounting callbacks. These are required JVM tests; physical structural leases,
actual world restart/chunks and multiplayer remain separate evidence.

## Physical structural ownership

Four NodeRegistryTest and four NodePersistenceTest cases cover structural leases,
copy quarantine, policy/schema fixtures, compressed disk movement, overflow,
duplicate positions and admission without orphan grants. Four required
NetworkNodeGameTests bring the headless suite to fourteen: actual creative
placement/movement, gateway faces, clone/lifecycle behavior, future-data recovery,
portable chunk rejection, exact recipes and explicit controller commissioning.
See [structural contract](modules/STRUCTURAL_NODES.md). Discovery/activation now
have the additional evidence below; real chunks/restart and client/multiplayer remain pending.

## Budgeted wire discovery

Six WiredDiscoveryTest cases cover reciprocal ports, gateway boundaries/unload,
bypass/collision, stale/cancelled jobs, 4,096-node one-visit progress, ceilings and
shared scheduling. Two required WireDiscoveryGameTests bring the world suite to
sixteen; actual placements produce private scopes, detached drives remain rootless
and remote chunk probes never load chunks. These tests explicitly own cancellation
and scheduling. Automatic region/event hooks and activation now have the live
coordination evidence below; see [discovery contract](modules/WIRE_DISCOVERY.md).

## Live wired coordination

Five WorldNetworksGameTests bring the required headless suite to twenty-one. They use
production block/entity notifications and actual server post-ticks to prove cell
activation, immediate disconnection denial, detached/reconnected exact claims,
coalesced notifications, multiple roots/independent regions, private storage views,
queued revocation and stock denial on remote structural clone conflicts. Existing
ownership assertions remain; their stock fixtures now use valid physical roots.
A typed chunk-load adapter regression verifies adjacent-index invalidation; its
fixture stays loaded, so actual unload/reload is still unverified. Core tests
cover current routing/queued writes, offline cleanup, capture listener
failure and full-size repeated-seed rediscovery. See [runtime contract](modules/WORLD_NETWORKS.md).
Actual chunk unload/reload, full restart, interrupted saves, client/multiplayer and
representative tick/memory measurements remain required future evidence.

## Energy-bank accounting prerequisite

Six EnergyBankAccessTest cases verify combined calls/simulation, independent
input/output ceilings, capacity/claim preservation, same-tick movement/upgrades,
stale/offline access, clock reversal and canonical snapshot restoration. A 4,000-step
conservation/rate sequence records seed 0xE0B001. They run in the required JVM suite.
See [contract](modules/ENERGY_BANKS.md). Physical integration evidence is below;
actual chunk/full-world restart and client tests remain required.

Four EnergyBankOwnershipTest cases extend the bank prerequisite with lease
movement, populated sequential upgrades retaining claims, same-tick shared rates,
copied placed-reference quarantine, occupied/wrong-tier/foreign rejection and
offline snapshot reload. They run in the required JVM suite.

Seven EnergyBankGameTests exercise actual placement/break, all-face capabilities,
combined rates/simulation/claims, immediate disconnect/reconnection, populated
module upgrades/components, lifecycle reload/copy quarantine, malformed/future
preservation and vanilla recipe costs/unlocks. Two EnergyBankHandleDataTest cases
require a committed schema-one fixture and reject malformed/future fields.
The physical movement/upgrade case also verifies the old capability cannot spend
the replacement bank and preserved claims retain their original owner.
These extend the required headless suite to 28 GameTests. Actual chunks, full-world
restart, interruptions, multiplayer and client checks remain required evidence.

DeviceSavedDataTest, NetworkPersistenceTest and NodePersistenceTest drain
NeoForge's I/O worker in `@AfterEach` before JUnit removes temporary directories.
Reads schedule cleanup as well as saves; the earlier read/teardown race produced
NoSuchFileException failures in three tests. The drain retains failure propagation
and does not skip or weaken assertions.

## Presentation and onboarding verification

R08/A11 require real client checks against [ASSETS.md](ASSETS.md), including
block/item appearance, cable shapes, gateway orientations, applicable status
feedback, translatable messages, keyboard actions and documented GUI scales.
Record environment, actions, observed results and evidence paths in the plan.
Resource loading and headless GameTests do not prove visual or interaction quality.
A17 requires following discoverable in-game help through the survival progression;
A13 requires exercising supported administrative recovery and unresolved quarantine.
These checks remain pending until the owning features and evidence exist.

## Canonical staging record verification

Four TransferRegistryTest cases cover bounded admission before extraction,
single runtime binding, unload/rebind, known-partial conservation, current
authority, uncertain-intent retention, retirement/reclaimed admission, duplicate
and byte/count limits, owner thread and reentrant notification denial. Three
TransferPersistenceTest cases exercise real compressed schema-four SavedData
source/destination/staging recovery, the committed registry-v4 SNBT intent fixture,
and full-save preservation for malformed/future/duplicate/overflowed records.
Existing schema-one/two/three fixtures now require exact owned-data migration and
empty transfer tables; schema five is the unknown-version rejection case. These
replace schema-three target expectations because canonical schema four adds owned
transfer records; no old fixture or assertion about conserved data was removed.
Actual world endpoints, full restart/chunks and interrupted independent saves remain
required. See [transfer persistence contract](modules/TRANSFER_PERSISTENCE.md).


## Scheduled world storage transfers

`InsertSimulationTest`, the registry simulation regression and targeted scheduler
cancellation test cover nonmutating admission, exact partial capacity, catalog,
offline/routing, Long.MAX_VALUE limits and conserved claims/dirty state.
Seven `WorldStorageTransfersGameTests` exercise the real shared scheduler and
physical holders: unreserved partial moves, revocation, disconnection, removed
cell, private-child denial, cancellation/reclaimed queue admission and partial
bank FE transfer. These are component world tests; external inventories,
actual chunks/restart/interrupted saves, client and multiplayer remain required.
See [API and lifecycle limits](modules/WORLD_STORAGE_TRANSFERS.md).


## Sided external connectors

Three ConnectorDataTest cases freeze local schema-one SNBT/field types and reject
invalid/future policy/reference data. NodePersistenceTest adds compressed connector
lease movement/reload without network-grant invention. The sparse item-adapter
regression uses the final valid int slot without allocating/scanning a giant
inventory and rejects Integer.MIN_VALUE slot-count arithmetic. TransferRegistryTest
now also exercises backing invalidation during extraction/delivery, preserves
known staging, releases live references, rebinds and isolates unrelated records.
Legacy pending schema-four data retains its OFFLINE-until-bind behavior and exact
fields; quarantine never replays or refunds after unbinding.

Nineteen ConnectorGameTests add real vanilla/NeoForge capability hosts: partial
component-aware item import/export and reservations, mB/FE, sided availability,
queued revocation, private child denial, same-provider/double-chest overlap,
policy/rate/backing-description changes, rotation/revalidation, physical controls,
movement/stale copies, future evidence, opaque providers and recursive/faulting
post-mutation calls. Test-only provider blocks remain outside the packaged mod.
These component checks do not establish real chunks/full restart, endpoint context
recovery, multiplayer, client scales/rendering, third-party identity adapters or
performance. See [contract](modules/CONNECTORS.md).


## Provider identity persistence

ExternalProviderIdentityTest validates strict world/version/field semantics and
preserves rejected markers; group identity ignores order, separates kinds/worlds,
and rejects duplicate/oversized components. Three ConnectorGameTests require real
compressed chest NBT identity/content round trips, replacement/lifetime separation,
future-marker preservation and copied identity rejection at distinct coordinates.
The existing group-change budget case now supplies real persistent block entities
for both declared parts rather than treating air as an identity-bearing component.
This replaces its fixture setup to satisfy the new persistent-provider contract;
its original rate/no-mutation assertions remain unchanged. Full chunks/server
restart, operation context/recovery and cross-file interruptions are still pending.


## Accepted transfer-context schema and scope

Four TransferContextTest cases cover the fixed transfer-context-v1 SNBT fixture,
strict type/schema/privilege fields, canonical pre-mutation endpoint matching,
immutable restore, compressed context/staging/grant recovery and full invalid or
foreign-context preservation. Schema-one/two/three migration tests now target
world schema five; their owned-data assertions remain unchanged. Schema-four's
pending fixture migrates to entry schema two without Context and is compared with
its original bytes after removing only declared version changes; counts/intents
and OFFLINE-until-bind semantics are preserved. Unknown schema six replaces five
as the future-world test because five is now the documented supported format.

WorldStorageTransfersGameTests verify context attachment and queue rejection
across replacement-root network/scope changes, preserving both inventories and
avoiding phantom records. A metadata budget regression caught an initial fixed
allowance that made 1,024 records unreachable. Corrected code charges context
separately, keeping the existing count/byte tests and limits unchanged. These are
component/disk-adapter evidence; recovery execution, real world restart/chunks,
client/multiplayer and independent-save interruptions remain required.


## Explicit global priority routing

Three PriorityOrderTest cases cover ordering across discovery-page arrival,
duplicate backing updates, extreme integer priorities, stable ties and bounded
admission/reclamation. Eight ConnectorGameTests exercise preferred foreign import,
preferred partial withdrawal, full-target fallback, owned cancellation, queued
revocation, uncertain extraction without fallback, stable repeated rotation and full-target
rotation fallback, with exact conservation.
An isolated pinned-runtime run executed all 80 world tests successfully; see the
implementation-plan checkpoint for commands, report paths and canonical-build
limitations. Cancellation/revocation and uncertain extraction now have world coverage. Real
client/full-restart and representative world-performance evidence remain required.


Claimed-uncertainty cleanup waits for quarantine-triggered origin topology
revalidation and asserts cleanup admission before expecting a completion.
Rejected OFFLINE work has no callback; ignoring that result previously produced
an intermittent fixture timeout. Journal/hold/conservation assertions remain.


## Owned buffered inventory prerequisite

Five ItemBufferTest cases cover nine-slot/component/stack bounds, simulation and
immutable reload, exact partial staging cleanup blocked by a full return buffer,
notification reentry and a 5,000-step conservation sequence (seed `0xB0FFE901`).
Two BufferedItemHandlerTest cases use real Minecraft stacks and IItemHandler calls
for partial admission, components, detached reads, shared transfer/slot quantities,
metadata limits and stale capability denial. These run in the mandatory JVM suite.
Physical hopper flow, canonical ownership/persistence, restart/clone/chunks and
client onboarding remain separate required evidence.


## Canonical buffered inventory ownership

Four BufferRegistryTest cases cover loaded-owner binding, exact preserved movement,
copy quarantine, unbound restore, shared metadata preflight/reclamation and strict
owner/record count admission. Five BufferPersistenceTest cases require fixed
sparse slot/schema-seven world fixtures, exact migration, compressed real disk
reload, retained generations/contents, malformed/future/duplicate/missing owner
preservation, byte/count limits and no orphan lease after failed placement.
Existing legacy migration tests now target eight and assert empty buffers; future
world tests use nine. Their conserved quantities and full raw-table equality
assertions remain. These are mandatory JVM checks; physical hopper/block/chunk,
restart/interruption and client evidence remain separate release requirements.


## Physical Buffered Interface

Two BufferDataTest cases freeze the six-field schema-one reference fixture and
reject malformed/future/nonpositive metadata without changing recovery tags.
Seven required BufferedInterfaceGameTests cover actual vanilla hopper insertion,
full slots/backpressure/resumption, shared six-face inventory, wired discovery,
creative reference consumption/break/movement, stale capabilities, lifecycle
reload/copied-reference isolation, raw future data, declared foreign alias rejection
and exact starter recipe/unlock. Physical buffers are outside shared stock catalog.
Network import/export, actual chunks/full restart/interruption, multiplayer and
client appearance/interaction evidence remain required future work.


## Explicit buffered world transfers

Nine BufferTransferGameTests cover partial buffer-to-cell conservation/context,
full-target preflight, queued grant revocation, old configuration after valid
scope rebuild, per-identity shared rates with independent input/output, explicit saved-staging delivery without
re-extracting refilled items, operator/private-child denial and global priority
routing from a fixed buffer. The rate case advances at most sixteen shared queue
steps in one fixed Minecraft tick to isolate rate accounting from wall-clock
scheduler cutoffs; production uses normal post-ticks. Three
BufferEndpointPersistenceTest cases require fixed context-two/world-schema-eight
fixtures, exact migration, invalid kind/configuration/union rejection and actual
compressed owned slot/staging reload. Legacy context-one fixtures/fields and old
quantity/raw-table assertions remain; world migration now targets nine, future ten.
Automatic import/deployment authority, actual restart/chunks/interruption, client,
multiplayer and release-performance evidence remain separate obligations.


Buffer deployment authority adds two BufferRegistryTest cases for single-record
replacement, monotonic revision, overflow/no mutation, offline restore and movement
without renewed authority. BufferPersistenceTest fixes buffers-v2 nested output,
compressed deployment/slot reload and nine malformed/future/unowned authority
variants. BufferTransferGameTests adds authenticated EDIT/DEPLOY/resource grant
checks, provenance/operator rejection, revocation and canonical replacement.
These tests prove configuration intent only; background import is still missing.


Twelve BufferImportGameTests exercise automatic real hopper-to-cell component
movement; full-cell/buffer/hopper backpressure/resumption; queued selective DEPLOY
revocation, pause/resume and disconnect/reconnect; known staging/no re-extraction;
uncertainty/non-replay even for explicit cleanup; old local reference rejection;
empty sleep/actual wake/no simulation wake; native player controls; queued break/
movement/reconfiguration; and original-owner redstone cleanup after pause/revocation.
Queue-order fixtures visit at most 32 eight-entry metadata batches within one
server tick to admit preparation before the next task for that principal; production
bounds and FIFO semantics are unchanged. TransferRegistryTest additionally checks
multiple known owners across restore/delivery/cancellation with exact conservation
and unresolved-backing visibility, plus unbind/uncertainty index retention.


Six CatalogPageTest cases cover exact identity/claims and stable complete paging,
maximal metadata/deleted cursors, per-backing held-once aggregation and totals
beyond signed long, no-mutation quota/overflow rejection, registry offline/stale
leases, and 5,000 generated mutation/restore operations (seed 0xCA7A106).
Eleven StorageSearchGameTests exercise actual digital/32-slot provider pages with
foreign/digital holds, exact component variants/filtering, owned-buffer exclusion,
private-provider no-probe behavior, queued revocation, ready-session actor/grant/
topology invalidation, typed fault without partial rows, bounded count/close/expiry,
real fluid/energy units/observable capacity, inactive-ledger exclusion and shared heavy-metadata admission.
No actual terminal packet/client/full-server restart evidence is claimed.

`./gradlew testClasses` followed by
`python3 scripts/benchmark_ledger_catalog.py --output docs/evidence/catalog-pages-v1-2026-10-12.txt`
compares the old production HashMap ledger from d36268b with the current derived
key index in separate JVMs. It measures existing-key mutation, type churn, full
copy/sort first-page discovery and new bounded pages, including shared-prefix
4-KiB metadata. The runner uses temporary old classes without editing the worktree;
it requires the baseline commit locally (a shallow clone must obtain that history).
These standalone measurements are not full server/terminal/network throughput.


PlayerEndpointPersistenceTest adds two cases freezing transfer-player-context-v3,
principal/derived backing/nonce/selected slot, old union bytes, bad actor/kind/
whole-source/version rejection, exact nonempty schema-nine migration to ten and
compressed owned staging reload. Migration/future envelope tests advance to ten/
eleven while their exact prior-table comparisons remain intact. Eleven
PlayerInventoryGameTests use the real pinned PlayerList mock-login helper for
native deposits, full/partial main inventory, equipment/cursor exclusion, stale
keys/other actors, deposit-only permissions, shared 128-item directions, queued
revocation/range, blocked owned cleanup after revocation, catalog exclusion,
logout and pending non-replay/fresh-extraction blocking. Same-tick rate fixtures
advance at most sixteen queue cycles without changing production scheduling.
The helper is deprecated in this pinned version; its narrowly annotated use is
intentional real PlayerList/connection registration, not a fake authentication bypass.
Actual terminal/client/multiplayer/full restart/independent files remain pending.


TerminalPacketsTest covers count-independent/detached SHA-256 exact identity,
actual bounded registry-aware command/view round trips, beyond-long decimal counts
and malformed/oversized inbound rejection. ArchitectureTest now checks common/server
classes do not depend on client classes. Eight TerminalGameTests use real PlayerList
bodies plus NeoForge's test-only mock channel setup to exercise physical opening,
exact deposit/withdraw, stale commands/source, full inventory, queued close/replacement,
range/revocation, real hopper/buffer/cell/search/menu/player flow and affordable recipe/
unlock. This headless setup does not simulate a real client handshake or prove
rendering, GUI-scale interaction, multiplayer or independent-save restart safety.
See [terminal contract](modules/STORAGE_TERMINAL.md).


## Isolated graphical terminal fixture

Run `./gradlew runTerminalClient` on a desktop. The clientTest source set compiles
and receives PMD/format checks in the canonical build, but this graphical run is
explicit and is not counted as passed on a headless CI host. It creates/replaces
only its generated world under build/client-terminal-run and writes a required
PASS result plus four screenshots under build/reports/client-terminal. Ordinary
run/client and run/server worlds and production packaging remain separate.
See [actual evidence and limits](evidence/terminal-client-v1-2026-10-13.md).

The fixture drives real integrated-player authentication, physical use, native
screen character/Enter/row-key/button mouse handlers and production payloads.
It proves search/Take1/paging, exact C2S deposit conservation, GUI-scale2/3/4 layout,
search retention on reinitialization and normal save/logout. Physical Shift-click,
hover/focus completeness, remote multiplayer/TCP, restart, survival and the rest
of the terminal/release remain separate checks. ModMetadataTest additionally
requires the packaged JAR to exclude clienttesting classes. A ninth
TerminalGameTest captures actual outgoing close packets during native initMenu;
it prevents an old-menu close from immediately dismissing the new client screen.


ResourceFilterTest adds four behavioral cases for exact versus registry identity,
include/exclude and live membership, immutable bounded definitions, resolver errors
and5000 generated evaluations (seed0xF117E221). StockTargetTest adds three cases
for physical resident/known inbound, unit/quanta/long overflow and10000 generated
plans against BigInteger (seed0x570C221). Three ResourceFilterGameTests check actual
loaded item/fluid tags and off-thread refusal. They do not prove deployed presets,
canonical configuration recovery, contention or client controls. See
[contracts and integration obligations](modules/RESOURCE_FILTERS.md).


BufferPolicyCodecTest freezes policy-v1 SNBT/strict fields and compressed buffers-v3
round trips while requiring byte-identical legacy buffers-v2 defaults. BufferRegistryTest
adds combined metadata admission, pause/restore/reclamation and rejection without
changed authority/revision/dirty state. BufferPersistenceTest adds actual compressed
world-ten owned-slot/policy reload and full malformed/future policy preservation;
its future nested fixture advances3→4 because3 is now supported. Seven native
BufferPolicyGameTests cover filtered variants/backpressure, queued replacement/
resource revocation, configured same-tick rates, authenticated physical controls,
actual tag rebinding wake and retained staged cleanup under a reduced rate. The tag
fixture uses the public registry rebinding API and a test-only item, preserving
all vanilla memberships; no production class or tag is packaged from gameTest.
See [policy contract](modules/BUFFERED_INTERFACES.md). Actual client/restart/
independent-file/multiplayer/performance and export/stock remain separate checks.


Seven BufferExportGameTests require actual scheduled buffer/hopper export and
conservation, Stock stop/replenish/full-vacancy, live physical deficit caps, known
inbound/no-refill re-extraction, pause/zero and competing goals with reserved
supply. Native compass/chest control assertions retain filter/rate/goal. Policy
codec and canonical persistence tests add strict table-v4/policy-v2 compressed
reload, old unions/bytes and future raw preservation. The new future buffer version
is5; no prior stock/raw assertion was weakened. GUI/editor, external-provider mode
recovery, actual chunks/restart/independent saves, multiplayer and performance
are separate required evidence. See [mode contract](modules/BUFFERED_INTERFACES.md).


## Buffer settings client fixture and partial results

`./gradlew runTerminalClient` now includes the Settings/Inventory extension and
requires13 terminal/buffer captures and a PASS result. Its isolated world/test-only
source set/package exclusions are unchanged. New steps exercise native draft,
registry/tag, exact sample and inventory controls plus Stock behavior and exact
conservation; physical Shift remains unverified. See the
[blocked launch record](evidence/buffer-client-attempt-2026-10-13.md): compilation
passed, but the new UI steps have not run because macOS reports no active display.

Five BufferSettingsGameTests now cover canonical drafts, invalidation/authentication,
non-consuming exact samples, round-trip conservation and explicit partial/full
move counts under rate/goal limits. Three packet tests round-trip the new bounded
operation projection. All are part of canonical verification; neither they nor
client compilation replace the graphical check.


## Local machine binding checks

Three MachinePersistenceTest cases cover schema11 compressed machine lease/policy
round trips, generation movement, schema10 rejection of the new kind, future raw
preservation and strict labels/legacy connector encoding. Existing migration tests
now expect schema11 output; future-world fixtures advance11 to12 because11 is
implemented. Original resource, raw-preservation and compatibility assertions stay.
Six MachineInterfaceGameTests cover exact selected-slot scheduled conservation,
catalog exclusion, reused labels across a gateway, case sensitivity, direct local
authority, queued relabel/unauthorized edits, sided fluid/FE counts, authenticated
non-consuming paper controls and recipe costs. See [contract](modules/MACHINE_INTERFACES.md).
Actual client/restart/multiplayer, selected-tank adapters and processing remain open.


Four MachineSettingsGameTests add native open/Cancel/complete atomic Apply,
wrong nonce/stale form, range/closed-session rejection and live EDIT/VIEW revocation.
Two MachineSettingsPacketsTest cases cover bounded real form/view codecs,
malformed resource/window/rate/label fields and enum rejection. No new persistence
schema is involved. The isolated graphical fixture now requires17 captures and
includes actual buffer and machine controls; its
[successful evidence](evidence/settings-client-v1-2026-10-17.md) supersedes the
no-display prerequisite as the latest observed client run. Physical Shift,
complete accessibility, multiplayer/restart and machine presets remain unverified.


## Machine preset deployment authority

Four MachinePresetsTest cases cover immutable authority/freshness, pause/restore,
movement,256-owner/16MiB admission with no prior-state/dirty mutation, reclaim,
future/duplicate leases and revision overflow. Three MachinePresetPersistenceTest
cases exercise a fixed schema1 filter fixture, compressed canonical scope/goal
round trips, empty-table migration and strict malformed/future/raw rejection.
Four MachinePresetAuthorityGameTests exercise actual physical configuration and
old chunk replay, selected-face rotation, grant revocation/regrant/principal replacement and new roots.
They do not run an automatic preset or claim staged-resource recovery evidence.

World migration assertions advance11→12 and strip only the newly added empty
MachinePresets table when comparing unchanged legacy content. Future-world tests
advance12→13. The legacy selected filter/stock/resource assertions remain intact;
new malformed table cases retain whole-world raw preservation. Binding reference
schema2, buffer policy versions and transfer context formats are unchanged.
See [authority contract](modules/MACHINE_INTERFACES.md) for remaining integration.


## Required machine preset runtime checks

Nineteen MachinePresetGameTests exercise actual scheduled item/fluid/FE Import,
Export and Stock with exact conservation, excluded keys, full-target vacancy/pause,
live goal admission for explicit moves, known inbound/no-refill re-extraction,
owned returns, queued edit/revocation, same-principal replacement, unknown-call
freeze, metadata admission and removal/old-chunk recovery. The quota fixture fills
only remaining global capacity and removes its temporary rows; uncertainty checks
are scoped to the fixture backing because required GameTests share one SavedData.
The original limits and expected conservation amounts remain intact.

MachinePresetsTest adds read-only preparation/stale commit and retained canonical
configuration marks. MachinePresetPersistenceTest adds compressed fence recovery,
strict malformed marks and schema1 migration; future nested-table tests now use3
because2 is implemented. World12, reference schema2 and context ABI3 are unchanged.
Actual player preset UI, client dispatch, independent saves/restart, multiplayer,
selected tanks and representative performance remain separate required checks.


## Machine preset editor checks

MachinePresetPacketsTest covers bounded real command/view codecs, detached sample
digests, malformed commands and overlong rule lists. TransferRegistryTest adds
separate device/backing identity and restored/unbound/uncertain index behavior.
MachineSettingsGameTests adds server draft isolation, explicit publication, nonce/
revision/configuration rejection, copied bucket/canonical FE samples and current
DEPLOY revocation. MachinePresetGameTests adds actual authenticated VIEW-only
original-owner staged return through the physical menu and shared scheduler.

`./gradlew runTerminalClient` now requires20 captures and the prior terminal/buffer/
binding checks plus preset Stock authoring, exact sample/goal/Deploy and selected
slot conservation. It does not prove physical Shift, remote multiplayer, restart,
independent saves or complete release acceptance.

## Separate-process orderly world restart

Run `./gradlew runTerminalRestartClient --console=plain` on an active desktop.
The task first runs the full terminal/buffer/machine graphical seed fixture, adds
mixed fluid stock, a principal-owned fluid hold and finite bank energy, captures
expected observations under build/reports/client-terminal/restart.properties,
and exits normally. Only the generated fixture world/report are replaced.
A second Java process opens that existing world with Minecraft's normal open flow;
it does not call fresh-world setup or restore authority from the expectation file.
It rejects the seed PID and requires that process to have exited.

Before changing resources, require equality of the complete canonical SavedData
snapshot, world/principal/root/machine/configuration/provider identities and real
player/chest/buffer stock. Require1200mB water with400 held/800 available,300mB
lava and500 loaded FE. Then remove3 real items from the machine chest into an
empty player slot and require the saved Stock preset to replenish through the
production scheduler with25 total conserved units. Finally reopen the physical
preset screen in the second client’s default window, require every visible control
to fit its actual scaled screen, capture it, write PASS and quit/save normally. Missing result or
capture fails Gradle. All fixture classes remain in the excluded clientTest source
set, which receives canonical compilation/format/static checks.

This experiment covers orderly integrated-server restart on one machine. It does
not replace independent chunk/player save-order injection, uncertain external
write recovery, real remote multiplayer, crash/power-loss, dedicated-server
restart, or separate-world namespace checks. Actual run evidence is recorded in
the implementation plan; source compilation alone is not a passed restart check.

## Native chunk lifecycle extension

The separate-process restart fixture additionally requires a genuine unload/load
cycle in its second JVM. After the initial Stock replenishment it captures all
physical leases, cached buffer/bank/machine capabilities and canonical state,
disables spawn-chunk retention only in the generated fixture world, and teleports
the real player1024 blocks away. It observes native NeoForge ChunkEvent.Unload/
Load events; it never posts a synthetic event or invokes block-entity lifecycle
methods to simulate unload.

Require `getChunkNow(0,0)==null`, removed old holders, no available item/fluid/FE
stock, retained exact quantities/holds, and rejected cached extraction/insertion.
Repeat offline probes for at least40 server ticks and require no chunk reload or
canonical accounting change. Return normally, require fresh physical objects with
the same leases/provider backing/full canonical state, and keep old capabilities
inert. A real one-item removal must then trigger saved Stock through shared
scheduling, conserving25 units. This does not test independent save-order
interruptions, split networks across chunk boundaries, orphan jobs or unknown-call
recovery; those remain separate required scenarios.

## Loaded-child/unloaded-root extension

The restart client also constructs a private fixture network with a root in
chunk111,272 cable blocks, and an east-facing gateway plus child drive/machine in
chunk129. It explicitly retains only the child chunk using vanilla administrative
chunk forcing, then moves the player far away. The eighteen-chunk separation keeps
the root outside the forced child chunk's broader generation-dependency retention
area. The root is explicitly retained during setup, then that separate ticket is
released before departure. Setup/return place the player midway along the path
so ordinary view loading covers both endpoints. Production discovery
and resource probes must never force or reload the root.

Require a native root unload event, removed original root, continuously loaded
child holders, zero storage availability, retained16 gold/four-unit hold, rejected
claimed extraction/cached machine mutation, invalid child execution scope and no
preset replenishment for40 server ticks. A three-item physical chest removal goes
into the player's inventory, so suspended conservation remains24 gold. Return
normally, require the same root lease on a new physical holder, and require saved
child Stock to resume without redeployment: cell13 + machine8 + player3 =24 with
hold4 unchanged. Release the fixture's forced chunk before returning to the main
fixture and opening its native screen. This adds actual split-root lifecycle
coverage; queued/staged unload and independent-save interruption remain separate.


DiscoverySeedsTest now replaces obsolete recursive-depth rejection with512
successive ownership transfers that retain every seed/charged completion visit.
It also checks combined8,192-frame admission preserves both queues without polling
callbacks, and retains prior empty-transition/cycle/sharing/queue-limit coverage.
The flat representation removes nested iterator recursion; it does not raise any
region/discovery tick, probe or vertex limits. The split-root client is the native
regression for the finite cable-placement trigger.

## Required dedicated staged-operation restart

`./gradlew runStagingRecoveryServer --console=plain` is now part of `check` and
therefore the canonical verifier/CI build. It runs two real dedicated-server JVMs
without graphics. Seed setup replaces only build/staging-server-run/world and
build/reports/staging-server, binds an ephemeral loopback port, and uses a fixed
flat world. The second process opens that saved world without reseeding; it
requires the first PID to have exited and compares complete canonical SavedData
before any progress. Normal run/server and player worlds are untouched.

A test-only native ItemHandler provider simulates normal acceptance but actually
accepts only three of eight items. Real shared scheduled submissions debit each
source20→12 and persist known staging5/delivered3. A third provider accepts3 then
throws, leaving QUARANTINED pending DELIVER8 with delivered0. Native inventories,
acceptance budgets/call counters and provider backing markers persist in chunk
NBT. The fixture never calls TransferRegistry.start or installs synthetic staging.

After restart, native provider counts/identities/counters and source debits must
match. Known delivery produces source12 + provider8 =20 without new extraction;
known original-source cleanup produces source17 + provider3 =20 without another
destination call. Both complete records may then retire. Uncertain delivery and
return reject UNCERTAIN, cannot retire, make no extra native calls and preserve
evidence. Its pending quantity is not treated as a proven recoverable balance.

Reports seed.txt/restore.txt must begin PASS; missing/incomplete runs fail Gradle.
This fixture uses a stable trusted synthetic program principal (NeoForge fake
player only for server/registry access), not a logged-in player or player endpoint.
It proves native server transfer/save/rebind behavior, not login/session/UI or
independent-save/crash atomicity. Common registry/server entry and client-only
setup subscribers are separated so dedicated execution never loads screen classes.
All fixture classes/resources remain excluded from production packaging.

The graphical restart fixture uses the same provider/checks. Its new staging steps
need their own successful desktop run; a headless pass cannot substitute for that.
