# Q122 — Game vocabulary, pinned deploys and sentence templates (2026-10-31)

ADR 0068, slice Q122.

## Change

- **`GameVocabulary`** (`platform/program`): every registered item and fluid, the item tags
  loaded in the game, and English item names that differ from the ID path. Untranslated and
  shared names are left out. It is cached and rebuilt on `TagsUpdatedEvent`. Any installed
  mod's items work.
- **Game compiles:** `WorldPrograms.compile` (deploy, preview) and `ProgramMenu` (save, Check)
  compile with it.
- **Pinned deploys:** `FriendlyCompiler.canonical` is the sentence program printed with no
  vocabulary. `ProgramControllerEntity` saves it as `Canonical` and reloads from it, while the
  editor keeps the player's own words. Pinning happens on the server, so packets don't change.
- **Generated vocabulary:** `runData` writes
  `src/generated/resources/data/factorycore/program_vocabulary.txt`, with 1,369 items, 393 tags
  (NeoForge common and vanilla, as declared in code) and the English names. This mod's names
  are read from its own `en_us.json`. `checkGeneratedResources` keeps the file fresh.
  `Vocabulary.read/write` is plain text, so the offline helper needs no game.
- **Templates:** all 12 are now sentences.

## Tests

- **`FriendlyV1PinTest`:** passes unchanged with the seven v1 templates rewritten as sentences
  and compiled with the generated vocabulary. Their IR is identical to the pinned brace IR.
- **`ProgramTemplatesTest` and `ProgramPrinterTest`:** every template is sentence style,
  compiles, and round-trips.
- **`ProgramGameTests.sentenceProgramsRunAndReloadFromTheirPinnedForm`:**
  - a program using `iron ingots` and `block of iron` moves both items;
  - the saved `Canonical` reads `iron ingot` / `iron block`;
  - the canonical form compiles with no vocabulary to the same program;
  - a reload keeps it RUNNING with that program, and the source keeps "block of iron".
- **`CablePortGameTests.theAutoSmelterTemplateSmeltsThroughALabelledFurnace`:** passes with
  the sentence template. That covers `raw materials` (`c:raw_materials`, so raw iron blocks
  stay in storage) and `keep 8 coal in furnace side` (the new faced keep) in a real furnace.
- **GameTests:** all 493 pass.
