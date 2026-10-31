# Q124 — Offline program helper (MCP) (2026-10-31)

ADR 0068, slice Q124. Setup: [PROGRAM_HELPER.md](../PROGRAM_HELPER.md).

## Change

- **`ProgramHelper`** (`core/program/helper`): newline-delimited JSON-RPC 2.0 over stdio.
  - It answers `initialize` (echoing the client's protocol version, with tool capabilities
    and instructions), `ping`, `tools/list` and `tools/call`, and ignores notifications.
  - Errors use the standard codes: -32700 parse, -32600 request, -32601 method, -32602
    parameters.
  - Inputs are limited to 1 MiB per line and 16,384 characters per program.
  - `Json` is a small built-in reader and writer, so the jar needs only the JDK.
- **Tools:** `language_reference`, `list_examples`, `check_program` (problems with hint and
  example, or the rules and the canonical form) and `find_names` (25 closest names, ranked as in
  the editor).
- **`programHelperJar`:** builds `build/libs/factorycore-program-helper.jar` (about 0.5 MB):
  the core classes, the generated vocabulary and `docs/PROGRAM_LANGUAGE.md`, the new single
  language reference.
- **`/factorycore programs vocabulary`** (operators, `ProgramCommands`): writes the running
  game's vocabulary to `factorycore/program_vocabulary.txt`. `FACTORYCORE_VOCABULARY` points the
  helper at it, so modpack items work.

## Tests

- **`ProgramHelperTest`:**
  - an in-process stdio session runs initialize, initialized (no answer), `tools/list` (four
    tools with object schemas) and `ping`;
  - `check_program` passes with `raw materials`, and its canonical `tag c:raw_materials`
    compiles with no vocabulary;
  - `check_program` fails `iron ingit` with the hint "Did you mean iron ingot?";
  - reference, examples, `find_names` for items and fluids;
  - the three error codes, and JSON escapes in both directions.
- **`ProgramGameTests.vocabularyExportListsTheGamesItemsTagsAndNames`:** the export has vanilla
  and mod items, fluids, loaded `c:` tags and "block of iron", and a program compiles against
  it.
- **Smoke run of the built jar:** `initialize`, `check_program` (with plural names) and
  `find_names iron` piped through `java -jar build/libs/factorycore-program-helper.jar`
  answered correctly.
