# Q109 — Recently crafted items first

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) Q109 (M18a, split from Q18). Design:
[ADR 0052 amendment](../decisions/0052-terminal-search-preferences.md#amendment--recently-crafted-items-first-2026-10-30-ergonomics-plan-q109);
player guide under the terminal's Depositing bullet.

## Checks

- `TerminalCraftingGameTests.craftedItemsLeadTheGridUntilClosed`: storage holds acacia logs,
  apples, birch logs and oak logs, shown in name order. One grid craft makes four oak planks,
  which are deposited back. The grid then shows oak planks first (name order would put them
  last). A two-row window at offset 3 shows birch log and oak log with five rows in total, so
  windows after the pin continue the name order with no row repeated or skipped. After closing
  and reopening, Acacia Log is first and Oak Planks last again. Without the pin the
  "planks lead" assertion fails, since name order puts Acacia Log first.
- Jobs started from the Jobs tab pin their output through the same list (`TerminalJobs.takeStarted`);
  no separate GameTest covers that path.
- Full GameTest run: all 453 passed.
- No graphical run: no layout change; the grid only shows rows in a different order.
- `python3 scripts/verify.py`: PASS (all 453 GameTests).
