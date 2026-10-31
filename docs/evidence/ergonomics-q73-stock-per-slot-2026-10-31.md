# Q73 — Stock per slot (2026-10-31)

**Policy.** `BufferPolicy` gains `slotTargets`, one goal per filter term (−1 means the policy
goal); all −1 normalizes to none. `perSlot()` holds for an allow list with goals; `goal(i)`
resolves a slot. `ResourceFilter.firstMatch` names the first term a key matches, ignoring the mode,
so every resident item counts toward exactly one slot.

**Runtime.** `TransferEndpoints.BufferPort` caps insertion at the goal of the key's own slot, counting
only what that slot matches. `WorldBufferImports.room` does the same per key while planning pulls;
a satisfied slot skips its rows instead of ending the pass, so other slots still fill.

**Persistence.** Policy schema 3 adds the `Slots` int array; buffer table 5 holds policy 2 or 3.
Older tables keep their exact pairing, and "future" checks in the existing tests moved to table 6
and command 18.

**Screen.** `BufferSettingsPackets` adds `SLOT_GOAL` (16) and `SLOT_DEFAULT` (17) and a `goals` list
in the view (protocol 5). In Stock with **Only these**, the selected slot's goal box and **Set**
sit at the right of the description row, and each slot with a goal shows it as a corner badge.

## Checks

- `BufferPolicyCodecTest.slotGoalsRoundTripAsSchemaThreeAndMustMatchTheSlots`: round trip, goal
  resolution, rate change keeps goals, wrong count / over 576 / missing array reject, table 5
  round trip with a mixed pair, table 4 refuses slot goals.
- `ResourceFilterTest.firstMatchNamesTheFirstSlotAResourceMatchesIgnoringTheMode`.
- `BufferExportGameTests.stockPerSlotKeepsSixtyFourCoalAndSixteenIronThroughAReload`: one buffer
  stocks exactly 64 coal and 16 iron from a cell, holds for 30 ticks, the policy survives a
  `DeviceSavedData` save/load, and taking 10 coal refills only coal.
- `BufferSettingsGameTests.slotGoalsFollowTheirSlotsThroughEdits`: set iron's goal, remove coal and
  the goal moves with iron, then clear it.
- 477 GameTests passed twice.
- Presentation client `buffer-slot-goal-scale-1.png` / `-scale-4.png`: Stock, Only these, badges 64
  and 16 on two slots, layout fits at scales 1–4.
- `python3 scripts/verify.py`: PASS.
