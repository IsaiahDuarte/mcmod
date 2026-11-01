# Q139 — Pattern Encoder rethink: naming and layout (2026-11-01)

From playthrough notes 7 and 8. Notes 12 and 16 are split into Q149 and Q148. Very large
recipes stay with Q69.

## Change

- **Name (n7):**
  - The panel toggle reads **Encoder »** / **« Encoder** (was Crafting »). Its tooltip says
    it is the Pattern Encoder: set up a crafting or processing recipe and encode it, or craft a
    table recipe by hand.
  - The guide calls it the Pattern Encoder.
- **Modes (n8):** two choices at the top, **Crafting** and **Processing**, replace the three
  pages Table, Inputs and Outputs.
  - Crafting is unchanged: the 3×3 grid, the arrow and the result, plus Table, Substitutes,
    Craft and ×64.
  - In Processing, the inputs grid (cells 0–8) and the outputs column (cells 9–11) are shown
    together. An arrow sits between them, with **Inputs** and **Outputs** headings over them.
    Inputs and outputs used to be on separate pages, so both could never be seen at once.
- **Amount row:** − / amount / + / Main now sit in one row under the grid (where Substitutes is
  in Crafting). The selected cell's amount text moved to the status area.
- **Clicks:**
  - Clicking, right-clicking or scrolling any of the 12 cells targets, clears or changes it
    directly, so there is no page switch to reach the outputs.
  - Ctrl-click filling still advances within inputs, or within outputs.
- **Unchanged:** server protocol and pattern format (the cells are the same 0–8 inputs and 9–11
  outputs).

## Tests

`runPresentationClient` (passed) drives the panel with real clicks:

- **Processing:** pick Processing, fill input 0 with water and set 1,000 mB. Click output cell 9
  in the column and fill it with water (500 mB). Click output cell 10 and fill it with stone ×2.
  Encode, collapse and reopen (ghosts kept), then switch back to Crafting (fluid ghosts
  dropped).
- **Crafting:** the existing table and stonecutter steps still pass.

Screenshots looked at:

- `crafting-outputs-scale-4.png` and `-scale-1.png`: Inputs and Outputs side by side, amount
  row under the grid.
- `crafting-table-scale-4.png`: Crafting mode.
