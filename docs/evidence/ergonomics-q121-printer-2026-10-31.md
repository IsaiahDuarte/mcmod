# Q121 — Printer and round trip (2026-10-31)

ADR 0068, slice Q121.

## Change

- **`ProgramPrinter.print(program, vocabulary)`:** writes any `ProgramIr.Program` as a sentence
  program.
  - **Intervals:** in minutes, seconds or half seconds where exact, otherwise in ticks.
  - **Rule keys:** `called` is printed only for a key other than `rule-N`.
  - **Labels and names:** bare when they read as one word, otherwise quoted.
  - **Items, fluids and tags:** printed as path words only when those words read back to the
    same thing under the vocabulary, otherwise as the ID (`create:brass_ingot`, `tag c:ingots`,
    quoted when the lexer would split it).
  - **Conditions:** grouped with parentheses only where `and`/`or` precedence needs them.
  - **Redstone:** `not` around a redstone check prints as `is unpowered`.
  - **Else-if:** `else if` chains print as `otherwise if`.
- **Unprintable:** an empty block, and a filter with several plain terms (not produced by
  either compiler), throw with a reason, so Convert can refuse instead of guessing.
- **`tag` term:** `tag c:ingots`, or `tag ingots`, which is the common tag when the vocabulary
  has it. This lets any tag be written exactly.
- **`SentenceCompiler.name`:** the lookup the printer uses to check that a name reads back.

## Tests

- **`ProgramPrinterTest.everyFixtureAndTemplatePrintsToEqualIr`:** every v1 and v2 fixture, every
  v3 fixture and its brace twin, and all 12 templates print to sentences that compile back to
  **equal IR**. v3 runs with the test vocabulary; the others run with none.
- **`namesPrintAsWordsWhenTheyReadBack`:** checks the exact output with and without a
  vocabulary: `raw materials` against `tag c:raw_materials`, a quoted label, a slot range,
  `is unpowered`, and `1.5 seconds`.
- **`anEmptyBlockHasNoSentenceSpelling`.**

## Moved

- The template rewrite moved to Q122. The v1 template IR pins need real item names
  (`iron ingots` → `iron_ingot`), which only the generated vocabulary provides.
