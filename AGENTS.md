# Repository instructions for AI contributors

## Read before changing files

Read [SPEC.md](SPEC.md), [implementation plan](docs/IMPLEMENTATION.md), [engineering rules](docs/ENGINEERING.md), and [verification requirements](docs/TESTING.md), then relevant module docs and ADRs. Inspect existing code and tests before proposing new infrastructure.

This repository currently contains a design and verification tooling. Do not assume a game version, loader, build system, package name, or Wasm runtime has been selected. Resolve platform choices before adding platform-specific implementation.

## Working rules

1. Identify the requested behavior, affected contracts, acceptance IDs, and earliest applicable implementation stage. The spec owns release behavior, the plan owns sequencing/open decisions, and ADRs explain decisions. Reconcile conflicts before implementing; research notes do not override requirements.
2. Preserve unrelated user changes. Make focused patches; avoid drive-by refactors, generated clutter, and speculative frameworks.
3. Follow the existing module boundaries. Keep Minecraft/loader types out of the portable core and optional integrations out of mandatory class loading.
4. Route UI, crafting, and script operations through the shared authorization, scheduling, and resource-accounting paths.
5. Make resource ownership, units, error behavior, and thread ownership explicit. Never silently lose resources, overflow counts, replay uncertain transfers, or widen segment visibility.
6. Bound all tick work, host calls, event queues, and guest execution. Do not add an unbounded network scan or one thread per program. Validate performance claims with representative measurements.
7. Add or update behavioral tests for changed logic. Bug fixes require a regression test when reproducible; explain any exception with concrete evidence.
8. Update affected docs and examples in the same change. Document public contracts and meaningful invariants; avoid comments that restate syntax.
9. Run the required applicable checks. Report exact commands, results, and any checks that could not run. Never call an unrun or failing check passed.
10. Review the final diff for scope, stale docs, disabled checks, accidental artifacts, and unsupported claims before reporting completion.
11. Resolve decision gates before dependent implementation and record evidence. Routine technical/balance choices within the spec are delegated to the implementing AI; ask only when a real conflict or missing external constraint prevents a compatible decision.
12. Maintain acceptance evidence in the implementation plan. An internal demo or passing documentation check is not the full first-playable release. Do not label unfinished stages or placeholder implementations complete.

## Documentation obligations

- Player-visible behavior: update the player guide when one exists and the relevant spec acceptance scenario until then.
- Public API/SDK: document parameters, units, scope, errors, lifecycle, and a working example.
- Persistence: document schema version, migration, ownership, and interrupted-operation recovery.
- Topology/concurrency: document authorization and invalidation rules, thread ownership, and race handling.
- Architecture/dependency/platform changes: add or amend an ADR with alternatives and consequences.
- Performance changes: record workload, hardware/runtime, baseline, measurements, and tradeoffs.
- Keep documentation factual: implemented, proposed, and unverified behavior must be identifiable.

## Test and verification obligations

- Exercise observable behavior and failure cases rather than private method structure or mock call counts.
- Prefer deterministic fake inventories/clocks for core logic; use real platform integration checks for adapter contracts.
- Cover partial insertion, stale handles, competing reservations, chunk unload, reload, cancellation, and limits when affected.
- Use property-based or generated operation sequences for conservation and accounting where they catch combinations example tests miss. Record replay seeds.
- Update compatibility and migration tests with ABI/schema changes.
- Do not write empty tests or implementation-mirroring assertions to satisfy a count. Documentation-only edits do not need unrelated gameplay tests.
- Do not delete, skip, weaken, or change expected results merely to make checks green. If a changed requirement invalidates a test, explain the requirement and replace the coverage.
- Do not remove the source-bootstrap guard without wiring real build, formatting/static, architecture, and test commands into verification and CI in the same change.

## Completion contract

A behavior change is complete when its acceptance criteria are implemented, relevant docs and tests are updated, required checks pass, and remaining limitations are reported. Report a blocked check as blocked and distinguish implemented work from verified work.

An AI instruction file cannot enforce its own truthfulness. Executable checks and review are also required. Do not silently weaken policy or CI to bypass a gate; proposed changes to those rules need an explicit rationale in the change description.

These repository rules add no new approval ritual. Continue work already authorized by the user, and ask only for decisions that genuinely block it. Do not delegate to other agents unless the user explicitly requests delegation.
