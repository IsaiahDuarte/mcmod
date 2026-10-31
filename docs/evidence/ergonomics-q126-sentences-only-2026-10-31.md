# Q126 — Sentences are the only language (2026-10-31)

The player asked for no backward compatibility: "i dont want v1/v2 etc because its just a new
mod".

## Change

- **`FriendlyCompiler`:** now only the entry point (limits, `Diagnostic`, `Result`,
  `compile`, `canonical`) over `SentenceCompiler`. The 700-line brace grammar is gone. A
  program with braces gets "Programs don't use braces", with a hint and an example.
- **Conversion:** before deleting the brace compiler, every brace program in the tests and the
  client harness was compiled with it, printed with `ProgramPrinter` and compiled back. 35
  literal programs were converted only when the IR was **equal**. The tool is in the session
  scratchpad, not the repo. The rest, built in loops or deliberately broken, were converted by
  hand: flood, census and padding programs, the evaluator's condition strings, `bogus` drafts
  (the editor diagnostic is now at 1:17), and the benchmark's overload program.
- **Machine Inspection:** prints sentence lines (`move everything from oven top to storage`,
  faces `top`/`bottom`/`north`…). Editor inserts (`ResourceExpressions`) produce `iron ingot`,
  `fluid water`, `create:brass_ingot` and bare or quoted labels.
- **Removed:**
  - `FriendlyCompilerTest`, `FriendlyV1PinTest`;
  - `program/v1`, `program/v2`, `v1-ir.txt`;
  - the `.brace` twins.
- **Fixtures:** `program/v3` became `program/sentences`, pinned to its IR in
  `program/sentences-ir.txt`. That IR was already proven equal to the brace twins.
- **Limits:** `SentenceCompilerTest.limitsAndBracesGiveClearErrors` covers what the brace tests
  checked: 64 rules, duplicate names, depth 8, a limit of 4,096, label 64, text 128, source
  16,384, 16 diagnostics, 512 statements, and braces.
- **Docs:** ADRs 0035 and 0062 are superseded by 0068 (amended). The SDK README's comparison
  table now uses sentences.

## Not changed

- The IR, evaluator, Wasm host and Rust SDK.
- The editor's highlighter and Ctrl+Space completion still assume the old syntax; Q123
  replaces them.

## Tests

- **Unit tests:** all pass.
- **GameTests:** all 493 pass with the converted programs.
