# Q144 — Port and cable looks (2026-11-01)

From playthrough notes 9, 10, 18 and 19.

## What changed

| Note | Finding | Change |
| --- | --- | --- |
| n18 | "Why do they turn white when connected?" Labelling a machine turns the cable beside it into a cable port. The port draws the cable's core and arm models, whose stripe has tint index 0, but only the cable block had a colour handler, so the port's stripe rendered untinted (white). | `TerminalClient.blockColors` registers the cable port too; a port is uncoloured, so its stripe takes the uncoloured cyan. |
| n18 | The cable port's plate was copper (orange). | `cable_port_plate.png`: a steel plate with four signal pins. `cable_port.json` and `cable_port_plate.json` use it. |
| n19 | The signal line ran along row 8 of the 4-row band (rows 6–9), off the centre line, and both the cable texture and the tinted overlay (0.02 larger) drew it. | The stripe is the two middle rows (7, 8), drawn only by the overlay. |
| n9, n20 | Interface, Importer, Exporter (and Constructor, Destructor, Tunnel, which share the model) had a 13-deep body with a copper socket frame standing 3 px out of the front. | `storage_connector.json` is a full cube; the front uses the flush `connector_front.png` (steel ring, signal pin). `PortBlock` and `InterfaceBlock` drop their socket shapes, so the interaction shape is the full block and matches the model. |
| n10 | The Crafter's textures were off-centre: the front's inset was 9 rows tall at rows 3–11 with a 5-pixel port at x 6–10; the side chip and top grid sat one pixel off centre. | Front: inset 2–13 × 3–12, the same steel ring as the port fronts at 5–10, amber "in" and cyan "out" chevrons. Side: inset 2–13, 4×4 chip at 6–9, pins at 6 and 9. Top: inset 2–13, 3×3 grid spanning 4–11. The Crafting Unit shares the side and top. |

All textures come from `tools/art/generate_art.py` (`--check` passes); the reference sheet is
regenerated.

## n9: small ports on several blocks

n9 asks for "the importer/exporter [to] be small and connected to multiple blocks within the same
block". Checked what exists:

- **Cable ports (ADR 0065)** are small: the cable stays a cable with a plate on one face, and
  every cable block beside a machine can be one. They reach a machine **for programs**, by label.
- They do **not** import or export on their own. Automatic pull and push still needs the
  full-block Importer or Exporter, one machine per block.

So cable ports cover the program path but not the AE2 import/export-bus path. To make them easier
to find, the Importer and Exporter tooltips now end with "For programs, no block is needed: label
a machine with the Label Tool and the cable beside it becomes a small cable port." The rest of n9
(Importer and Exporter as cable attachments, several per cable block) is a topology and model
change, recorded as Q150 behind gate QD26.

## Tests

- **Presentation tour** (`runPresentationClient`), new check `checkTintedBlocksHaveColours`: every
  Factory Core block state whose baked model has a tinted quad must get a colour other than -1 from
  `BlockColors`. It covered 53,760 tinted quads. Without the fix the cable port's states fail it
  (no handler for `factorycore:cable_port`).
- **New captures:** `orientation-fronts.png` (Storage Interface, Importer and Crafter fronts in two
  facings), `ports-close.png` (Importer and Exporter on chests, centred cable stripe) and
  `cable-ports-close.png` (the three ports from the row-labelling scene, steel plates, cyan
  stripes). The tour passed on a headless KWin display (`kwin_wayland --virtual`, `DISPLAY=:2`).
- `python3 scripts/verify.py` passes (GameTests included; the full-block shapes changed no test).

## Screenshots looked at

- `ports-close.png`: Importer and Exporter are full cubes with no copper; the cable between
  them shows a centred cyan stripe with segment notches.
- `orientation-fronts.png`: north and east fronts show the steel ring flush with the casing;
  the Crafter shows the ring between the two chevrons, its side chip centred.
- `cable-ports-close.png`: each plate is steel with cyan corner pins against its furnace; the
  stripes are cyan, not white.

Not checked in the user's pack yet.
