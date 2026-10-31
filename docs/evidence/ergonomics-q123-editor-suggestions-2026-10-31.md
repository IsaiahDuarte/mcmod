# Q123 — Editor support for sentence programs (2026-10-31)

ADR 0068, slice Q123.

## Change

- **`ProgramSuggestions` (core):** finds the slot at the cursor from the words before it on the
  line:
  - a sentence start, or a unit after `every N`;
  - an item name after `move [N|all]`, `keep N`, `craft N`, a comparison and its amount,
    `has no/any` or `except`, where the name can be several words and is completed from where
    it starts;
  - a fluid after `fluid`;
  - a label (plus `storage` where it fits) after `from`, `to`, `in`, `if`, `and`, `or`, `not`,
    `turn on/off`, `set` or `when`;
  - after a label: a face, slots, `to`, `has` or `is powered`;
  - after `has`: the comparisons.

  Matches are ranked by prefix, then by the start of any word, then by containing the text,
  then (only when nothing else matches) by a small typo. Names and labels are listed
  alphabetically; the language's own words keep their order. Item names are written so they
  read back exactly, and include common tag groups (`raw materials`) and English names.
- **`ProgramTextEditor`:**
  - while you type, the best match shows as faint text after the cursor, with up to five
    choices in a list under the line (above it near the bottom);
  - **Tab** accepts and adds a space, **Up/Down** choose, and moving the cursor hides the list;
  - live problems compile with the game vocabulary, and the tooltip shows the message, hint
    and "For example: …".
- **`ProgramScreen`:**
  - the log area shows the live problem with its hint and example while you type (not while
    a word is still being finished);
  - it asks for the visible labels when it opens;
  - Ctrl+Space opens the picker on the slot's category, and the picker inserts sentence
    names.
- **Server Check:** lines include the hint.
- **Highlighting (`ProgramSyntax`):** uses the compiler's keyword list and decimal numbers.
  The old quoted-ID `ProgramCompletion` was removed.

## Tests

- **`ProgramSuggestionsTest`** (generated vocabulary):
  - `move 8 iron in` → `iron ingot` with the ghost `got`;
  - item, tag, fluid and except slots;
  - the typo `iron ingit`;
  - labels with `storage` only where it fits, and a quoted label;
  - faces and next words, comparisons, sentence starts, units and comments.
- **`ProgramSyntaxTest`:** highlighting with sentences.
- **Terminal client tour (`runTerminalClient`, passed):**
  - typing `    move 4 iron ing` suggests `iron ingot`;
  - Tab, then `from sm` suggests `smelter`;
  - Tab, `to storage` and Enter rebuild the exact program;
  - `iron ingit` gives the hint "Did you mean iron ingot?";
  - picker inserts are `coal` and `smelter`.
- **Screenshots checked:**
  - `program-suggestion-scale-2.png`: the ghost and list;
  - `program-error-hint-scale-2.png`: the log area and the tooltip;
  - `program-highlighted-scale-4.png`.
- **Tour fix on the way:** the Buffer step found the ID box by position, which was stale since
  Q73 added the slot goal box. It now finds it by name.
