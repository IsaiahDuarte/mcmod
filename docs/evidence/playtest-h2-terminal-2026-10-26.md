# H2 — Terminal on the panel kit

Scope: playtest plan H2 (K2; gate HD3; [ADR 0064](../decisions/0064-panel-kit.md)). Minecraft
1.21.1, NeoForge 21.1.252, Java 21, Linux x86-64 on Wayland.

Implemented:

- The Items/Fluids/Energy/Jobs tabs sit on the panel's top edge, with a status lamp at the right
  (hover for the full status; an offline reason is drawn over the grid). One search row holds
  search, show-craftable (⚒), sort, direction and **⇕** (Tall).
- The grid has 9 columns, and the player inventory is docked under it so the two share column
  positions, as in AE2/RS. The grid has six rows, or fills the window height with Tall (at least
  3; 4 at the 427×240 minimum). Tall is bit 8 of the saved `Sort` preference
  (`TerminalSearchSupport.TALL`). `mode()` masks it out, so the order never changes, and an unknown
  bit (16) is still refused.
- The info row under the grid holds the capacity text and bar (Items), Fill (Fluids) or
  Charge/Drain (Energy), plus Return and **Crafting »**. The Energy tab has no Crafting button,
  because the grid only takes items and fluids.
- The crafting grid is a separate panel on the right, only as tall as its content. Table, Inputs
  and Outputs are a choice row, and Substitutes is a checkbox.
- Jobs uses the whole area under the search row: the 8-cell craftable strip and its scrollbar,
  three text lines, a job list that grows with the window, and two button rows. The inventory
  slots are hidden while Jobs is shown (`Slot.isActive` false: not drawn, hovered or clicked;
  contents unchanged). The plan breakdown and job steps cover the strip and list.
- The capacity text for external storage reads "N items stored". The unknown capacity is
  explained in its tooltip.

Checks:

- `TerminalSearchSupportTest.tallRidesInTheSortPreferenceWithoutChangingTheMode` (new), plus a
  Tall-ordering assertion. `quantitySortUsesArbitraryPrecision…` now checks unknown bit 16
  instead of 8, because 8 is the new Tall bit (the requirement changed, and the refusal coverage
  is kept).
- `TerminalGameTests` sort test: sending reversed quantity with Tall keeps the reversed order, and
  Tall is persisted per player and restored on reopen.
- `runPresentationClient`, `runTerminalRestartClient` (with `runTerminalClient`), `runJeiClient`
  and `runMultiplayerCheck` PASS. The tours' job-row and craftable-strip clicks now use
  `TerminalScreen.jobRowX/Y` and `craftableStripX/Y` instead of fixed offsets. I inspected
  `terminal-scale-4.png`, `terminal-fluids-scale-4.png`, `crafting-table-scale-4.png`,
  `jobs-plan-two-level-shortage-scale-4.png`, `jobs-monitor-waiting-scale-4.png` and
  `jobs-started-scale-2.png`. Every terminal screen fits at scales 1–4 in the 1708×960 minimum
  (main panel 192 wide; 372 with crafting).
- `python3 scripts/verify.py` PASS (fast).

Follow-ups found on inspection and fixed before commit: the capacity text was cut off, the plan's
Craft and Missing headers touched, and the crafting panel had empty space at the bottom.
