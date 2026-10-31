# Q120 — Sentence compiler (2026-10-31)

ADR 0068, slice Q120.

## Change

- **`SentenceCompiler`:** a front end that compiles indented sentence programs to the existing
  `ProgramIr`. `FriendlyCompiler.compile(source, vocabulary)` sends sentence-style sources to it.
  Brace programs compile exactly as before.
- **`Vocabulary`:** names resolve as item ID path (singular or plural), then English name, then
  `c:`/`minecraft:` tag, else an error with up to three suggestions. `Vocabulary.NONE` takes
  names literally. The game vocabulary arrives in Q122.
- **`ProgramIr.KeepAt`:** `keep N stuff in machine side`, counted and filled through one face
  and slot range. `ProgramCommand.Keep` gains an optional `Access`. `WorldPrograms.keep` counts
  the matching contents of that face's view and fills through it. Plain `Keep` and every
  v1/v2 IR are unchanged.
- **`Diagnostic`:** gains an optional `hint` and `example`. Each sentence line reports at most
  one error and checking goes on with the next line. After an indentation error, the rest of
  that rule is skipped.
- **Labels:** any word but `storage` can be a label, because labels only appear in fixed
  places. The Energy template's `powered` label, for example, still works.

## Tests

- **`SentenceCompilerTest`:**
  - `src/test/resources/program/v3/` has 14 fixtures, one per form:
    - moves with no amount, a number and `all`;
    - the one-line form;
    - plural names, display names, mod IDs, quoted names and common tags;
    - patterns with `except`, fluids and energy;
    - sides and slots, and quoted labels;
    - `keep`, and `keep` with a side;
    - craft when low, with `say`;
    - `and`/`or`/`not`/parentheses, `otherwise if` and `otherwise`;
    - `for each`;
    - `when … is powered`, `is unpowered`, `turn on/off` and `set … redstone to`;
    - `every 0.5 seconds` indented with a tab, with comments.
  - Each fixture with a `.brace` twin must compile to **equal IR**.
  - Errors are tested for:
    - a misspelled item ("Did you mean iron ingot?");
    - a missing `from` (with an example);
    - misaligned indentation, mixed tabs and spaces, and indentation under a line without `:`;
    - a rule that does not start at the left edge, and a `:` with nothing after it;
    - `keep … in storage`, storage to storage, a stray `otherwise`, and a side on a `has` check;
    - an unknown sentence, a fractional tick interval, an interval out of range, and crafting
      a group;
    - an ambiguous mod name;
    - several broken lines reported together.
- `FriendlyCompilerTest`, `FriendlyV1PinTest` (v1 IR pins unchanged) and the evaluator and
  Wasm program tests pass.

## Not yet

- Programs are not in game until Q122. A deployed sentence program would currently resolve
  names with no vocabulary, and pinned deploys don't exist yet.
- Printer and round trip: Q121. Editor and suggestions: Q123. MCP helper: Q124.
