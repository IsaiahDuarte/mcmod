# Architecture decision records

Use sequential files such as `0001-platform.md` for decisions that materially affect architecture, compatibility, persistence, or performance. The platform selection is recorded in [ADR 0001](0001-platform.md); local macOS validation passed; cross-OS execution is pending.

Each record contains:

- Status: proposed, accepted, or superseded.
- Context and requirements driving the choice.
- The decision, including supported versions or boundaries when relevant.
- Alternatives considered and why they were rejected.
- Consequences and limitations.
- Evidence and validation plan/results, clearly distinguished.
- Migration or superseding decision when applicable.

Record decisions under the authority already provided by the task. An ADR is a durable explanation, not an additional permission ceremony. Keep accepted history and mark superseded records explicitly.

The [implementation plan](../IMPLEMENTATION.md) assigns D01–D07 decision deadlines and evidence requirements. The [spec](../../SPEC.md) owns product behavior. Record current decisions with evidence; do not leave conflicting choices in an ADR and the spec.

## Accepted records

- [Platform bootstrap](0001-platform.md).
- [Resource accounting](0002-resource-accounting.md).
- [Scheduling/workload targets](0003-scheduling-workloads.md).
- [Rust/Wasm feasibility candidate](0004-wasm-feasibility.md); local probe verified,
  production and cross-OS validation pending.
- [Persistence/ownership design](0005-persistence-ownership.md); component/registry/physical lease checks pass locally; complete world/interruption validation pending.
- [Initial progression/balance defaults](0006-initial-balance.md); selected before physical cells/drives, P6 survival/power/wireless validation pending.
- [Wired topology and current authority](0007-topology-authority.md); portable
  graph/grant implementation, now integrated with discovery and live coordination in ADRs 0010–0011.
- [Canonical network grant persistence](0008-network-persistence.md); schema-two
  metadata and schema-one migration verified locally; extended by ADR 0009.
- [Physical structural leases](0009-structural-leases.md): controllers/gateways,
  directional ports and schema-three migration; discovery/activation subsequently integrated in ADRs 0010–0011.

- [Budgeted wire discovery](0010-bounded-wire-discovery.md): portable producer,
  common scheduler task and nonloading world reader; automatic event coordination
  and device activation subsequently integrated in ADR 0011.

- [Live region coordination](0011-live-region-coordination.md): indexed callbacks,
  shared server ticks and current routing availability; actual chunk/restart and
  representative performance checks remain pending.

- [Finite energy-bank access](0012-energy-bank-access.md): selected identity-based
  rate accounting and canonical finite FE; physical bank integration has local
  headless evidence, with real chunks/restart/client evidence pending.

- [ADR 0013 — Sided external storage registration](0013-external-connectors.md): leased connectors, explicit backing identity, bounds and unfinished recovery.

- [Owned buffered inventory](0014-buffered-inventory.md): nine-slot transfer/NeoForge
  inventory boundary; canonical physical ownership, authenticated Import/Paused
  controls and known staged cleanup are implemented; full preset/client verification remains pending.

- [Bounded storage discovery/search](0015-bounded-storage-search.md): derived key
  pages, shared scheduled all-key observations and bounded transient search
  sessions with exact aggregates; terminal frontend and full workload evidence pending.

- [Authenticated player inventory transfers](0016-player-inventory-transfers.md):
  selected-slot/local-owner boundary, shared accounting/rates and context-three
  accepted player evidence; terminal frontend and independent-file validation pending.

- [Physical storage terminal menu](0017-storage-terminal-menu.md): access-only wired
  shell, server-owned exact selections, bounded projections and guarded player
  actions; item controls implemented, client verification and other terminal tabs pending.

- [Canonical buffer selection/rates](0018-buffer-filter-policy.md): nested buffers-v3,
  shared metadata/rate budgets, guarded filtered Import and basic physical controls;
  export/stock/full editor and release verification remain pending.

- [Local machine bindings](0019-local-machine-bindings.md): distinct schema11 leases,
  local labels and shared sided resource routes; presets/editor/release checks pending.

- [Canonical machine preset authority](0020-machine-preset-authority.md): bounded
  world12 deployment records and freshness/permission guards; automatic dispatch/UI pending.

- [Bounded machine preset dispatch](0021-machine-preset-dispatch.md): shared scheduled
  item/mB/FE Import/Export/Stock and canonical configuration fences; player authoring/release checks pending.

- [0022 — Recovery inspection and audited known cleanup](0022-recovery-inspection.md)

- [0023 — Audited canonical cell conflict repair](0023-canonical-cell-repair.md)

- [0024 — Audited canonical Buffered Interface conflict repair](0024-buffer-conflict-repair.md)

- [0025 — Audited controller and gateway conflict repair](0025-network-node-repair.md)

- [0026 — Audited connector and Machine Interface conflict repair](0026-foreign-port-repair.md)

- [0027 — Standalone vanilla Fuel Generator](0027-fuel-generator.md)

- [0028 — Terminal manual crafting grid](0028-terminal-manual-crafting.md)

- [0029 — Portable pattern model and bounded recursive planner](0029-crafting-planner.md)

- [0030 — Pattern items and Crafting Units](0030-patterns-and-crafting-units.md)

- [0031 — Persisted crafting jobs with single reservations](0031-crafting-jobs.md)

- [0032 — Processing patterns and the Crafting Interface](0032-processing-patterns.md)

- [0033 — Published branch recipes](0033-branch-recipe-publication.md)

- [0034 — Keep-stock crafting requests](0034-keep-stock-requests.md)

- [0035 — Friendly language version 1 (D05)](0035-friendly-language.md)

- [0036 — Program Controller runtime for native rules](0036-program-controller-runtime.md)

- [0037 — Production Wasm runtime and factory_v1 ABI](0037-wasm-factory-v1.md)

- [0038 — Terminal fluid containers and energy items](0038-terminal-fluids-energy.md)
- [0039 — Wireless access point and handheld terminal](0039-wireless-access.md)
- [0040 — Paired wireless links](0040-paired-wireless-links.md)
- [0041 — Gateway policy editor](0041-gateway-policy-editor.md)
- [0042 — In-game Factory Guide](0042-in-game-guide.md)
- [0043 — Optional JEI integration](0043-jei-integration.md)
- [0044 — Persistent guest state](0044-persistent-guest-state.md)
- [0045 — Fluid processing patterns](0045-fluid-processing.md)
- [0046 — Independent-save mismatch adoption for storage and structural holders](0046-independent-save-adoption.md)
- [0048 — D06 balance version two: recipes, tiers and power curve](0048-final-balance.md)

- [0047 — Legacy staged-transfer settlement](0047-legacy-staging-settlement.md)

- [0049 — Player access administration and live terminal observations](0049-player-access-administration.md)

- [0050 — Shared wireless resource throughput](0050-shared-wireless-throughput.md)

- [0052 — Bounded terminal search, sort and player preferences](0052-terminal-search-preferences.md)

- [0051 — Native program authoring and read-only previews](0051-program-authoring.md)

- [0053 — Selected tanks and fixed machine ports](0053-selected-tanks-and-fixed-machine-ports.md)

- [0054 — Rust SDK v2 and the revised factory_v1 ABI](0054-rust-sdk-v2.md)
- [0058 — Device state presentation and client coverage](0058-device-presentation.md)

- [0054 — Paid throughput progression under unchanged fair budgets](0054-throughput-upgrades.md)

- [0055 — Network Access and Transfer Recovery screens](0055-access-and-recovery-screens.md)

- [0056 — Program host/log limits and visible overload](0056-program-limits-and-overload.md)

- [0057 — Processing dispatch journal and interruption recovery](0057-processing-journal-and-interruption.md)

- [0059 — Unified Interface block](0059-unified-interface.md)

- [0060 — Crafter block](0060-crafter.md)

- [0061 — Wireless link as a Gateway upgrade](0061-gateway-link-upgrade.md)

- [0062 — Friendly language version 2](0062-friendly-language-v2.md)

- [0063 — Crafting-pattern substitution](0063-pattern-substitution.md)
