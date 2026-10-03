# Changelog

## 0.1.0-dev — Development bootstrap

- Selected Minecraft 1.21.1, NeoForge 21.1.252 and Java 21.
- Added a minimal loadable Factory Core entry point with no gameplay blocks.
- Added checksum-validated Gradle tooling, dependency locks, formatting, PMD,
  JVM metadata/artifact tests and compiled architecture checks.
- Extended the canonical Python verifier and Linux/Windows CI configuration.
- Verified local macOS arm64 build and client/dedicated-server loading.
- Added a pinned Rust/Wasm probe with parser declaration bounds, finite execution/
  stack/host/memory limits, trap-batch tests and cold/warm startup measurements.
  Chicory remains a test dependency; the production SDK/runtime is pending.
- Added exact ledger snapshots, versioned integrity-checked serialization and
  offline reservation recovery; world ownership/save integration is pending.

This development artifact is not the first playable release. Storage blocks, world transfers, crafting, scripting, terminal UI, progression
and wireless features remain unimplemented. P1 core accounting and bounded
NeoForge handler ports are implemented and tested; they are not yet connected
to a playable network. Linux/Windows runtime compatibility and remote CI are unverified.
See [implementation evidence](docs/IMPLEMENTATION.md).
