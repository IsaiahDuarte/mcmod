# Q30 — Facades (2026-11-01)

QD6 resolved yes, after Q29. SPEC R02 and ADR 0010 amended.

## Change

- **Block:** `NetworkCableBlock` gains a `facade` state, a full shape while it is set, and an
  entity (`FacadeEntity`) only while it is set.
- **Item and recipe:** `FacadeItem` carries the `facade_block` component. `FacadeRecipe` turns a
  cable and an allowed block into 4 facades.
- **Using it:** a facade used on a placed cable goes on. Breaking the cable takes it off first. An
  outright removal drops both.
- **Rendering:** `FacadeRenderer` draws the block on the cable and the item as its block. The item
  model is `builtin/entity`, now allowed by `AssetReferencesTest`.
- **Creative tab:** it lists stone brick, oak plank and smooth stone facades. A blank facade is
  hidden. `creativeTabShowsEveryItem` now checks uniqueness per stack (item plus components).
- **Special recipes:** `RecipeGameTests` and `RecipeProgressionTest` list the two special recipes,
  `cable_dye` and `facade`. The facade recipe counts as the facade's one source.

## Tests

- **`FacadeGameTests.aFacadeHidesACableWithoutChangingLinksAndComesOffFirst`:**
  - On a red cable in a VALID network, a stone brick facade goes on and uses one item.
  - Only the `facade` state changes, and the shape is a full block. A second facade does not go
    on.
  - The network stays VALID with the same id.
  - A survival player's break leaves the identical cable with no entity, drops the facade, and
    keeps the same network.
  - An outright removal of a faced cable drops the red cable and the facade.
- **`FacadeGameTests.facadesAreCraftedFromACableAndAPlainBlockAndSaveTheirBlock`:**
  - A cable and stone bricks make four "Stone Bricks Facade".
  - A chest, glass, two cables, a second block, or a Factory Core block do not match.
  - The saved block round-trips. An unknown block, or schema 2, loads as no facade.
- **Full GameTest run:** 513 of 513 pass.
- **Presentation client:** `runPresentationClient` captures `facade-scale-1.png` and
  `facade-scale-4.png`, checked by eye.
  - The faced cable in the middle of the red run shows as stone bricks, with the red arms running
    into it.
  - The hotbar shows stone brick (×2) and oak plank facades drawn as their blocks.
  - **Retry:** the first run after a PC restart failed its window-size check (1920x1050, the
    window was maximized). A rerun passed unchanged.
