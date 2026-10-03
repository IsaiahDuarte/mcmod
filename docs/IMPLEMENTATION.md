# AI implementation handoff

## Authority and current state

[SPEC.md](../SPEC.md) owns product behavior and release scope. This file owns implementation sequence, decision gates, and evidence tracking. [AGENTS.md](../AGENTS.md) owns the contributor workflow. Follow the current user's instructions; do not treat old proposals or research comparisons as additional requirements.

Current state: design/tooling only. All implementation stages below are **not started**. Product acceptance criteria have no passing evidence yet.

## How an AI should proceed

1. Read the spec, contributor rules, testing policy, this plan, and accepted ADRs.
2. Select the earliest incomplete stage. Resolve its prerequisite decisions using current primary documentation and focused experiments.
3. Record the choice and evidence in an ADR. Routine technical/balance choices within the spec are delegated to the implementing AI; do not ask the user to select every library or numeric default.
4. If no feasible option satisfies a required behavior, report the concrete conflict and alternatives. Do not silently remove that requirement or treat an untested assumption as settled.
5. Implement one reviewable vertical slice with real behavior, affected docs, tests, and required checks. Temporary experiments use their own reproducible harness and must not bypass source/build verification.
6. Update stage progress and acceptance evidence with file paths, test names, commands, environment, and observed outcomes. Partial implementation stays partial.
7. Continue authorized work through subsequent stages. Report limitations honestly; an unavailable required test prevents verified completion of the affected stage.

## Decision gates

All gates are currently **open**. The implementing AI owns resolution within the stated product contract. Record a selected design before dependent coding; mark it validated only after its required evidence exists. Experimental implementation/builds needed to obtain evidence are allowed within the owning stage. They do not count as a completed gate or stage. Performance budgets are targets until measured.

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
| A02 | P2, P6 | Not implemented. |
| A03 | P1, P2, P3 | Not implemented. |
| A04 | P2, P3, P6 | Not implemented. |
| A05 | P2 | Not implemented. |
| A06 | P4 | Not implemented. |
| A07 | P4 | Not implemented. |
| A08 | P4, P5 | Not implemented. |
| A09 | P5 | Not implemented. |
| A10 | P1, P5, P7 | Not implemented. |
| A11 | P3, P4, P6 | Not implemented. |
| A12 | P2, P6 | Not implemented. |
| A13 | P2, P4, P7 | Not implemented. |
| A14 | P0, P3, P6 | Not implemented. |
| A15 | P1, P7 | Not implemented. |
| A16 | P6 | Not implemented. |
| A17 | P6, P7 | Not implemented. |

## Suggested implementation instruction

> Implement this repository according to AGENTS.md, SPEC.md, and docs/IMPLEMENTATION.md. Resolve decision gates with evidence and ADRs, starting with P0, then continue through the ordered stages. Preserve the full first-playable scope. Keep docs and meaningful tests with each behavior change, run the required checks, and maintain acceptance evidence. Make routine choices within the contract autonomously; report genuine blockers and never claim unrun checks passed.

This instruction authorizes implementation when the user gives it to an agent. Its presence in a design repository does not itself start implementation.
