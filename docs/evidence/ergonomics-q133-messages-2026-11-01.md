# Q133 — Terminal messages (2026-11-01)

From playthrough notes 11 and 14.

## Change

- **Quiet statuses:** `TerminalPackets.Status.quiet()` is true for READY, OK, FILLED, EMPTIED,
  CHARGED and DISCHARGED. The terminal's info row and capacity bar treat these as Ready, so
  "Transfer completed" and the like are no longer written.
- **Warnings:** other statuses are still written on the info row in the warning colour.
  `TerminalStorageView.ellipsize` cuts one that doesn't fit and ends it in "…". Hovering the row
  shows the full text, wrapped and kept on screen (`Tooltips.show`, Q135), on every tab; before,
  this hover worked only on Items.
- **Shorter text:** "Insufficient available items" is now "Not enough available".
- **Found by the presentation tour:** the Constructor, Destructor and Tunnel (Q34, Q35, Q37) had
  no item tooltip text, and the tour's untranslated-tooltip check failed on them. They now have
  one each.

## Tests

`runPresentationClient` passes, with a new `warningScales` step:

- After a store, the view's status is quiet (checked).
- At scales 1 and 4, a client copy of the settled view with UNCERTAIN, the longest message, is
  captured as `terminal-warning-scale-1.png` and `terminal-warning-scale-4.png`. Both show
  "Transfer could not …" inside the row, clear of the Crafting button.
- The original view is put back before the usual terminal captures, and `terminal-scale-*.png`
  shows "1.0M items stored" after the store.
