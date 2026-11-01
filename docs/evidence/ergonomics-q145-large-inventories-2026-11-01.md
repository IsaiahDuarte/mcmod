# Q145 — Inventories over 64 slots (2026-11-01)

Found while doing Q136. A port read and wrote at most 64 item slots, so most of a big modded chest
was out of reach.

## Change

- **`ConnectorSettings`:**
  - `wholeItemHandler()` is true for the default item window (slot 0, count 64).
  - `WHOLE_ITEM_SLOTS` is 1024.
- **`ItemHandlerPort.whole`:** a port over every slot. It re-measures `getSlots()` on each call
  and is capped at 1024.
- **`WorldConnectors`:**
  - A non-indexed binding with the default item window uses the whole port.
  - Observation, `stock` and stock matching span 1024 slots; the page loops go up to 64 pages of
    16.
  - `viewable` allows the larger own-face range, and no longer reports RESERVED for other faces
    of a whole binding.
- **Unchanged:** indexed `ExternalBackingProvider` partitions and narrowed windows keep their exact
  slots.

## Tests

- **`ModdedProviderGameTests`:**
  - The Importer takes 64 dirt from slot 97 of a 100-slot inventory.
  - With slots 0–63 full of cobblestone, a keep-8 Exporter places exactly 8 iron beyond them;
    cobblestone is untouched and storage loses exactly 8.
- **`PlatformResourceTest.wholeItemPortReachesPastSlotSixtyFourAndFollowsResizes`:**
  - Extraction from slot 97 works, and insertion lands in slot 64.
  - A handler shrunk to 64 full slots accepts nothing.
  - A 2000-slot page walk ends at 1024.
- **Full GameTest run:** 523 of 523 pass.
