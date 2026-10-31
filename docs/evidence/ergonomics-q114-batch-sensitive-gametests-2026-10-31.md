# Q114 — Batch-sensitive GameTests (2026-10-31)

**Cause.** Blocks a test sets in one tick (controller, drive, terminal) are each a wired position
with no network yet, so each starts its own region discovery (`WorldNetworks.changed`). The first
to finish publishes VALID; an overlapping one can then merge into it and publish a new scope,
which closes menus opened in between. When that happens depends on server load and on which
tests share a batch. A step that then cast `player.containerMenu` to `TerminalMenu` threw a
`ClassCastException`, which crashes the whole GameTest server instead of failing one test.
(Seen again during Q74: `TerminalCraftingGameTests.fullInventoryRefusesBeforeConsumingAnything`.)

**Fix (tests only).**
- New `testing/TestMenus`: `terminal(player)` is a checked cast that fails the test with
  "Terminal closed: …". All 21 unchecked casts in 14 files use it.
- `TestMenus.ready(helper, ready, position, message)` passes once `ready` holds and the network
  at the position has stayed VALID with the same scope for 10 checks (ticks). The 38 one-line
  `canUse` readiness waits in those files use it, so menus open on a settled network (as
  `MachineSettingsGameTests.settled` did in Q22).
- `ProcessingJournalGameTests`: the `factorycore recovery processing resolve` command admits one
  request a second **server-wide**. Tests running together shared it, so
  `machineSavedBeforeItsInsertionFreezesTheJobUntilResolved` failed "Operator resolves" with "Wait
  one second before another recovery request". The four resolve steps now retry in a
  `thenWaitUntil` until admitted. A refused request changes nothing and writes no evidence, so the
  evidence-count checks are unchanged.
- The Wrench tests are back in the default batches (their `batch = "wrench"` is removed).

The duplicate rebuild of a freshly built network is product behavior and is left as it is: it is
correct (menus on a stale scope must close), and a player builds one block at a time.

## Checks

- Five consecutive full `./gradlew runGameTestServer` runs: all 479 required tests passed each
  time (logs `q114d-1..5.log` in the session scratchpad).
- `python3 scripts/verify.py`: PASS.
