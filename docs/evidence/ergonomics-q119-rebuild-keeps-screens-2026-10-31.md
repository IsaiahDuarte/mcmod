# Q119 — Device screens survive a network rebuild (2026-10-31)

## Probe

`RebuildScreensGameTests.deviceScreensStayOpenThroughARebuild` builds one network with a Drive,
Buffer, Machine Interface (and chest), Crafter, Program Controller, a Storage Interface (and
chest) and a cable column. It opens each screen in turn (and the controller's Overview), places
one more cable, and polls for 40 ticks.

Before the fix it reported `[BufferSettingsMenu, MachineSettingsMenu, ProgramMenu]` closed. The
Overview, Drive and Crafter screens check only physical presence and already survived. The
Storage Interface screen (added to the probe afterwards) has the same scope pin as the Buffer
and Machine screens.

## Causes

- **Buffer, Machine and Storage Interface settings:** `current()` compared the scope captured at
  opening with the live one using `equals`. A scope includes the region and topology generation,
  which change on every rebuild, so any rebuild ended the session. `MenuSession.due` then closed
  the menu. These screens also need View, which is false while REBUILDING.
- **Program:** `canView` needs View, and `broadcastChanges` closed the menu when it failed.

## Fix

- **`platform/session/RebuildGrace`:** the Q118 rule as a shared class. While the player is
  present and the network is REBUILDING, the menu stays open for at most 100 ticks. Commands
  meanwhile are kept (at most 8) and replayed in order once the menu is current again, and each
  replay re-runs every check.
- **Scope comparison:** `RebuildGrace.sameAuthority` compares only network, segment and node, the
  identity the scope pin exists to keep. A rebuild that keeps them refreshes the captured scope.
  A real move to another network or segment still closes the screen, and grants are still checked
  live on every action.
- **`present(...)`:** the physical half of `canUse`/`canView` on `BufferedInterfaceEntity`,
  `ConnectorEntity` (and so Machine Interfaces) and `ProgramMenu`.
- **`broadcastChanges`:** returns early while the menu is held, before `due` could close it.

## Test fix

`TerminalCraftingGameTests.fullInventoryRefusesBeforeConsumingAnything` waited for status FULL,
but its first step already leaves FULL. If the second craft was held during a rebuild, the wait
passed before that craft had run ("sticks 0 planks 64"). It now waits for FULL and 64 sticks, the
outcome it asserts next.

## Results

The probe passes. Full GameTest suite: 5 consecutive runs, 492/492 each.
`TerminalMenu` keeps its own Q118 copy of the rule (tested by
`TerminalGameTests.aRebuildKeepsTheTerminalOpenAndAppliesItsSearchAfter`). It could move to
`RebuildGrace` later.
