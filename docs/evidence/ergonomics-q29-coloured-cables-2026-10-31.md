# Q29 — Coloured cables (2026-10-31)

QD5 resolved yes: 16 dyes plus uncoloured. SPEC R02 and ADR 0010 amended.

## Change

- **Colour state:** `CableColor` is NONE plus the 16 dyes. It is the `color` block state on
  `NetworkCableBlock`. `joins` holds when either colour is NONE or both are the same.
- **Links:** `WorldWireReader` drops a cable's port toward a loaded cable whose colour does not
  join. Arms use the same rule (`linksTo(self, neighbour, side)`), and so does the Label Tool's
  row walk.
- **In the world:** a dye on a placed cable recolours it and uses one dye (none in creative). A
  wet sponge clears it and is kept.
- **Crafting:** the special recipe `CableDyeRecipe` (`factorycore:cable_dye`) takes 1 to 8
  cables of any colour plus one dye, or one water bucket. The water bucket clears them and
  comes back as an empty bucket.
- **Items:** the colour is kept in vanilla `block_state`.
  - Placement reads it.
  - The loot table copies it back only when the cable is coloured, so plain cable still stacks.
  - Pick Block gives the coloured item.
  - The item is named "Red Network Cable" and so on.
- **Rendering:** the stripe is a greyscale overlay (`network_cable_stripe`, from
  `generate_art.py`), a cutout element with tint index 0.
  - Block and item colour handlers tint it with the dye's colour.
  - An uncoloured cable is tinted with the art's SIGNAL colour, so it looks as before.

## Tests

- **`CableColorGameTests.colouredRunsStaySeparateAndRecolouringRebuilds`:**
  - A red run and a blue run side by side, each with its own controller, are two VALID networks
    with different ids. No arm is drawn between them.
  - An uncoloured cable replacing a middle red one joins both roots, and both runs leave VALID.
  - A red dye used on it (survival) recolours it, uses one dye and makes two networks again.
  - A wet sponge clears it, is kept, and joins them again.
  - At each step the cable's arms match the wire reader's ports.
- **`CableColorGameTests.theGridRecoloursCablesAndBreakingKeepsTheColour`:**
  - Three cables (one of them blue) and a red dye make three red cables.
  - A water bucket makes plain cable that stacks with crafted cable, and returns a bucket.
  - Two dyes, a dye with a bucket, a stray item, or a dye alone do not match.
  - A broken green cable drops "Green Network Cable". An uncoloured one drops plain cable.
- **`RecipeGameTests`:** the one special recipe must be `cable_dye`. The 29 ordinary recipes are
  checked as before.
- **`WiringConsistencyGameTests`:** unchanged. It still passes for every networked block.
- **Full GameTest run:** 511 of 511 pass.
- **Presentation client:** `runPresentationClient` captures `cables-coloured.png`, checked by eye.
  - Red, blue and plain runs side by side.
  - The red run has no arms toward the blue run.
  - The plain run joins the blue run beside it.
  - The stripes are tinted, with no z-fighting.

## Not done

- **Cable anchor (M93):** split out as Q130.
