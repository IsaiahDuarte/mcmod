# Engineering rules

## Design priorities

Resource correctness and clear ownership come first. Performance must be measured against documented workloads. Keep the user experience understandable and the code easy to change through concrete boundaries, small cohesive components, and explicit contracts.

[SPEC.md](../SPEC.md) defines product behavior; [IMPLEMENTATION.md](IMPLEMENTATION.md) owns sequencing, decision gates, and evidence; [AGENTS.md](../AGENTS.md) defines contributor workflow. ADRs explain decisions and must agree with these contracts. Research and review notes are not independent requirements.

## Code standards

- Use descriptive domain names, explicit resource units, immutable value objects where practical, and checked quantity arithmetic.
- Keep operations and state transitions cohesive. Split a component when it has distinct owners or responsibilities, not to meet arbitrary file or line counts.
- Prefer composition and explicit dependencies. Add an interface for a real integration boundary or interchangeable behavior, not for every class.
- Represent expected failures as typed results with actionable reasons. Exceptions should preserve context; never silently swallow them.
- Document non-obvious invariants, public API behavior, ownership, and concurrency. Remove dead code and speculative abstractions.
- Keep platform objects and mutable world access in adapters. Core planning and accounting should be testable without launching Minecraft.
- Keep data supplied by clients or scripts subject to server validation and segment permissions.
- Document and test the exact insertion/extraction contract for each resource adapter. Do not assume simulation is a reservation or that third-party handlers support rollback.
- Define cache ownership, invalidation, and memory bounds with each cache. Do not introduce global mutable caches without a documented lifecycle.
- Use versioned schemas and explicit migrations. Never reset incompatible player data silently.

## Documentation required by change type

| Change | Required documentation and evidence |
| --- | --- |
| New feature | Player behavior, acceptance scenario, relevant examples, behavioral tests. |
| Bug fix | Reproduction, expected behavior, regression test, corrected docs if their contract was wrong. |
| API/SDK | Contract, units/errors/permissions, versioning impact, examples and compatibility tests. |
| Persistence | Ownership and schema, migration/recovery rules, old-save and interruption fixtures. |
| Topology/scheduler | Scope and state transitions, invalidation/fairness rules, isolation/overload tests. |
| Resource adapter | Supported operations, units, partial/failure semantics, adapter integration tests. |
| Optimization | Comparable before/after workload, baseline, environment, latency/throughput/memory evidence. |
| Architecture/platform/dependency | ADR covering motivation, alternatives, deployment/compatibility cost, validation. |
| Pure refactor | Preserved contract, relevant verification, boundary docs if structure changed. |
| Docs or tooling | Relevant links/examples/checks; no unrelated gameplay tests. |

Public behavior documentation belongs with the feature; detailed module contracts belong alongside the owning module once it exists. Keep a discoverable index. Add a changelog when the first versioned artifact exists.

## Architecture decisions

Use [architecture decision records](decisions/README.md) for consequential choices, including platform, Wasm runtime, amount representation, persistence/transfer recovery, dependency boundaries, and segment semantics. Small routine implementation choices do not need an ADR.

The next implementation bootstrap must record the chosen Minecraft/loader/JDK versions, build tooling, dependency locking policy, supported operating systems, and test harness. Rust guest tooling and ABI support need their own documented versions.

## Definition of done

- Requested behavior and affected failure paths are implemented; no placeholder implementation is presented as complete.
- Docs, examples, tests, and compatibility fixtures agree with the behavior.
- Applicable checks from [TESTING.md](TESTING.md) pass. A check that could not run remains explicitly unverified.
- No required check was disabled or weakened to hide a failure.
- Performance-sensitive changes include evidence proportionate to their risk; unrelated full benchmark suites need not run for every edit.
- The change summary states behavior, validation, and practical limitations.

## Enforcement boundaries

The local verifier and prepared CI enforce required files, basic Markdown structure including closed fences, local file links, matching acceptance IDs in spec/plan, passing nonempty tooling tests, and the absence of unverified JVM/Rust source. They cannot prove prose accurate, evidence authentic, or tests meaningful. CI has not run remotely.

Once code exists, automate formatting/static checks, dependency boundaries, compilation, behavioral tests, and required integration checks. Semantic documentation quality and acceptance coverage remain review responsibilities. Do not substitute a coverage percentage or a checked PR checkbox for behavioral evidence.

When hosted, configure the CI status as a required merge check and review changes to governance/CI. Repository hosting and branch protection are external setup tasks and are not established by adding a workflow file.
