# ADR 0001 — First platform and verification bootstrap

Status: accepted; D01 locally validated on macOS arm64. Linux/Windows execution pending.

## Context

P0 needs a real client/server mod and executable verification before gameplay
implementation. R01 requires one platform; R10 requires a portable core and
optional integrations. No existing source or accepted platform decision exists.

## Decision

- Minecraft 1.21.1, NeoForge 21.1.252, Java 21, ModDevGradle 2.0.148,
  Gradle 9.2.1 with the official wrapper and distribution SHA-256.
- Mod ID `factorycore`, display name Factory Core, package `dev.izzy.factorycore`.
  Version `0.1.0-dev` denotes development, not a playable release.
- Original code remains All Rights Reserved; do not imply permission to
  redistribute Minecraft, its mappings, or dependencies. Wrapper files retain
  their upstream license notices.
- Target Linux and Windows client/server, macOS client/server (including Apple
  silicon). These are validation targets, not verified compatibility claims.
- Rust 1.95.0 is the baseline guest toolchain. Target/runtime/ABI stay open at D04;
  no Rust production source is introduced in P0.
- Gradle compiles and packages; Spotless 8.1.0/google-java-format 1.28.0 checks
  formatting; PMD 7.17.0 checks Java; JUnit 5.11.4 runs behavioral tests;
  ArchUnit 1.4.1 checks compiled dependency boundaries. Test suites reject zero
  execution and skips. Portable code lives in `..core..`, platform code in
  `..platform..`, optional integration code in `..integration..`.
- Pin direct versions and lock resolvable application/test configurations.
  Commit lock updates only after resolving and reviewing them. ModDevGradle
  controls Minecraft tool dependencies; the pinned NeoForge/MDG versions control
  that graph. No dynamic direct versions or optional mod dependencies at P0.
- Preserve the Python document/tooling gate and extend its entry point to execute
  Gradle `build`. Missing tools and subprocess failures are failures.

## Alternatives

Fabric offers a smaller loader but requires additional choices for energy and
the requested JEI/Mekanism environment. NeoForge provides those integration
boundaries directly. A newer Minecraft release would increase ecosystem churn
without satisfying additional first-release requirements. Supporting multiple
loaders now adds adapter and test cost beyond R01. Native Wasm dependencies are
not selected here; D04 must compare them with JVM runtimes using experiments.

## Consequences

The mod initially loads without registering gameplay blocks. P0 alone is not a
storage demo or a completed A14. Client and dedicated-server startup are required
before D01/P0 exit. Client startup needs a graphical environment. All remaining
product gates and acceptance criteria retain their original scope.

## Evidence

Selection sources inspected 2026-10-03:

- [NeoForge 1.21.1 getting started](https://docs.neoforged.net/docs/1.21.1/gettingstarted/)
  specifies Java 21 and client/server development flows.
- [Official MDK](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle)
  selects NeoForge 21.1.252, MDG 2.0.148 and Gradle 9.2.1 at inspection.
- [ModDevGradle documentation](https://docs.neoforged.net/toolchain/docs/plugins/mdg/)
  describes source bindings and run configurations.

Local environment: macOS, OpenJDK 21.0.12, Python 3.13.11, Rust 1.95.0.
Baseline `python3 scripts/verify.py`: passed 15 tooling tests before source.
Local clean build, JUnit/ArchUnit and client/server startup passed. ArchUnit uses
the platform's SLF4J 2.0.9 instead of its newer transitive dependency, preserving
Minecraft's strict version constraint. The official Gradle 9.2.1 wrapper JAR
SHA-256 is `423cb469ccc0ecc31f0e4e1c309976198ccb734cdcbb7029d4bda0f18f57e8d9`.
Remote CI and cross-OS execution remain unverified; see actual outcomes in
[the implementation plan](../IMPLEMENTATION.md).
