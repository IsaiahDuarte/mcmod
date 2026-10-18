# ADR 0028 — Terminal manual crafting grid

Status: selected; native headless evidence for P4/R08 manual crafting. JEI transfer,
patterns/autocrafting and graphical verification of the grid remain open.

## Context

R08 requires a built-in 3-by-3 ghost recipe grid filled from the player's inventory
and authorized network stock; crafting must revalidate, use exactly the current
ingredients, return outputs and remainders without loss, stay bounded per request,
and never hide stacks in the grid.

## Decision

Each terminal session owns a server-side `TerminalCrafting` grid of nine exact item
`ResourceKey` ghosts (components included). Ghosts hold no items and are dropped
with the session; nothing needs persistence or close-time cleanup. Clients set a
ghost by naming an own main-inventory slot plus the exact-key SHA-256 fingerprint
already used for deposits, or a current terminal row under the current view
revision. They never send stacks, recipes or quantities.

The recipe is the vanilla `RecipeType.CRAFTING` match for the nine ghosts, cached
per grid revision. A craft request names the grid revision and 1–64 crafts:

1. Portable `GridRequirement` computes exact per-craft units and the first deficit
   against the player's 36 main-inventory slots.
2. A deficit is gathered through the existing priority withdraw route into the
   player inventory (current grants, range, scheduler and staging apply), at most
   64 units per route and 18 routes per request; then the craft is retried. When
   storage cannot supply more, the request crafts as many whole recipes as owned
   ingredients cover.
3. Crafting runs on the server thread over a copy of the main inventory: per craft
   it takes one exact unit per ghost, re-runs `matches`, assembles the output and
   `getRemainingItems`, and keeps the craft only if output and remainders all fit.
   The inventory is written once at the end. A craft that cannot fit stops the
   request before any of its ingredients are consumed, so no staging is needed.

Ghost edits have a separate 32-per-second budget; crafts use the menu's existing
two-tick command throttle and one in-flight action. Stats/advancements use
`onCraftedBy` and the recipe is awarded to the recipe book.

## Alternatives

Real container slots in the grid would hide items that can be lost on close and
invite client-predicted moves. Extracting ingredients directly from network storage
into a craft would bypass shared routes or need new crafting staging; gathering into
the inventory reuses audited withdrawal and leaves gathered items with the player
if crafting later stops. Consuming before checking output space would require a
staging owner for outputs; the inventory copy proves fit first.

## Consequences

Gathered ingredients stay in the player inventory when a request stops early; that
is ordinary ownership, not loss. Only the 36 main slots participate. Ghost state
is per session, not per player. Patterns, Crafting Units, JEI transfer and recursive
autocrafting remain separate P4/P6 work.
