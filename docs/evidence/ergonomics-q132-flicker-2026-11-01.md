# Q132 — Terminal flicker on take and store (2026-11-01)

From playthrough note 13: the grid flashes when items are taken or stored.

## Cause

When a move finishes, `TerminalMenu.settle` calls `refresh()`. That cleared `selections`, started
a new search, and `publish()` sent the view straight away: zero rows, with the move's status. The
rows came back a tick or more later, when the search window completed, so every take or store
showed one empty grid.

## Change

`refresh(boolean keepRows)`. `settle` passes true, so the rows shown stay until the completed
result replaces them. Changes to search, sort, view or scope still clear the grid at once, as
before.

- **Commands in between:** a command still names a row by index into `selections`, which now
  holds the previous rows, so it acts on the resource key the player sees. Every move is checked
  against real stock, so a stale count can't move more than exists.
- **Rejected alternative:** a client-side merge that kept rows for interim LOADING views was tried
  first. The interim view carries the move's status, not LOADING, and the server is the one that
  knows a refresh is in flight.

## Tests

- **`TerminalClientVerification`** (`runTerminalRestartClient`): on every tick while the native
  left-click take (stage 5) and the click store (stage 6) are in flight, the grid must have rows.
  - Before the fix: failed with "The grid keeps its rows while a take or store refreshes: OK".
  - After the fix: passed.
- **Full GameTests and verify:** see the commit run.
