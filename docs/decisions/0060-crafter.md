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

## Amendment — the terminal's Patterns view (2026-10-30, ergonomics plan Q3)

A **Patterns** tab in the terminal lists every Crafter the terminal's segment can use, with its
nine slots, and moves patterns through the cursor. "Can use" is exactly what autocrafting uses:
`WorldCrafting.visible(scope)`, extracted from `index`, lists the segment's own VALID, current
holders and those of descendant branches whose gateways publish CRAFT, in stable order. A
gateway that withholds CRAFT therefore hides its branch's Crafters here as it hides their
recipes. The rule for moving a pattern is shared with the Crafter screen as
`PatternHolderEntity.editableBy(player)`: EDIT where the Crafter is, which for a published
branch Crafter is checked in its own branch. Inserts go through the holder's own validation.
Crafters had no player label, so rows showed positions; Q107 (below) adds labels.
Payloads and limits are in [STORAGE_TERMINAL](../modules/STORAGE_TERMINAL.md#patterns-view-q3).

## Amendment — Crafter labels (2026-10-30, ergonomics plan Q107)

A Crafter (any `PatternHolderEntity`) has a label of up to 64 characters, set with the Label
Tool under EDIT where it is, as Signal Interfaces are. The tool takes the use before the Crafter's
own screen would open; Shift-copy and paste of Crafter settings (Q13) are unchanged. The label is
saved as an optional `Label` field in schema 1, so older Crafters load with none. It survives
breaking as the item's vanilla `CUSTOM_NAME`, which the loot table copies; placing a named item
labels the Crafter, so an anvil rename works too (truncated to 64). The Crafter screen's title and
the Patterns view rows show the label and fall back to the position; the Patterns row carries it
as `label` (terminal protocol 8). Alternatives: a mod component for the label (rejected: an anvil
name is the vanilla way to name a block item, and one name in two places would diverge), and
labels only through the Crafter screen (rejected: every other label comes from the tool).
Labels do not float over Crafters while the tool is held; the overlay still covers machines and
Signal Interfaces only.

## Every device keeps its anvil name — 2026-10-31 (Q77)

Other devices use the vanilla route the Crafter's label follows, without a label field. Placing an
item named in an anvil leaves `CUSTOM_NAME` among the block entity's kept components (vanilla
1.21), which save and load with it. `DeviceNames.title` makes it the screen title. That covers
Terminal, Drive, Buffer, Storage/Signal Interfaces, Importer/Exporter/cable port, Energy Bank,
Program Controller and Gateway. Loot-table devices list `CUSTOM_NAME` in `CarriedComponents`
(Terminal, Drive, Access Point, Program Controller, Fuel Generator; loot tables regenerated).
Leased devices build their own drop, so `NetworkedBlock` passes it through `DeviceNames.carry`,
which names the device's own item unless it already has a name (recovery items keep theirs). A
Crafter still turns its name into its label. The name is display only: programs keep using
labels, and Q13 copy does not move it.
