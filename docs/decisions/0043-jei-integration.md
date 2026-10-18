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
