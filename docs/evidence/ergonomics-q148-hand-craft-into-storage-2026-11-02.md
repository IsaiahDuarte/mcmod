# Q148 — Hand crafting into storage (2026-11-02)

From playthrough note 16: "Crafting regularly + encoding should be a bit more explicit. but it
should also drop the output back into the storage system". Split from Q139.

## Change

- **Labelled action:** in the encoder's Crafting mode the craft button reads **Hand craft**; **×64**
  and the C / Shift+C keys stay. Their tooltips say the result goes into storage.
- **Into storage:** the client sends CRAFT with `source = INTO_STORAGE` (1). After the craft (and
  any gathering), `TerminalMenu.storeOutput` queues deposit steps for exactly crafted × output
  count of the output key, from the inventory slots holding it. They run one stack at a time on
  the same guarded deposit route as Shift-click (`Step`, `priorityGuarded`, uncertain-transfer
  checks). Other stacks and recipe remainders are not touched.
- **Results:** `GridStatus.STORED` ("Crafted ×N into storage") when everything was taken;
  `GridStatus.KEPT` ("…storage was full, the rest is in your inventory") when storage took none
  or only part. Delivered amounts are subtracted as each route settles, so a partial store is
  counted exactly.
- **Compatibility:** CRAFT with `source = 0` still leaves the output in the inventory, which the
  older GameTests use.

## Fixed on the way

The JEI and EMI tours still used labels renamed by Q137 and Q139 ("J" → **Sync**, "Inputs" /
"Outputs" → **Processing** plus a click on the first output cell, "« Crafting" → **« Encoder**,
"Table" → **Crafting**). They had not been run since those slices. Both pass again.

## Tests

- **GameTests** in `TerminalCraftingGameTests` (two logs and three planks in the inventory, craft
  ×2 into storage):
  - `handCraftStoresExactlyTheCraftedOutput`: STORED; 8 planks stored; the 3 planks already in
    the inventory stay.
  - `aPartialHandCraftStoresWhatWasCrafted`: asks for 3 crafts with only 2 logs; 2 crafted, 8
    stored, STORED.
  - `handCraftKeepsWhatAFullStorageRefuses`: a full cell; KEPT; 0 stored and 11 planks in the
    inventory.
  - `handCraftStoresWhatFitsAndKeepsTheRest`: 5 units free; KEPT; exactly 5 stored and 6 kept.
- **Client tours:** the presentation, terminal and JEI tours press **Hand craft** and expect
  STORED with the output gone from the inventory. This replaces their old "output in the
  inventory" checks, because that requirement changed. The presentation tour then takes the
  stored planks back out, so its later Jobs scene starts without planks.
- **Runs:** `runPresentationClient`, `runJeiClient` and `runEmiClient` pass on the headless display.
  `python3 scripts/verify.py` passes (530 GameTests).
