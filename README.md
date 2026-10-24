# Factory Core

A Minecraft 1.21.1 / NeoForge 21.1.252 storage and automation mod. It offers:
- unified item, fluid and energy storage behind private, segmented networks;
- a storage terminal with search, manual crafting, autocrafting jobs and container actions;
- no-code import/export/stock presets;
- processing patterns for machines;
- programs written in a friendly rule language or in Rust compiled to WebAssembly.

Progression runs from wired storage to paid throughput upgrades, wireless access and links, and
Infinite cells.

**Status:** V1 is feature-complete and in cleanup and iteration. Every system has automated
world-level and real-client evidence; release acceptance (A01–A17) and the human survival
playtest are tracked in [IMPLEMENTATION.md](docs/IMPLEMENTATION.md). Players start with the
[player guide](docs/PLAYER_GUIDE.md); server owners and operators use the
[admin guide](docs/ADMIN_GUIDE.md).

## Develop

You need Java 21, Python 3.11+ and the pinned Rust guest toolchain:

```text
rustup toolchain install 1.95.0 --profile minimal --component rustfmt --component clippy --target wasm32v1-none
```

| Command | Purpose |
| --- | --- |
| `python3 scripts/verify.py` | The inner loop: docs, formatting, PMD, architecture, JVM tests, GameTests (about 45 s) |
| `python3 scripts/verify.py --full` | Also every dedicated-server restart/kill/save-skew suite; run before merging |
| `./gradlew spotlessApply` | Format |
| `./gradlew runClient` / `runServer` | Play in `run/client` / `run/server` |

[TESTING.md](docs/TESTING.md) maps every suite (graphical tours, JEI, multiplayer, benchmarks)
to what it proves. CI runs only when started manually; the local gate is authoritative.

## Read

| Document | Owns |
| --- | --- |
| [SPEC.md](SPEC.md) | Product behavior, release scope and acceptance criteria |
| [IMPLEMENTATION.md](docs/IMPLEMENTATION.md) | Status, stages, decision gates and the acceptance evidence table |
| [ENGINEERING.md](docs/ENGINEERING.md) | Architecture boundaries and change discipline |
| [TESTING.md](docs/TESTING.md) | Gates, suites, test-writing rules and profiling |
| [AGENTS.md](AGENTS.md) | Contributor (human or AI) workflow |
| [ASSETS.md](docs/ASSETS.md) | Models, textures, screens and their client verification |
| [Decisions](docs/decisions/README.md) | ADRs: why each significant choice was made |
| [CHANGELOG.md](CHANGELOG.md) | Versioned artifact status |
| [Archive](docs/archive/) | The original design review and the dated build and verification logs |

Module contracts (invariants, units, thread ownership, persistence, failure behavior):

| Area | Modules |
| --- | --- |
| Resources | [accounting](docs/modules/RESOURCE_ACCOUNTING.md) · [filters/stock goals](docs/modules/RESOURCE_FILTERS.md) · [scheduling](docs/modules/SCHEDULING.md) |
| Storage | [cells and drives](docs/modules/STORAGE_CELLS.md) · [energy banks](docs/modules/ENERGY_BANKS.md) · [device ownership](docs/modules/DEVICE_OWNERSHIP.md) · [ledger persistence](docs/modules/PERSISTENCE.md) |
| Networks | [topology and authority](docs/modules/NETWORK_AUTHORITY.md) · [network persistence](docs/modules/NETWORK_PERSISTENCE.md) · [controllers/gateways/cables](docs/modules/STRUCTURAL_NODES.md) · [wire discovery](docs/modules/WIRE_DISCOVERY.md) · [live networks](docs/modules/WORLD_NETWORKS.md) · [wireless](docs/modules/WIRELESS.md) |
| Transfers | [world transfers](docs/modules/WORLD_STORAGE_TRANSFERS.md) · [staging persistence](docs/modules/TRANSFER_PERSISTENCE.md) · [player inventories](docs/modules/PLAYER_INVENTORY_TRANSFERS.md) · [search](docs/modules/STORAGE_SEARCH.md) |
| Devices | [connectors](docs/modules/CONNECTORS.md) · [machine interfaces](docs/modules/MACHINE_INTERFACES.md) · [buffered interfaces](docs/modules/BUFFERED_INTERFACES.md) · [terminal](docs/modules/STORAGE_TERMINAL.md) · [fuel generator](docs/modules/FUEL_GENERATOR.md) |
| Automation | [crafting](docs/modules/CRAFTING.md) · [programs](docs/modules/PROGRAMS.md) |

Research notes: [LogisticsNetworks comparison](docs/LOGISTICSNETWORKS.md). It informs the design
but does not add requirements.
