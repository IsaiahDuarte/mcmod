# Q28 — Storage Monitor (2026-10-31)

## Change

- **Block:** `platform/monitor`.
  - `MonitorBlock` is a horizontal `NetworkedBlock`, so the wire reader finds it.
  - `MonitorEntity` is a `WiredMember` with the TERMINAL role.
- **Use:**
  - With an item: chooses what it shows (Edit).
  - With an empty hand: takes a stack (Withdraw). If the take reports `SHORTAGE`, it submits a
    `WorldCraftingJobs` job for one stack (Craft, checked by the job submission).
  - With the shown item: stores the held stack, or, on a second use within 10 ticks, every
    matching main-inventory stack (Deposit).
  - Sneak-use with an empty hand: locks or unlocks what it shows (Edit).
- **Moves:** every move uses `WorldStorageRoutes.priority` with a player-inventory endpoint, the
  same path as terminal moves and wireless Pick Block.
- **Reading:** `WorldMonitors` reads each loaded monitor's count at most once per 20 ticks.
  - It walks the catalog under the grants of the player who chose the item, and needs View.
  - Bounds: at most 4,096 tracked monitors and 640 catalog pages per reading.
  - The count saturates at `Long.MAX_VALUE`.
  - A failed reading shows "—".
- **Saved data:** schema 1 under `FactoryCoreMonitor`, holding the owner, the item and the lock.
  - Malformed or future data loads as an empty monitor. A monitor holds no resources.
  - The count is not saved. It travels only in the update packet.
- **Client:** `MonitorRenderer` draws the item and the short count (`CountText.compact`) at full
  brightness on the front face.
- **Assets and recipe:**
  - Art: `storage_monitor_front` and `storage_monitor_side` from `generate_art.py`.
  - Recipe: starter stage, 5 iron, 1 glass block, 2 copper, 1 redstone.
  - `RecipeProgressionTest` and `RecipeGameTests` now count 29 recipes.

## Tests

- **`StorageMonitorGameTests`:**
  - **`showTakeStoreAndStoreAll`:**
    - Choosing an item moves nothing, and the monitor reads 100.
    - Using it with the shown item stores the held 5.
    - An empty hand takes 64 into the selected slot, and it reads 41.
    - Two quick uses store the held 10 and then the other cobblestone stacks (7 and 3), and leave
      dirt alone.
  - **`refusalsKeepEverythingWhereItWas`:**
    - A player with no grants cannot choose, lock or take.
    - A locked monitor keeps its item.
    - With the starter cell full, the held 16 stay in hand.
  - **`anEmptyHandAtZeroCraftsAStack`:** with no planks stored, 16 logs and a log-to-planks
    pattern, an empty-hand use crafts 64 planks into storage. The monitor then reads 64.
- **Full GameTest run:** 509 of 509 pass.
- **Presentation client:** `runPresentationClient` captures `monitor-close.png`. The monitor at
  the end of the status row shows cobblestone and "1.2k" for 1,234 stored, checked by eye.
