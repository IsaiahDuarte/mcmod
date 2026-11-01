# Q137 — Terminal layout and button consistency (2026-11-01)

From playthrough notes 3, 5 and 6.

## Change

- **Width (n3):** the terminal's grid widens with the screen, one column (18 px) at a time, from
  9 up to 18 columns. It leaves room for the help button and, when open, the crafting panel. The
  inventory stays nine wide, centred under the grid. Tabs, the search box, tool row, info row,
  Jobs and Patterns views all follow the panel's width (`TerminalScreen.main()`/`columns()`
  replace the old constants). The server window size follows (`columns × rows`).
- **Tools (n5):** each tool under the search box is a word in the same button style:
  - the view (Stored / All / Craftable; was a coloured ⚒);
  - the sort (Name, Qty, Mod, ID, New) and the direction arrow;
  - **Tall** (was ⇕), **Sync** (was J; only with a recipe viewer) and **Views** (was ★).
  - Toggles are green when on and plain when off. Widths come from each tool's longest label;
    a row too narrow for them all shrinks the words evenly.
  - Each still has its explanatory tooltip.
- **Help button (n6):** every device screen puts the **?** in the same place, just outside the
  panel's top-right corner. It used to try three spots and take the first free one, so it moved
  between screens. Only a panel touching the screen's right edge puts it inside. Upgrade slots
  beside a panel start below it. The glyph is drawn centred to the pixel (`HelpButton`).
- **Player guide:** layout, tool names, view names and Sync updated.

## Tests

- `runTerminalRestartClient` (passed): at GUI scale 4 on the 1708 px test window the terminal
  has more than nine columns (`columns() > 9`); every existing grid, Shift-drag, scroll, search
  and saved-view check still passes at the new width.
- `runPresentationClient` (passed): the Importer screen's **?** button is at the panel's right
  edge + 3, top + 3 (`GuideLinks.spot`) before it is pressed; the view tool is found and cycled
  by its words.
- Screenshots looked at: `terminal-scale-1.png`, `terminal-scale-4.png` (18 columns, centred
  inventory, tool words), `crafting-table-scale-4.png` (10 columns beside the crafting panel),
  `importer-scale-4.png` (? above the upgrade slot).
