# Q99 — Terminal nits

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) Q99 (M110–M112, M125, M127). Design: amendments to
[ADR 0052](../decisions/0052-terminal-search-preferences.md),
[ADR 0031](../decisions/0031-crafting-jobs.md) and [ADR 0064](../decisions/0064-panel-kit.md);
player guide under Jobs, Fluids and energy, Finished jobs and the JEI section.

| Item | Check |
| --- | --- |
| M110 Jobs badge | `runPresentationClient`: once the 37-plank job starts, `runningJobs()` is 1 (evidence line "jobs tab badge: 1"), and `jobs-typed-37-started-scale-2.png` shows a green 1 on the Jobs tab. |
| M111 clear JEI search on open | `runJeiClient`: right-click J sets the flag (J underlined). With sync off and JEI's search at "dirt", reopening the terminal empties JEI's search; a second right-click turns the flag off. `TerminalSearchSupportTest` accepts the flag on any mode, and cycling the mode keeps it. |
| M112 scrolling titles | `TitleShiftTest`: a title that fits stays put; a long one reaches both its start and its end and never shifts past them. `runPresentationClient`: the Crafter labelled "Overflow patterns for the east wall greenhouse" scrolls in a 107 px room. `crafter-scale-4.png` shows it clipped mid-sweep, with the Online strip still beside it. |
| M125 buckets | `CountTextTest.fluidAmountsInBuckets` (0B, <1B, truncation, 1.5kB). `runPresentationClient` captures `terminal-fluids-buckets-scale-{1,4}.png` (lava 2B, water 5B, button B), then switches back to mB. |
| M127 toast while carried | `WirelessGameTests.aCarriedChargedHandheldAsksForAJobToast`: nothing carried, an uncharged bound handheld or a charged unbound one gives no toast; a charged, bound one in the offhand gives one. `JobNoticeTest` round-trips the flag. |

Also run: `runTerminalClient` and `runTerminalRestartClient` PASS. `python3 scripts/verify.py` PASS
(461 GameTests).

Not exercised: the toast itself with no screen open (the client branch only reads the flag the
GameTest covers), and the badge's "16+" cap.
