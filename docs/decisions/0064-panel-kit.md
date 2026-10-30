# 0064 — Panel kit for screens

Status: accepted 2026-10-26 (playtest plan H1, gate HD3).

## Context

The first survival playtest found the spacing and whitespace of every screen odd (finding K1 in
the [playtest plan](../PLAYTEST_PLAN.md)). Each screen chose its own width (220, 300 or the
whole window), placed every widget at hand-written pixel offsets, and drew flat dark rectangles.
No two screens shared a row height, gap, button size or footer. Large Interface screens filled
the window at GUI scale 4 with full-width buttons. Instruction sentences sat inside the panels.
HD3 chose vanilla/AE2-style panels.

## Decision

Every screen is built from one kit in `platform/client/ui`:

- **Geometry (`PanelLayout`).** Panels have a fixed width: 176 px (a vanilla chest) or 230 px.
  They never grow with the window; vanilla GUI scale sizes them. The content edge is 7 px, the
  vanilla inventory inset, so a docked 9-slot inventory lines up exactly. Controls are 18 px
  tall, the same as a slot, with a 4 px gap. The title sits at y = 6 and the body starts at
  y = 18. Rows fill the body top-down. A row that would reach the footer throws, so a layout
  that does not fit fails in tests and tours rather than overlapping. `fit` splits a row into
  cells sized to their labels and shares the spare width. `alignRight` places footer actions.
- **Footer.** The bottom row holds the device status (lamp and text) on the left and the actions
  on the right, with the confirming action last (for example **Cancel**, **Apply**).
- **Look (`Panels`).** A light, nine-sliced panel with a bevel, sunken insets and 18 px slots,
  drawn from generated sprites in `textures/gui/sprites` (`tools/art/generate_art.py`; nothing
  is copied from Minecraft). Text uses dark colors for the light face: normal `0x404040`,
  muted, good (green), warning (amber) and error (red).
- **Choices (`ChoiceButton`).** A row of options, such as gateway presets or modes, shows the
  selected one pressed in copper with white text. Before, the selected option was a disabled
  button and looked unavailable.
- **Toggles (`ToggleButton`).** An on/off setting is a ticked box with its label, not a button
  reading "View: on". The tick is a shape, so the state does not depend on color, and narration
  says the label and state.
- **Text.** Explanations move into tooltips. A panel shows only state that the player needs to
  see, such as "Read-only: you need Manage at this gateway".
- **Tabs** (H2) sit on the panel's top edge, inside the panel's bounds, so the fit checks still
  apply.

Screens move to the kit one group at a time (H1 Gateway, H2 Terminal, H3 the rest). Until a
screen moves, it keeps its dark style. `StatusStrip.draw` keeps the dark colors and
`StatusStrip.drawOnPanel` adds the light ones.

## Alternatives

- **Keep the dark flat style with a layout helper.** This would fix the spacing but not the
  mismatch with vanilla widgets and the player inventory. HD3 chose vanilla/AE2 panels.
- **Panels that scale to the window.** This is the old behavior that made scale-4 screens fill
  the window. Vanilla GUI scale already gives players the size control they expect.
- **A widget library (for example owo-lib).** This would make players install another mod for a
  small amount of geometry. The mod runs with NeoForge alone today, and optional integrations
  must not become mandatory (SPEC R10).

## Consequences

- Screens with too many controls for a 230 px panel must split them into tabs or move rarely
  used settings behind a button, instead of growing.
- The minimum supported window, 427×240 at scale 4, bounds panel height to 240 px including
  tabs.
- Client tours find toggles as `ToggleButton` with a bare label rather than a "Name: on" button.
- `PanelLayoutTest` covers row stacking, footer reservation, vanilla edge alignment, and `fit`
  sharing and shrinking. The presentation tour checks each ported screen at scales 1–4.

## Amendment — H3a: Interface and Drive screens (2026-10-26)

The Storage, Buffer and Signal Interface screens and the Drive screen moved to the kit.

- **One choice per setting.** Every setting with a few named values is a row of `ChoiceButton`s
  showing all of them, instead of a button that cycles. That covers the Interface's resource and
  access, the Buffer's mode and filter, and the Signal's condition, comparison and output. For
  the Buffer, the `MODE` and `FILTER` commands gained a direct form: `value` n picks choice n − 1,
  and 0 still cycles.
- **Views, not tabs, inside a screen.** Settings/Filter (Storage) and Settings/Inventory (Buffer)
  are a pair of choices at the top that swap the rows below them, in the same panel size.
- **The Interface's mode stays a captioned row at the bottom ("Mode: Storage Buffer Signal").**
  The H3 plan said "mode buttons become tabs at the top". They were not made tabs because
  switching mode changes the block, and the switch is refused while items are in transit or an
  upgrade is installed. A tab would suggest a free change of view.
- **Instruction lines removed.** "Apply saves your changes…", the Drive's "Click a bay…", and the
  partition note moved into tooltips. The message row shows only results and problems.
- **Inventory positions.** The Buffer and Drive menus place the player inventory centred in the
  230 px panel (`INVENTORY_X = 35`). The Drive panel is 238 px tall, within the 240 px limit.

## Amendment — H3b: Access and Recovery screens (2026-10-26)

- **Network Access is a list, then the selected player's controls.** The old screen put a role
  button, Revoke and Owner on every row, which needed a 300 px panel. Now each row is one line
  with the player's name and current role. Under the list sit the selected player's role
  choices (Guest, Member, Builder, Admin), or with Advanced the seven permission letters as
  toggles, then Revoke, Owner and the Roles/Advanced switch. Paging and Recovery… are in the
  footer. A blocked grant still shows `*` after its letter, so it does not depend on color.
- **Transfer Recovery** uses the same list style. The selected record's safe next step is shown
  in words under the list, and only the applicable actions are enabled. The "Select a record"
  instruction is gone. Both panels are 230 px wide.

## Amendment — H3c: Program Controller, Label Tool and Guide (2026-10-26)

- **The Program Controller stays 344 px wide.** The editor needs the width for readable lines.
  At 344 px it still fits the 427 px minimum window at scale 4, like the Terminal with its
  crafting panel open. The code editor keeps its dark field, as a text area should. The panel,
  log area and preview are on the kit.
- **Templates are chosen by name.** The Template button used to replace the draft with the next
  template on each click. It now opens the same chooser as Insert, on a Templates category that
  lists all templates in teaching order. Choosing one replaces the draft, and Ctrl+Z undoes it.
- The side buttons are grouped: saving and deploying, running, then writing.
- The Label Tool screen and the Guide's picture and recipe panels use the kit.

H3 is complete: every screen the mod opens uses the kit.

## Amendment — Q55: Terminal split and shared selection colours (2026-10-26)

Ergonomics plan Q55 (M53, M54). No behaviour or layout change.

- **The Terminal is a shell plus three parts.** `TerminalScreen` keeps the tabs, search row,
  status lamp, mode switches, the terminal command packet and the verification API that the
  client tours use. `TerminalStorageView` draws the Items/Fluids/Energy grid, its scrollbar and
  the info row; `TerminalCraftingPanel` the crafting panel; `TerminalJobsView` the Jobs view
  with the plan and job steps. Each part adds its widgets in `init` in the old order, so focus
  order is unchanged, and the shell asks the parts for drawing, tooltips, clicks, scrolling and
  keys in the order the single class used. New terminal features (lane T, Q3, Q7) go into the
  part they belong to, or a new part, rather than the shell.
- **Selection and row colours live in `Panels`.** `selected` (copper fill), `selectedEdge`,
  `focusEdge`, `hovered` and `row` replace the colours copied into the Terminal, Drive, Network
  Access, Recovery and Buffer screens. The Terminal's other marks are kit calls too: `veil` for a
  craftable with no stock, `marker`, `meter`, `thumb`/`lightThumb`, `shortRow`, `lamp`, and the
  `LIST_*` and `FIELD_*` text colours. No terminal class calls `fill` or holds a colour value.
- The job list, plan and job steps keep shadowed light text on the grey inset (`LIST_*`), as H2
  shipped them; a restyle would be a separate, visible change.

## Amendment — the last fills on the kit (2026-10-30, ergonomics plan Q105)

Every fill and colour in `platform/client` outside `ui/` now goes through `Panels`, except the
program editor's code field (H3c). The status strip draws `Panels.lamp`, so every device screen
shows the same framed lamp as the Terminal. New kit parts: `track`, a two-colour `meter`,
`disabled`, `badge`, `picker`, `pickerCell` and `lightFocusEdge`, plus named colours for the job
toast and the in-world label overlay.
