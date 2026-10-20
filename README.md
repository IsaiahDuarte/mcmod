# Minecraft Storage and Automation

A mod under development: unified item/fluid/energy storage, a crafting terminal, segmented factories, recipe-based autocrafting, and Rust/WebAssembly automation alongside a friendly language. Progression starts with wired storage and adds bounded wireless access/links and infinite item capacity.

Status: incomplete development build for Minecraft 1.21.1 / NeoForge 21.1.252 /
Java 21. Wired item/fluid/energy storage, shared transfers and presets, terminal
crafting/jobs/container actions, friendly/Rust-Wasm programs, wireless access/links,
onboarding and original assets are implemented in part. Local canonical checks,
two-client terminal permissions, graphical screens/art and a separate-process
restart have passing evidence within their recorded scope. Shared wireless resource
caps, remaining adapter/UI/upgrade behavior and full survival/performance/release
acceptance are still open. See the [current implementation evidence and next work](docs/IMPLEMENTATION.md),
[player guide](docs/PLAYER_GUIDE.md) and [asset inventory](docs/ASSETS.md).

## Start here

- [Product specification](SPEC.md): authoritative release scope, behavior, and acceptance criteria.
- [Implementation handoff](docs/IMPLEMENTATION.md): ordered stages, decision gates, evidence tracking, and an instruction to give an implementing AI.
- [Release asset inventory](docs/ASSETS.md): models, textures, device states, screens, onboarding and client verification status.
- [Agent continuation task](docs/AGENT_HANDOFF.md): copyable goal-oriented handoff for the full first playable release.
- [AI contributor instructions](AGENTS.md): required workflow and completion criteria.
- [Engineering rules](docs/ENGINEERING.md): architecture, documentation, and change discipline.
- [Verification requirements](docs/TESTING.md): current checks and required implementation gates.
- [Development changelog](CHANGELOG.md): versioned artifact status and limitations.
- [Ledger persistence](docs/modules/PERSISTENCE.md): initial P2 schema/recovery
  contract; full staging/job recovery remains pending.
- [Canonical transfer staging](docs/modules/TRANSFER_PERSISTENCE.md): bounded
  ownership, schema-four migration and conservative external-intent recovery.
- [Device ownership](docs/modules/DEVICE_OWNERSHIP.md): registry/lease and actual
  SavedData adapter, with [physical storage contracts](docs/modules/STORAGE_CELLS.md) and the [player guide](docs/PLAYER_GUIDE.md).
- [Architecture decisions](docs/decisions/README.md): how to record significant choices.
- [Network authority](docs/modules/NETWORK_AUTHORITY.md): bounded portable
  topology, local scopes and current grants, integrated with live wired availability.
- [Network persistence](docs/modules/NETWORK_PERSISTENCE.md): canonical grant
  metadata, schema-one migration and bounded principal admission.
- [Physical network nodes](docs/modules/STRUCTURAL_NODES.md): portable structural
  ownership, gateway ports and migration.
- [Wire discovery](docs/modules/WIRE_DISCOVERY.md): budgeted reciprocal probes,
  shared scheduling and nonloading world reads.
- [Live wired networks](docs/modules/WORLD_NETWORKS.md): indexed event invalidation,
  bounded server post-ticks and automatic validated cell availability.
- [Energy-bank accounting](docs/modules/ENERGY_BANKS.md): finite tiers and shared
  identity-based FE/tick budgets and physical bank capabilities; real chunks/client
  and complete powered progression evidence pending.
- [Review findings](docs/REVIEW.md): contradictions corrected and remaining decision gates.
- [LogisticsNetworks comparison](docs/LOGISTICSNETWORKS.md): revision-specific overlap and design implications.

## Verify this repository

Requires Python 3.11 or later, JDK 21 and pinned Rust guest tools:

```text
rustup toolchain install 1.95.0 --profile minimal --component rustfmt --component clippy --target wasm32-unknown-unknown
```

The checked-in Gradle wrapper downloads
Gradle and dependencies on the first run.

```text
python3 scripts/verify.py
```

On Windows use `python scripts/verify.py`. This canonical check validates docs,
acceptance tracking and nonempty tooling tests, then runs Gradle `build` for
compilation, packaging, formatting, PMD, Rust fmt/clippy/tests/build and nonempty
JVM/architecture/real-Wasm tests and required headless Minecraft GameTests. Rust is checked in the wired experiment and `sdk/rust` workspace; other unwired
Kotlin/Rust source still fails. Passing this command
proves only its covered behaviors, not full gameplay acceptance or actual client interactions.

Development commands:

```text
./gradlew runClient
./gradlew runServer
```

On Windows use `gradlew.bat`. The client and development server use separate
`run/client` and `run/server` folders. Do not commit runtime worlds or local settings. See [verification requirements](docs/TESTING.md) for exact gates.

The [GitHub Actions workflow](.github/workflows/quality.yml) runs the canonical command on Windows and
Linux only when started manually. Pull requests and pushes are not gated on CI.

Scheduled digital world transfer API and remaining integration limits: [contract](docs/modules/WORLD_STORAGE_TRANSFERS.md).

Physical connector identities, controls, supported providers and unfinished recovery: [connector contract](docs/modules/CONNECTORS.md).


The server-side [storage discovery/search contract](docs/modules/STORAGE_SEARCH.md)
now documents bounded all-key pages and transient exact-count queries. The player
[physical item terminal](docs/modules/STORAGE_TERMINAL.md) now uses these services;
crafting/fluid/energy tabs and the remaining frontend/release scope stay unfinished.

The [authenticated player inventory transfer boundary](docs/modules/PLAYER_INVENTORY_TRANSFERS.md)
now provides server-side selected-slot operations and known cleanup. Native item
terminal menus/client controls have partial [graphical evidence](docs/evidence/terminal-client-v1-2026-10-13.md); full client/release verification remains pending.

Bounded typed selectors and physical stock-deficit planning: [filter/stock contract](docs/modules/RESOURCE_FILTERS.md). These are P3 prerequisites; authenticated persistent preset integration remains unfinished.

Local machine labels, selected windows, sided utilities and current limits: [machine interface contract](docs/modules/MACHINE_INTERFACES.md).
