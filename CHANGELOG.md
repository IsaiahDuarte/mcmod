# Changelog

## 0.1.0-dev — Development bootstrap

- Selected Minecraft 1.21.1, NeoForge 21.1.252 and Java 21.
- Added a minimal loadable Factory Core entry point, later extended with storage registration.
- Added checksum-validated Gradle tooling, dependency locks, formatting, PMD,
  JVM metadata/artifact tests and compiled architecture checks.
- Extended the canonical Python verifier and Linux/Windows CI configuration.
- Verified local macOS arm64 build and client/dedicated-server loading.
- Added a pinned Rust/Wasm probe with parser declaration bounds, finite execution/
  stack/host/memory limits, trap-batch tests and cold/warm startup measurements.
  Chicory remains a test dependency; the production SDK/runtime is pending.
- Added exact ledger snapshots, versioned integrity-checked serialization and
  offline reservation recovery; full staging/job recovery is pending.
- Added world-scoped generations, metadata admission, live-copy quarantine and
  Overworld SavedData with corrupt-file preservation. Complete staging/job saves remain pending.
- Added four-slot Storage Drives, finite item/fluid tiers, earned Infinite Item Cells,
  safe sequential upgrade modules, vanilla recipes/recipe-book unlocks, creative tab
  and player documentation. Required headless GameTests cover placement, break,
  lease preservation/reload, failures and recipe costs.
- Added bounded portable topology validation, current private grants, gateway
  policy intersections and local machine queries. World discovery/activation,
  network persistence and player permissions controls remain pending.

This development artifact is not the first playable release. World transfers, crafting, scripting, terminal UI and wireless features remain unimplemented; complete survival progression remains unverified. P1 core accounting and bounded
NeoForge handler ports are implemented and tested; they are not yet connected
to a playable network. Linux/Windows runtime compatibility and remote CI are unverified.
See [implementation evidence](docs/IMPLEMENTATION.md).
