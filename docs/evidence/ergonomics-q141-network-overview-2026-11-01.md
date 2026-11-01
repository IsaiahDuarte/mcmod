# Q141 — Network Controller screen (2026-11-01)

From playthrough note 1: "optional" was unexplained and the screen looked bare.

## Change

- **What the network has:** under an "On this network" heading, a row of five tiles. Each tile
  shows the item for that kind of device (Storage Drive, Storage Terminal, Importer, Crafter,
  Program Controller) in a well, with its count beside it. A count of 0 is greyed out, and
  hovering a tile shows the old text ("Storage blocks: 4"). This replaces the two text columns.
- **Checklist:** split under two headings, so "Optional:" is no longer needed.
  - **Getting started:** storage and a terminal. These show a cross until done.
  - **Next steps (when you want them):** machines and Crafters. These show a circle until done.
  - The to-do texts now start with what to do ("Connect machines: …", "Autocrafting: …").
- **Unchanged:** the energy section, the Access… button and the offline states.

## Tests

`runPresentationClient` passed. Screenshots `overview-scale-4.png` and `overview-scale-1.png`
were looked at: tiles, counts and both headings fit, and nothing overlaps at either scale.
