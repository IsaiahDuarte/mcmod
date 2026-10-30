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

## Amendment — any adjacent machine (playtest H8, 2026-10-26)

HD5 removed the single faced machine. A processing dispatch now tries each side in turn,
starting after the side the Crafter used last. It picks the first machine that:

- exposes the handlers the pattern needs;
- is not leased by another job;
- passes processing admission;
- holds none of the expected outputs; and
- has a journal that agrees.

Skipping a machine that cannot take the job means one dirty machine no longer stops the job;
the refusal reported is that of the last side tried. A machine is any neighbour with an item or
fluid handler, except Factory Core blocks (cables and devices, including other Crafters) and a
hopper pointing into the Crafter (it feeds patterns). The dispatch already saved the exact
machine position, so later passes, cleanup and recovery use that side. A saved target that is no
longer beside the Crafter reports `machine_changed`, as before. Sides with a machine hide the
pattern handler, so a machine never pulls patterns, and a neighbour change invalidates the
Crafter's capabilities. The `facing` block state remains for the front texture only.
`CrafterSidesGameTests` covers taking turns between two furnaces, skipping a dirty furnace, and
the neighbour rules. `CraftingUnitGameTests` covers the pattern handler on machine sides.

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

## Amendment — Crafter screen (2026-10-30, ergonomics plan Q2)

Empty-hand use opens a kit screen (`CrafterMenu`, `CrafterScreen`) instead of printing an
action-bar line. Its nine pattern slots are ordinary menu slots over the Crafter's pattern
handler: click to take, click with a pattern to insert, Shift-click to move. Taking or inserting
needs EDIT where the Crafter is, checked on the server at each slot change; the client greys the
slots out from the view's `editable` flag. Sneak-use with an empty hand still takes the last
pattern, and using a pattern on the block still inserts it, both unchanged.

The screen shows the installed speed (crafts per run), the Crafter's activity (no patterns,
idle, working, or waiting with the job's reason) and each adjacent machine: free, busy with the
output the holding job makes, or waiting with that job's reason. A machine is held when a job's
dispatch from this Crafter targets it; a legacy dispatch without a target holds every side, as
`WorldCraftingJobs` fences it. `CrafterPackets.View` sends block and output registry IDs; the
client names them, so a dedicated server translates nothing. The view refreshes every 10 ticks
and after a slot change. `CrafterScreenGameTests` covers taking the third of three patterns,
inserting from the cursor, refusals without EDIT and a furnace shown busy during a job.
