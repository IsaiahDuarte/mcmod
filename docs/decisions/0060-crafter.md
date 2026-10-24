# 0060 — Crafter block

Status: accepted 2026-10-24 (UX plan U12, gates UD1 and UD7).

## Context

UD1 merges the Crafting Unit, which holds crafting-table patterns, and the Crafting
Interface, which holds processing patterns and faces a machine, into one **Crafter**.
Unlike the three Interfaces (ADR 0059), both are plain pattern holders on
`PatternHolderEntity`. Neither has a lease, an ownership record or a persisted
registry, and job planning already indexes any holder's crafting and processing
patterns generically (`WorldCrafting`).

## Decision

- **One block and entity.** `factorycore:crafter` is a single block with `facing`,
  backed by a single `CrafterEntity` that accepts both pattern kinds in its nine
  slots. Crafting-table patterns run as before, needing no machine. Processing
  patterns are pushed into the machine the Crafter faces, unchanged from the
  Crafting Interface. Hoppers may feed patterns on every face except the faced
  machine's.
- **Placement and controls.** The Crafter faces the block it is placed against, like
  the Crafting Interface. It keeps the Unit's controls: using a pattern inserts it,
  and sneak-use with an empty hand takes the last one back. Its recipe is the old
  Crafting Unit's (iron, gold, redstone, crafting table).
- **Removed.** The `crafting_unit` and `crafting_interface` blocks, items,
  block-entity types and recipes are deleted, with no aliases (UD7). A single real
  entity is simpler than ADR 0059's per-kind backing because there is no persisted
  state to preserve.

## Alternatives

- **Keep two entity types behind one block, as ADR 0059 does.** That pattern exists
  to keep three persistence schemas untouched; the crafting holders have none, so it
  would add complexity for nothing.
- **Unit-style (unfaced) Crafter.** This would need a second block or a mode for
  processing. Rejected, because facing is harmless for crafting-table patterns.

## Consequences

The pattern holder's saved data, pattern index and job journal are unchanged. Tests
and fixtures place `crafter`, and the guide and docs describe one Crafter. The
display name "Crafter" follows the UD4 names table. Vanilla 1.21 also has a block
called Crafter (a redstone autocrafter); see UD4 for a possible rename.
