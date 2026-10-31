# Q118 — Open terminals survive a network rebuild (2026-10-31)

## Bug

Placing one cable on a running network closed every open Storage Terminal on it.
`TerminalMenu.broadcastChanges` closed the menu as soon as `authorized()` was false, and
`WorldNetworks.authorize` returns INVALID_SCOPE while a region is REBUILDING. The same timing
caused the intermittent "Terminal closed" GameTest failures (for example
`fullInventoryRefusesBeforeConsumingAnything`).

## Fix

- `TerminalAccess.present(player)` is the physical half of `canUse` (holder, range, power,
  level), implemented by `TerminalEntity` and `WirelessAccess`. `canUse` = `present` + View.
- `TerminalMenu.stillValid` keeps the menu open while the network is REBUILDING and the player is
  present, for at most 100 ticks. `broadcastChanges` closes through `stillValid` and, while not
  authorized, publishes only the device status strip.
- Commands from the viewer during the rebuild are kept (at most 8) and replayed in order once
  authorized. The replay calls the handler again, so session, grants, rate and power are
  re-checked.
- A route result that arrives during the rebuild is settled once the network is VALID
  (`settle`). Before, a held-open menu would have left a craft stuck in GATHERING: a diagnostic
  run showed `GATHERING crafted 0 … net VALID`.
- Every action still requires `authorized()`; nothing moves while the network is not VALID.

## A first attempt, reverted

Holding the menu open without keeping commands made two other terminal tests fail in 3 of 4
runs (searches and actions sent mid-rebuild were dropped silently). That is why commands and
results are kept.

## Tests

- `TerminalGameTests.aRebuildKeepsTheTerminalOpenAndAppliesItsSearchAfter` waits until the cable
  really puts the network in REBUILDING, then sends a search. It checks the terminal stays open,
  and that the search applies (gold only) with the view READY once the network is VALID. Without
  the fix it fails with "The terminal stays open while the network rebuilds".
- Full GameTest suite: 8 consecutive runs, 491/491 each, after the fix. Before it,
  `fullInventoryRefusesBeforeConsumingAnything` failed intermittently (2 of 5, then 1 of 8 with
  the first part of the fix).

## Not done (Q119)

The Drive, Buffer, Interface and Crafter screens still close on a rebuild.
