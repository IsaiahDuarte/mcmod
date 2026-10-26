# H9 — Whole-inventory moves in programs

Scope: playtest plan H9 (K8; amendment to
[ADR 0062](../decisions/0062-friendly-language-v2.md)).

Implemented: `everything` and `items` (every item) and `fluids` (every fluid) as filter terms.
They compile to `ResourceFilter.Glob("*")`, identical to `item("*")`/`fluid("*")`, and work with
`except`. The words are reserved and highlighted by the editor, and the "Empty containers"
template uses them.

Checks:

- `FriendlyCompilerTest.wholeInventoryWordsCompileToTheSameFiltersAsAGlob`: four moves using the
  words compile to exactly the program that the equivalent globs produce. `items` as a loop
  variable is a reserved-word error, and `energy` after `except` is refused.
- `FriendlyV1PinTest` (v1 IR unchanged) and `ProgramTemplatesTest` (11 templates, all compiling
  with named rules) pass.
- The presentation tour now finds the redstone template by name rather than by position.
- Moving with a glob is already covered by the D5 GameTests, and the words produce the same IR.
