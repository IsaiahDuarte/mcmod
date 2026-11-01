# Q6 — Stonecutter and smithing patterns (2026-10-31)

QD3 resolved yes. ADR 0030 and SPEC R06 amended.

## Change

- **`PatternContents.Table`:** crafting, stonecutting (input in cell 0) or smithing (template,
  base and addition in cells 0–2). It is saved as `table` only when it is not crafting. Cells
  that do not fit the workstation, and substitutes on these kinds, are refused.
- **`TableRecipes`:** `cuts` lists a stonecutter input's results; `make` finds the recipe and
  checks it gives the encoded output and remainders.
- **Crafter:** `WorldCraftingJobs.assemble` calls `make` before every craft, as it already
  revalidates crafting-table recipes.
- **Terminal grid:**
  - a workstation button above the grid (Crafting table, Stonecutter, Smithing table);
  - clicking, right-clicking or scrolling on the stonecutter result picks another result;
  - Craft answers "Encode this recipe as a pattern; a Crafter makes it";
  - Encode and Ctrl-click load keep the workstation and the chosen result.
- **JEI:** `TableTransferHandler` fills the stonecutter or smithing workstation, choosing the
  result JEI shows.

## Tests

- **`TablePatternGameTests`:** jobs cut 4 stone bricks from 6 stone (2 left), and smith a
  netherite sword from template, diamond sword and netherite ingot, each in a Crafter. A badly
  laid-out stonecutter pattern is refused, and a smithing pattern survives a save.
- **`TerminalCraftingGameTests.stonecutterAndSmithingRecipesFillChooseAndEncode`:** a fill
  chooses stone bricks; CUT moves to another result and back; Craft is refused; Encode writes a
  stonecutter pattern; a smithing fill shows the netherite pickaxe and encodes; loading the
  stonecutter pattern restores it.
- **`TerminalCraftingPacketsTest`:** the view's table and the fill's table and result
  round-trip, and are bounded.
- **`runJeiClient`:** JEI's stonecutting (`stone_bricks_from_stone_stonecutting`) and smithing
  (`netherite_sword_smithing`) recipes fill the grid (`jei-stonecutter-transfer.png`,
  `jei-smithing-transfer.png`). Its program-ghost check now expects sentence names
  ("iron ingot fluid water"), a stale expectation left by Q126.
- **`runPresentationClient`:** the crafting panel at scales 1 and 4 shows the workstation
  button between the mode tabs and the grid. A first version overlapped Encode and was moved.
- **Full GameTest run:** 501 of 501 pass.
