# Q146 — Jobs inline in the Items view (2026-11-01)

From playthrough note 4 ("a unified view of fluids, items, energy, jobs that are inline in the
terminal"), split from Q138.

## Change

- **Strip** (`TerminalJobStrip`): while the viewer has unfinished jobs of their own, the first row
  of the Items grid area shows them. The grid gives up that row (`TerminalStorageView.rows()` is
  the laid-out rows less one, `gridTop()` moves down 18 px), so the panel, info row and inventory
  stay where they are. Turning the strip on or off rebuilds the screen's widgets, so the grid's
  cells and window follow; the window is re-requested because its size changed.
- **Chips:** each is three grid columns wide (54 px): the output's icon, crafts done/total and a
  progress bar (cyan running, amber waiting or delivering), and ✕. A 9-column terminal fits three
  chips, an 18-column one six. With more jobs than chips the last chip reads "+N more" and opens
  Jobs.
- **Clicks:** a chip opens that job's steps in Jobs (`TerminalJobsView.openJob`, the same DETAIL
  command a row click sends). ✕ arms on the first click (red, tooltip "Click ✕ again…") and
  cancels on a second within 60 ticks, with the Jobs tab's CANCEL command. A cancelling job's ✕ is
  muted and ignores clicks.
- **Data:** none new. The server already sends the job list to every open terminal (the Jobs-tab
  badge uses it), own jobs first, so chip *i* is listed job *i*; the server checks DETAIL and
  CANCEL as before.
- **Detail open race (fixed on the way):** the Jobs view closed a just-opened detail on its next
  tick if the server's reply had not arrived yet. It now keeps it for up to 20 ticks while the
  DETAIL request is answered; this applies to row clicks too.
- **Scope:** Keep, Plan and Tree stay in Jobs, reached from the craft dialog's Details. Only the
  viewer's own jobs are shown (Manage users see others' jobs in Jobs).

## Tests

`runPresentationClient` (passed on the headless display), in the A4 waiting-iron scene:

- With the iron job waiting for a machine, the Items view shows the strip at GUI scales 4 and 1,
  and the first grid cell is 18 px lower than without it.
- A real click on the chip opens Jobs with the iron job's steps.
- Back in Items, one click on ✕ leaves the job listed and not cancelling; a second cancels it,
  then the strip disappears and the grid's first cell returns to the top.

The same run also fixes a flaky step found while running it: the keyboard take (Enter on a grid
cell) was pressed while the view was still busy from the scale change, which `clickRow` ignores,
so the next step waited forever for an item on the cursor (2 of 6 runs today, stage 107). The step
now waits for the view to settle and presses Enter again if nothing was taken.

`runTerminalRestartClient`: the first JVM's terminal tour passed in 2 of 3 runs with this change.
One run timed out opening the Exporter's screen (stage 41), which passed in the other two. The
restart JVM then failed `SplitRootVerification` phase 1 in both of those runs. The same check
also failed in 1 of 2 runs at `2605478` (before Q144) under the same load (load average about
23), so it is a pre-existing flake, recorded as Q151.

`python3 scripts/verify.py` passes.

## Screenshots looked at

- `items-job-strip-scale-4.png`: the chip with the iron ingot, "0/1" in amber, an empty bar and
  ✕, over a 2-row grid (the minimum window at scale 4 lays out 3 rows).
- `items-job-strip-scale-1.png`: the same chip at scale 1, legible, the grid 5 rows.
