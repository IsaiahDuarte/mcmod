# ADR 0043 — Optional JEI integration

Status: selected; compiled against JEI 19.57.0.451 API. Server-side transfer behavior has
headless evidence. On 2026-10-18 the client behavior ran graphically with JEI 19.57.0.451
installed ([evidence](../evidence/jei-client-v1-2026-10-18.md)): JEI resolved both
handlers and drove them. Physical drag and the "+" button click were not simulated.

## Decision

- **Dependency:** compile-only `mezz.jei:jei-1.21.1-{common,neoforge}-api:19.57.0.451`
  from maven.blamejared.com, through Gradle `exclusiveContent` limited to group
  `mezz.jei`. It is locked, never bundled, and never a runtime requirement.
- **Isolation:** the plugin lives in `platform.client.integration.jei` and is loaded only
  by JEI's `@JeiPlugin` scan. `ArchitectureTest` already forbids any other class from
  depending on `..integration..` or `mezz.jei..`, and keeps client classes under
  `platform.client`.
- **Recipe transfer:** the JEI crafting transfer into the terminal grid sends one
  `TerminalCraftingPackets.Fill` with nine registry item IDs. `GridTransfer.choose`
  prefers, per cell, an option the player can see (own inventory or terminal rows),
  otherwise the first option.
  - The server switches the grid to crafting mode and sets ghost cells by registry
    identity (the R07 default). Unknown IDs clear a cell.
  - The fill counts against the 32-edits-per-second ghost budget.
  - Nothing moves; a craft revalidates real stock.
  - The payload is viewer-neutral, so other recipe viewers could use it.
- **Editor drops:** dropping a JEI item or fluid on the Program editor types
  `item("…")`/`fluid("…")` at the cursor, escaped by `ResourceExpressions` with exactly
  the compiler's two escapes.
- **Without JEI:** players use terminal rows, own-inventory ghost cells and typed resource
  IDs.

## Alternatives

Transferring real items like a crafting-table handler was rejected: the grid is a ghost
grid, and moving stock belongs to the craft action. Registering every viewed recipe as a
pattern is forbidden by R07. Tag or full-component matching for drops is a later explicit
option.

## Consequences

A11 JEI filling and the R07 JEI-drop requirement are implemented. `./gradlew runJeiClient`
verifies them in an isolated `jeiTest` source set, the only one with JEI at runtime; the
`net.mezzdev.config` group is allowed from JEI's maven for that run. A14
"works without JEI" is evidenced by every headless run, since none has JEI on the
classpath.


## U4 panel visibility (2026-10-20)

Receiving a nonempty JEI ghost fill opens the terminal's crafting panel on Table;
the user need not expand it before using recipe transfer. The payload and server
fill path are unchanged. The JEI fixture additionally exercises Craft, table Encode,
Inputs/Outputs and processing Encode with JEI installed, preserving real stock.
Physical recipe-plus clicks and ingredient dragging remain separate verification gaps.

## Amendment — processing transfer from any recipe (2026-10-30, ergonomics plan Q5)

A universal transfer handler (`ProcessingTransferHandler`, JEI's
`addUniversalRecipeTransferHandler`) serves every recipe category without a specific handler, so
furnace, blasting, smoking, campfire and other mods' machine recipes fill the processing grid.
JEI keeps preferring the crafting-table handler for crafting recipes. The handler reads each
slot's displayed item or fluid by role (INPUT, OUTPUT; catalysts and other ingredient types are
ignored), so it needs no per-category code. Outputs past three are dropped with an action-bar
message; more than nine inputs is a transfer error shown on the button, as is a recipe without
an item or fluid on both sides. Fluid amounts round up to the grid's 250 mB unit; the player
sees and can change the amounts before encoding. Alternative: one handler per known category
(rejected: it misses other mods' categories, which is the point of the slice).

Processing transfers (Q5) choose among an input's alternatives the way crafting transfers do:
the first one the player has in the inventory or the terminal, else the first listed. They
never use JEI's cycling display, so a click does not depend on the moment it is made (fixed
2026-10-30 during Q65).

## Amendment — plentiful choice and mode warnings (2026-10-30, ergonomics plan Q112)

Both transfers pick, for each slot with alternatives, the item the player has most of: undamaged
inventory stacks plus the available amount of each shown terminal row. A tie keeps JEI's listed
order (`GridTransfer.choose` with counts). Damaged stacks do not count, so a transfer never
steers a craft toward worn tools. A transfer that switches the grid between Table and
Inputs/Outputs says so on the action bar instead of silently changing mode. A crafting recipe with
more than nine inputs is still refused.

The processing grid has a typed amount per slot (items, or mB rounded up to 250 mB steps, 1–64
steps) and a `MAIN` grid command that swaps output 2 or 3, with its count, into the first output.
The first output is what the encoded pattern makes. Alternatives: an explicit "main" flag per
output (rejected: the first output already has that meaning in the pattern format, so swapping
keeps old patterns unchanged).
