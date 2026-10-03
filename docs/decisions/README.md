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
