# Q61 — Search syntax

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) Q61 (M59, M60). Design:
[ADR 0052 amendment](../decisions/0052-terminal-search-preferences.md#amendment--search-language-2026-10-30-ergonomics-plan-q61);
[STORAGE_TERMINAL](../modules/STORAGE_TERMINAL.md#search-sort-and-player-preferences-r08a11);
player guide under the terminal's Search bullets.

## Checks

- `SearchQueryTest` (8 tests): plain words (name or ID, ANDed); each prefix (`@`, `#`, `$`, `*`)
  matching only its part; quoted phrases, including after a prefix; OR, NOT (nested), grouping
  and the precedence of AND over OR; every count operator and the three-valued behaviour
  without a total (NOT and OR of unknown stay unknown, a failing word still rules a row out);
  twelve malformed queries, each with a code, position and no matches; and the length, term,
  nesting and count-digit bounds, each just under and just over.
- `runTerminalClient` PASS. The query `(#minecraft:coals | "hopper variant") !@factorycore >5`
  finds only the named stack of 23 (coal, at 3, fails `>5`), so the server applies tag, quoted
  text, OR, NOT and the count. `terminal-search-syntax.png` shows the tokens coloured (the box
  scrolls to its end: purple `@factorycore`, green `>5`). `iron (coal` shows no rows and is red
  and underlined (`terminal-search-invalid.png`). The existing display-name, `@mod` and `#tag`
  steps in the same run still pass.
- Not exercised graphically: Up/Down history, right-click clear and the Ctrl+F focus key
  (client input only); the error hover tooltip.
- Screens checked at GUI scale 2 only. The change is text colour in an existing box, with no
  layout change.
- `python3 scripts/verify.py`: PASS (all 454 GameTests, which include the existing search tests).
