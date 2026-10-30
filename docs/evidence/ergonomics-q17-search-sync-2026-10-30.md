# Q17 — Recipe-viewer search sync

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) Q17 (M17). Design:
[ADR 0052 amendment](../decisions/0052-terminal-search-preferences.md#amendment--recipe-viewer-search-sync-2026-10-30-ergonomics-plan-q17);
player guide under JEI.

## Checks

- `runJeiClient` PASS. With the terminal open, the suite presses the **J** tool (sync bit set
  by the server), types "cobble" in the terminal search and waits until JEI's ingredient filter
  reads "cobble"; then sets JEI's filter to "stone" and waits until the terminal search box and
  the server-side view both read "stone". `jei-search-sync.png` shows the terminal filtered to
  stone with **J** lit green. The suite then turns sync off and clears both searches.
- `TerminalGameTests` (sort persistence test): the saved sort now also carries `SYNC` and is
  restored on reopen with Tall and Reversed.
- `TerminalSearchSupportTest`: `SYNC` is valid, masked out of the mode, and bit 32 is still
  refused (the old "16 is unknown" check moved to 32, since 16 is now this flag).
- Not rerun: the presentation client (no JEI). Without a bridge the tool list and widths are the
  same four as before, so its search row is unchanged by construction.
- `TerminalSearchSupportTest`'s ordering test likewise moved its "first unknown bit" from 16 to
  32.
- `python3 scripts/verify.py`: PASS (all 450 GameTests) on the second run; the first failed only
  on that unknown-bit check.
