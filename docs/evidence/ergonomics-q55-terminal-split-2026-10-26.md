# Q55 — Terminal split onto the kit

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) Q55 (M53, M54); [ADR 0064](../decisions/0064-panel-kit.md)
Q55 amendment. Minecraft 1.21.1, NeoForge 21.1.252, Java 21, Linux x86-64 on Wayland.

Implemented:

- `TerminalScreen` (2,070 lines) is now a shell of 587 lines over `TerminalStorageView` (grid,
  scrollbar, info row), `TerminalCraftingPanel` and `TerminalJobsView` (strip, job list, plan and
  steps). The verification API the tours call (`itemX/Y`, `gridX/Y`, `jobRowX/Y`,
  `craftableStripX/Y`, `windowSettled`, `craftingOpen`, `jobsMode`, `requestFromViewer`) is
  unchanged.
- `Panels` gained the selection fill and edge, focus edge, hover fill and `row`, used by the
  Terminal, Drive, Network Access, Recovery and Buffer screens, plus the Terminal's veil, marker,
  meter, thumbs, short-row fill, lamp and named list and field text colours.
- `grep -n '\.fill(\|0x[0-9a-fA-F]\{6\}' platform/client/Terminal*.java` finds nothing.

Checks:

- `runPresentationClient`, `runTerminalClient` with `runTerminalRestartClient`, `runJeiClient` and
  `runMultiplayerCheck` PASS unchanged (one Gradle run, 5 min 23 s).
- Screenshots were captured from the commit before the change and after it, and compared pixel by
  pixel. All 28 Jobs and plan captures, among others, are identical. Inside the panel rectangles,
  `terminal-scale-1`, `crafting-table-scale-1` and `crafting-outputs-scale-4` are identical. The
  remaining differences are outside the panels: the sky behind the tab strip, the world beside a
  panel, and an animated water texture in a Fluids slot. I looked at `terminal-scale-4`,
  `crafting-table-scale-4` (before and after), `jobs-monitor-waiting-scale-4` and `access-scale-4`.
- `python3 scripts/verify.py --full` PASS.
