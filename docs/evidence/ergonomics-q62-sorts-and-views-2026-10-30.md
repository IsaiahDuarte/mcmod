# Q62 — Sort and view filters

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) Q62 (M61, M62). Design:
[ADR 0052 amendment](../decisions/0052-terminal-search-preferences.md#amendment--id-and-recent-sorts-items-view-2026-10-30-ergonomics-plan-q62);
[STORAGE_TERMINAL](../modules/STORAGE_TERMINAL.md) preference fields; player guide under Sort
and Craftable items.

## Checks

- `CraftableGridGameTests.recentAndIdSortsAndTheThreeViews`: logs, planks and a dirt stack named
  "Zeta" are stored, then one log is taken. Recent orders log, Zeta, planks (last change
  first); ID puts `minecraft:dirt` (Zeta, last by name) first. The Craftable view shows only
  planks (3) and the unstocked stick. View and ID sort survive closing and reopening. A saved
  preference with only the old `Craftable` flag opens in the All view with all four rows.
- `DeviceRegistryTest.changesAreSequencedPerDeviceAndKey`: inserts and extracts (including a
  partial fill) raise the key's sequence; a move refused as full changes nothing; an emptied key
  and a stale handle read 0.
- `TerminalSearchSupportTest.idAndRecentSortsAndTheButtonCycle`: ID and Recent orders, Recent
  reversed, and the five-step button cycle keeping flags. Unknown sort examples moved to 65 and
  128 because 3 (ID) and 64 (Recent) are now modes. The same change applies in
  `TerminalPacketsTest` and the `TerminalGameTests` decode-refusal step. `TerminalPacketsTest`
  round-trips the Craftable view and refuses view 3.
- `runPresentationClient` PASS at GUI scale 4: `terminal-sort-id-scale-4.png`,
  `terminal-sort-new-scale-4.png` (the label fits), `items-craftable-scale-4.png` (All),
  `items-craftable-only-scale-4.png` (aqua tool, craftables only) and `items-stored-scale-4.png`.
  `result.txt` lists the Recent order with the last-stored raw iron and planks first.
- Scale 1 was not captured for these views. The tool and button sizes are unchanged and the
  labels are no longer than "Name".
- Not done: the "last changed when/by whom" tooltip. The catalog does not track it; the decision
  is in the ADR amendment.
- `runTerminalClient` PASS after its sort-cycle step learned the two new modes (it clicked Mod
  and waited for Name, which is now three clicks away); `runTerminalRestartClient` and
  `runJeiClient` PASS.
- `python3 scripts/verify.py`: PASS (all 455 GameTests).
