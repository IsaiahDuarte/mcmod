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
  graph/grant implementation, physical world discovery pending.
- [Canonical network grant persistence](0008-network-persistence.md); schema-two
  metadata and schema-one migration verified locally; extended by ADR 0009.
- [Physical structural leases](0009-structural-leases.md): controllers/gateways,
  directional ports and schema-three migration; discovery/activation pending.
