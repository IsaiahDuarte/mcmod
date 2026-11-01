# Q138 — One terminal view: the craft dialog (2026-11-01)

From playthrough note 4: "a modal or something lets you configure the job… if you are out of
that item… it will show 'craft' or something over it."

## Change

- **Ghosts:** a craftable item with none stored shows **Craft** over its veiled icon (was "+")
  in the All and Craftable views.
- **Craft dialog** (`TerminalCraftDialog`): clicking a Craft entry, or middle-clicking any
  craftable entry, opens a modal over the grid instead of switching to the Jobs tab.
  - It shows the item's icon and name, an amount box with − and +, and the plan summary: steps
    and crafts, or the first missing ingredient (red), or the preview's status.
  - Buttons: **Start**, **Keep**, **Details** and **Close**.
  - The server focuses the item in the craftable list (the existing `REQUEST`). Typing previews
    after a 6-tick pause.
  - **Start** starts the shown plan, or previews and starts in one step (`QUICK`/`QUICK_TARGET`)
    if the shown preview isn't for the typed amount. **Keep** sets keep-stock to the amount.
    **Details** opens the item in Jobs for the full plan and tree.
  - It is modal. A click outside closes it, and no grid tooltip shows through it. Escape closes
    only the dialog, Enter starts, Up and Down step the amount. It closes when you leave the
    Items tab.
  - It sends the Jobs tab's commands, so the server's authorization and limits are unchanged.
- **View toggles:** these already existed (Q62, Stored / All / Craftable, now words since Q137).
- **Split out:** inline running jobs (Q146) and mixed-kind items, fluids and energy (Q147). The
  latter needs a server query across kinds.
- **Player guide:** the Craftable items section, plus a new "Crafting from the grid" entry.

## Tests

`runPresentationClient` (passed), A3 scene at GUI scale 4:

- Clicking the stick's Craft entry opens the dialog for `minecraft:stick` while the terminal
  stays out of Jobs mode.
- Typing 2 produces a server preview of 2 sticks (`items-request-scale-4.png`: "Steps: 1 ·
  crafts: 1 · ingredients available").
- Escape closes the dialog and leaves the terminal screen open.
- The later view-cycling steps (All → Craftable → Stored) still pass with the dialog closed.

Screenshots looked at: `items-request-scale-4.png` (the dialog) and
`items-craftable-scale-4.png` (Craft ghosts).
