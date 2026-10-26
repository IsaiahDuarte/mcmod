# Q60 — Code-health re-check

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) Q60, after Q52–Q59 (commits `b6bd783` to
`97d8c82`). The searches are those behind M50–M58, run on `src/main/java` on 2026-10-26.

| Search | At `7002c7b` | Now | Remaining |
| --- | --- | --- | --- |
| `"uncertain"` literals | 12 (9 files) | 0 | — |
| Job, rule or staging reason compared as a string in `platform/client` | Terminal, Recovery, Buffer screens | 0 | `ProgramScreen` compares the program state with `"running"` twice; with the other menus' statuses in M142/Q104 |
| String `.equals("…")` of any kind in `platform/client` | not counted | 29 | Menu statuses (`"ready"`, `"applied"`, …), M142/Q104 |
| Raw `fill` in the Terminal classes | 10 | 0 | — |
| Lines with a hex colour in the Terminal classes | 28 | 0 | — |
| Copied selection/hover colours outside `Panels` | 8 (5 screens) | 0 | — |
| Raw `fill` in other client screens (not `ui/`) | not counted | 10 | `ProgramTextEditor` (3, the dark code field kept by ADR 0064 H3c); `DriveScreen` bay fill bars (2); `FilterSlots` (2); `SlotPicker` (2); `StatusStrip` lamp (1): M143 |
| Anonymous `IEnergyStorage` | 4 (3 consume-only) | 1 | `FuelGeneratorEntity`, an extract-only producer: a different contract, not a copy |
| `codec.itemStack(key, 1)` calls | 4 (3 inline checks) | 1 | `WorldPlayerInventories` builds a stack it uses, not a check |
| `getMessage() == null` lines | 9 (8 "message or class name" copies) | 2 (the helper `core.Failures` and `ThroughputUpgradeItem`) | `ThroughputUpgradeItem` keeps an empty fallback on purpose |
| `TerminalScreen` lines | 2,070 | 589 (+ parts of 570, 497 and 855) | — |
| Wasm clock reads per full-budget rule | 65,537 | 257 | — |

Follow-ups recorded in the plan: M142/Q104 (typed menu statuses) and M143 (remaining raw fills
and colours outside the Terminal).

Correction: the Q57 commit message says nine copies were replaced; there were eight (the ninth
match was the new helper itself). The plan's Q57 entry is corrected.
