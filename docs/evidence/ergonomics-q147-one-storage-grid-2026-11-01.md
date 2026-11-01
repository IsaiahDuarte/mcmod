# Q147 — Items, fluids and energy in one grid (2026-11-01)

From playthrough note 4 ("a unified view of fluids, items, energy, jobs that are inline in the
terminal"), split from Q138. The plan left open whether the separate tabs stay. The user decided
mid-slice (2026-11-01): "are we removing the items fluids energy tabs? we should."

## Change

- **Tabs:** the terminal's tabs are now **Storage**, **Jobs** and **Patterns**. Items, Fluids and
  Energy are gone as tabs.
- **Kind tool:** a button at the end of the search row, before the lamp, cycles **All kinds** →
  Items → Fluids → Energy. A terminal opens on All kinds.
- **Server search** (`WorldStorageSearch.start(..., List<ResourceKind> kinds, ...)`): one search
  observes each kind's catalog in turn, ITEM then FLUID then ENERGY, into one sorted index. Ghost
  rows and paging accept any listed kind, and keys already sort by kind first. Capacity counts
  only the first kind's cells, so "items stored" stays in item units. The per-kind source map
  starts again for each kind, because a provider seen under one kind is a different endpoint
  under the next; its byte charge stays counted against the search budget.
- **Menu:** `KIND` accepts `KIND_ALL` (3), and the view reports kind 3. Each row carries its own
  kind (`TerminalPackets.Row.kind`, one byte on the wire). Commands check the named row:
  - take, half, swap, Shift+scroll and Ctrl+scroll name only item rows (a fluid row moves
    nothing);
  - Fill needs a fluid row;
  - deposits and Charge/Drain are unchanged.
- **Client rules per row:**
  - Item rows behave as in the old Items tab: take, store, craft ghosts, the job strip and
    Shift-drag.
  - Selecting a fluid row shows **Fill** and the mB/B toggle; selecting the energy row shows
    **Charge** and **Drain**.
  - In All kinds, Ctrl+Shift-click on an inventory item empties its fluid or drains its energy
    into storage. Plain Shift-click stores the item itself, as before.
  - Icons, counts, tooltips and recipe-viewer lookups (R/U) follow each row's kind.
  - EMI's "what can I craft" counts use item rows only.
- **Removed duplicate keys:** older `gui.factorycore.terminal.kind.*` entries were defined twice;
  the old copies are gone.

## Tests

- **Unit** `TerminalPacketsTest.allKindsViewRowsCarryTheirKind`: an All view with item, fluid and
  energy rows round-trips; a row kind of 3 or -1 is refused.
- **GameTest** `allKindsViewListsItemsFluidsAndEnergyAndActsPerRow`: an item cell, a fluid cell
  and an Energy Bank on one network, with KIND 3:
  - the view lists all three kinds with their own amounts;
  - a take on the water row moves nothing;
  - Fill on it fills a bucket with exactly 1,000 mB;
  - a take on the stone row puts all 5 on the cursor.
- **Presentation tour** (passed, headless display): the All kinds grid at GUI scales 4 and 1 lists
  item and fluid rows. Selecting a fluid row shows Fill; right-clicking an item row hides Fill
  and takes half a stack, which the tour then puts back.
- **Tour start kind:** the older tours were written for the Items tab. They now open terminals on
  Items through a verification hook (`TerminalScreen.startKindForTest(0)`) and switch kinds with
  `selectKind`, where they used to press the Fluids, Energy and Items tabs.
- `runTerminalRestartClient`: the first JVM's tour passed, including its fluid and energy
  captures. The restart JVM failed the known split-root flake (Q151).
- `python3 scripts/verify.py` passes. One earlier run failed only
  `gridShortcutsMoveOneAndSwapIntoTheHotbar`, the known intermittent test (Q129); the rerun passed.

## Screenshots looked at

- `terminal-all-kinds-scale-4.png`: Storage, Jobs and Patterns tabs; **All kinds** beside the
  search box; diamonds, emeralds, gold, iron, lava (2.0k), planks, pork, cobblestone and water
  (5.0k) in one grid, sorted by name. The tool row is unchanged.
- `terminal-all-kinds-scale-1.png`: the same at scale 1; the kind tool's label fits.

Not checked in the user's pack yet.
