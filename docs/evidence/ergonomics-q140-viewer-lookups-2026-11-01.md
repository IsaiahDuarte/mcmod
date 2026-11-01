# Q140 — JEI-like item lookups in the terminal (2026-11-01)

From playthrough note 15.

## Change

- **Lookups:** `TerminalScreen.hoveredIngredient(x, y)` returns the item (`ItemStack`) or fluid
  (`FluidStack`, one bucket) entry under the mouse and its 16 px slot. It returns null over energy,
  empty cells, other tabs and anything outside the grid.
  - **JEI:** a container handler for `TerminalScreen` turns it into a clickable ingredient
    (`getClickableIngredientUnderMouse`), so R, U, bookmarking and cheat-in work on terminal
    entries as on inventory slots.
  - **EMI:** a stack provider does the same.
- **Tooltips:** an item entry's tooltip is the standard item tooltip
  (`Screen.getTooltipFromItem`, which fires `ItemTooltipEvent`), drawn with the stack
  (`renderTooltip(font, lines, image, stack, x, y)`, which fires `RenderTooltipEvent`). So
  tooltip mods add lines and style the box, and JEI/EMI add the mod name. The terminal's
  counts and click help follow. The registry id line is gone (F3+H shows it, as everywhere
  else).
- **Limit:** rows carry an entry's display name but not its data components, so the lookup and
  the tooltip are for the plain item. When the stored stack has another name (an anvil-renamed
  item), the first line shows that name in italics, as vanilla does. Fluid and energy tooltips
  are unchanged.

## Tests

- `runTerminalRestartClient` (passed): the first grid entry is a lookup target whose ingredient
  is an `ItemStack` of that row's item, at `itemX(0)`/`itemY(0)`, 16 wide. A point outside the
  panel is not a lookup target.
- Screenshot `terminal-saved-views-scale-1.png`: a stone entry's tooltip is the standard item
  tooltip followed by the counts.
- Not run here: JEI and EMI are not on the test client's classpath at runtime. Both adapters
  compile against their APIs (JEI 19.57 `IClickableIngredientFactory`, EMI
  `EmiStackInteraction`). Confirm R/U in the pack.
