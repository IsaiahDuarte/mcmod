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
