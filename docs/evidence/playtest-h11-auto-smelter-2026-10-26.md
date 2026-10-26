# H11 — Auto smelter in one step

Scope: playtest plan H11 (K7).

Implemented:

- Factory Guide, now 17 pages:
  - Page 1 tells players to use the Controller to see what the network still needs (the H10
    overview).
  - Page 6 ("Machines") pictures the Importer and Exporter.
  - The new page 7, "Auto smelting", shows the build (Exporter on top with raw ores, Exporter on a
    side with coal and Keep 8, Importer underneath) with Exporter, furnace and Importer pictures
    and the Exporter recipe.
  - The Programs page mentions the Label Tool and pictures it.
  - Later pages are renumbered.
- An "Auto smelter" program template (the SFM route). Every furnace labelled "furnace" through a
  cable port gets `item("minecraft:raw_*") except item("minecraft:raw_*_block")` through the top,
  coal topped up to 8 on the north side, and `everything` from the bottom back into storage.
- The player guide mentions the template.

Checks:

- `PortGameTests.threePortsMakeAFurnaceAnAutoSmelter` (H6) builds the guide's three-port smelter
  from a fresh network and smelts raw iron into storage, and coal is only burned or stocked.
- `CablePortGameTests.theAutoSmelterTemplateSmeltsThroughALabelledFurnace` (new): the Label Tool
  labels the furnace, and the template deploys unchanged. Both raw iron come back as ingots, a
  raw iron block in storage is never sent to the furnace, and coal is kept on the side with one
  burned.
- `GuidePagesTest` (17 pages, each at most 256 characters, topics include Importer, Exporter and
  Label Tool), `GuideGameTests` (one picture list per page), `ProgramTemplatesTest` (12 templates
  compile with named rules) and `FriendlyV1PinTest` pass.
- `runGameTestServer`: 413 of 413. The presentation tour captures `guide-smelting.png` (page 8 of
  17, "7. Auto smelting"), which I inspected.
