# Q142 — Fuel Generator screen (2026-11-01)

From playthrough note 2: the generator had no screen. An empty-handed use printed one long
status line above the hotbar.

## Change

- **Opening:** an empty-handed use opens `FuelGeneratorScreen`, which uses the panel kit
  (`FuelGeneratorMenu`, registered as `factorycore:fuel_generator`). Its **?** button links to the
  generator's guide entry. The old `message.factorycore.generator_status` chat line is gone.
- **What it shows:**
  - The stored FE out of 100,000, with a meter.
  - **Giving out:** the FE handed to neighbours in the last tick, with the 80 FE/t cap.
  - The status in words: Burning (green), or Idle / Paused with the reason (amber). These reuse
    the existing `status.factorycore.generator.*` texts. The container-full text no longer tells
    you to sneak-use, because the screen can now take the container out.
  - **Burn left:** the seconds left on the current fuel item.
  - The **Fuel** slot, a burn bar, and the **Container** slot. The Container slot is take-only,
    and both slots show a help tooltip when empty.
- **Inventory moves:** shift-clicking fuel moves it into the fuel slot, and anything else stays in
  your inventory.
- **Unchanged:** sneak-using with an empty hand still takes items out, and using it while holding
  fuel still inserts it.
- **Data:** values above 65,535 are synced as two 16-bit halves. The output comes from the new
  `FuelGenerator.recentOutput(now)`, which counts this tick or the one before, because a screen
  syncs before the level ticks.

## Tests

- **Unit:** `FuelGeneratorTest` checks `recentOutput`:
  - It reports what tick 11 gave out, both during tick 11 and on tick 12.
  - It reports 0 one tick later, and 0 on a new generator. This last check guards the
    `Long.MIN_VALUE` start, which overflowed in the first version.
- **GameTest:** `screenMenuShowsBurnAndMovesFuel` checks the following.
  - **Shift-click:** cobblestone stays in the inventory, and coal moves to the fuel slot.
  - **Container slot:** it refuses placement.
  - **Before sync:** the client menu reads loading until it gets data.
  - **After syncing the data slots:** it shows burning coal (1,600 ticks total, some left) and
    stored FE equal to the server's.
  - **Large values and status:** 70,000 FE syncs whole, and an empty slot reads no_fuel.
- **Presentation tour:** `generatorScreen` places a generator on the Energy Bank with 12 coal
  and opens it with a real empty-handed use. It then waits until the screen shows Burning and
  20 FE/t going out to the bank, with 11 coal left. The evidence line was
  `0 FE stored, 20 FE/t out, burn 1566/1600, fuel 11 minecraft:coal`.
- **Screenshots looked at:** `generator-scale-4.png` and `-scale-1.png`. Every line fits, and the
  slot row and inventory are aligned. A first layout cut the burn text off ("Burn l"), so it was
  moved to its own line.
