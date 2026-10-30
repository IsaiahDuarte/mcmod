# Q104 — Typed menu statuses

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) Q104 (M142), after Q54. Design and contract:
[ADR 0017 amendment](../decisions/0017-storage-terminal-menu.md#amendment--typed-menu-statuses-2026-10-30-ergonomics-plan-q104).

## What changed

| View | Status type | Before |
| --- | --- | --- |
| Terminal storage | `TerminalPackets.Status` (own states, container actions, every `OperationResult.Reason`) | `String`, compared with `"ready"` three times |
| Terminal crafting grid | `TerminalCraftingPackets.GridStatus` | `String`, `"filled"`, `"empty"`, `"ready"` |
| Signal Interface | `SignalPackets.Status` | `String`, `"applied"`, `"labeled"`, `"ready"` |
| Machine and Connector Interface | `MachineSettingsPackets.Status` | `String`, `"ready"` |
| Machine preset (Port screens) | `MachinePresetPackets.Status` | `String`, `"applied"`, `"draft"`, `"redstone_set"` |
| Buffer | `BufferSettingsPackets.Status` | `String`, `"ok"`, `"applied"`, `"ready"` |
| Drive | `DrivePackets.Status` | `String`, `"ready"` |
| Network Access | `AccessPackets.Status` | `String`, `"updated"`, `"transferred"` |
| Transfer Recovery | `RecoveryPackets.Status`, from `RecoveryActions.Result` | `String` switch in the screen |
| Program Controller | `ProgramControllerEntity.State` and `WorldPrograms.RuntimeStatus` | `String`, `"running"` twice |
| Status strip | `NetworkTopology.Status` (null while pending) | `String`, `"valid"` three times |

Each key equals the string sent before; lang keys and saves are unchanged. `RedstoneMenu.click`
returns whether the setting was applied instead of a status string. The Terminal's count strings
moved behind `View.staging()` and `Row.none()`.

## Checks

| Search on `src/main/java/.../platform/client` | Before (Q60) | After |
| --- | --- | --- |
| `.equals("…")` of any kind | 29 | 0 |

- `MenuStatusPacketsTest` (new, 3 tests): every value of each of the 14 status enums round-trips
  through its view's codec; overwriting the key on the wire with an unknown key of the same
  length is refused with `IllegalArgumentException`; every `InterfaceSwitch.Outcome` refusal,
  `OperationResult.Reason` and `RecoveryActions.Result` has its mirrored status.
- `LangKeysTest.everyMenuStatusHasItsKey` (new) failed first with six values lacking a lang entry:
  `buffer.status.busy`/`redstone`, `terminal.status.busy`/`redstone`, `preset.status.redstone`
  and `terminal.grid.status.no_power`. The last was reachable: a handheld terminal that ran out
  of FE during a grid craft showed the raw key. All six now have text.
- About 90 GameTest, client-tour and JEI assertions that compared a status with `.equals("…")`
  now compare typed constants; a wrong guess of the enum type would not compile. The first
  `verify.py` run caught three missed program-status comparisons (compiled, always false); they
  are fixed and a sweep of the remaining `).equals("…")` calls in every source set found only
  methods that still return `String`.
- `python3 scripts/verify.py`: PASS, all 432 GameTests.
- Graphical suites, run under the display lock on Linux: `runTerminalRestartClient`,
  `runJeiClient` and `runMultiplayerCheck` passed first time. `runPresentationClient` first timed
  out at stage 6 (world setup, before any screen) with the vanilla pause screen open, which is a
  lost window focus; it passed on rerun. Screenshots at scales 1 and 4 (Buffer settings, Drive,
  Terminal, Network Access) show the same status texts and colours as before.
