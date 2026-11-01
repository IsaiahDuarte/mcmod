# Q32 — Portable storage (2026-11-01)

QD8 resolved yes: one cell, owner only. ADR 0005 amended.

## Change

- **`PortableCells.with`:** reaches a cell for one take or store. It attaches the cell at a
  per-player `factorycore:portable` location, makes it available, runs the move, then detaches
  and writes back the handle and summary. The routing predicate admits that location only
  during the move.
- **`PortableStorageItem`:**
  - The first user becomes the owner; it refuses every other player.
  - It has the Wireless Terminal's battery, capability and charging, and a charge bar.
- **`PortableStorageMenu`:**
  - Slot 0 holds one item cell, kept in the item's `container` component.
  - Slots 1–45 show a page of the catalog, at most 1,024 types read every 20 ticks, most
    stored first. Their counts are not capped at a stack.
  - Click takes (right: half), Shift-click takes into the inventory, a click with a stack
    stores (right: one), Shift-click from the inventory stores.
  - Each move costs 64 FE, and nothing moves without it.
  - The open Portable Storage cannot be picked up or swapped out of its hand.
- **`PortableStorageScreen`:** short counts, exact amounts in the tooltip, and page arrows.
- **Recipe:** starter stage, 5 iron, a Storage Terminal, a Storage Drive and 2 redstone. Art
  comes from `generate_art.py`.

## Tests

- **`PortableStorageGameTests.opensTakesStoresAndSpendsCharge`:**
  - Opening:
    - It opens with no network, and its first user becomes the owner.
    - A fresh starter cell goes into its slot.
  - Storing and charge:
    - Shift-clicking 20 cobblestone stores 20 and costs 64 FE.
    - Afterwards the cell is detached.
  - Moving items:
    - The page shows 20.
    - A click takes 20 onto the cursor, and a right click stores one.
    - A click stores the rest, and Shift-click takes them into the inventory.
    - Five moves cost five charges.
  - Display and refusals:
    - 192 stone shows as 192 on one slot.
    - With 10 FE a store moves nothing.
    - The open Portable Storage cannot be picked up.
- **`PortableStorageGameTests.anotherPlayerIsRefused`:** someone else's Portable Storage does not
  open; its owner's does.
- **Full GameTest run:** 517 of 517 pass.
- **Presentation client:** `runPresentationClient` captures `portable-storage-scale-1.png` and
  `portable-storage-scale-4.png`.
  - Fit checks pass at scales 1–4.
  - 1,024 cobblestone shows as "1.0k", with 51 iron and 34 logs.
  - The first run showed 64 instead of 1,024, because `SimpleContainer` capped the shown stack.
    The shown container is now uncapped, and the 192-stone assertion above covers it.
