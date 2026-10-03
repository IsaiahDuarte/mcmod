# Minecraft Storage and Automation

A mod under development: unified item/fluid/energy storage, a crafting terminal, segmented factories, recipe-based autocrafting, and Rust/WebAssembly automation alongside a friendly language. Progression starts with wired storage and adds bounded wireless access/links and infinite item capacity.

Status: P0 bootstrap implemented and locally verified for Minecraft 1.21.1 / NeoForge 21.1.252 / Java 21. The minimal loader entry and real JVM verification are in place. No playable storage or automation exists yet. P1 accounting/scheduling foundations and a real Rust/Wasm sandbox probe are locally verified. See [resource contracts](docs/modules/RESOURCE_ACCOUNTING.md), [runtime decision](docs/decisions/0004-wasm-feasibility.md), [platform ADR](docs/decisions/0001-platform.md) and [implementation evidence](docs/IMPLEMENTATION.md).

## Start here

- [Product specification](SPEC.md): authoritative release scope, behavior, and acceptance criteria.
- [Implementation handoff](docs/IMPLEMENTATION.md): ordered stages, decision gates, evidence tracking, and an instruction to give an implementing AI.
- [AI contributor instructions](AGENTS.md): required workflow and completion criteria.
- [Engineering rules](docs/ENGINEERING.md): architecture, documentation, and change discipline.
- [Verification requirements](docs/TESTING.md): current checks and required implementation gates.
- [Development changelog](CHANGELOG.md): versioned artifact status and limitations.
- [Architecture decisions](docs/decisions/README.md): how to record significant choices.
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
JVM/architecture/real-Wasm tests. Rust is checked only in the experiment's wired
source tree; other unwired Kotlin/Rust source still fails. Passing this command
does not prove gameplay or client/server startup.

Development commands:

```text
./gradlew runClient
./gradlew runServer
```

On Windows use `gradlew.bat`. The client and development server use separate
`run/client` and `run/server` folders. Do not commit runtime worlds or local settings. See [verification requirements](docs/TESTING.md) for exact gates.

The [GitHub Actions workflow](.github/workflows/quality.yml) installs Java 21 and pinned Rust tools, and
runs the canonical command on Windows and Linux. Remote CI and branch protection
remain unverified.
