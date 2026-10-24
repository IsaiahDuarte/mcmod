# U7 — Status on screens, not chat

Scope: F4, partial A11. Minecraft 1.21.1, NeoForge 21.1.252, Java 21, macOS arm64.

Implemented:

- `DeviceStatus` (platform/session) is one shared payload. It carries the network
  state, the device's reason as a translation key, and preformatted power. Each device
  menu publishes it from its own broadcast step, only when it changes, and clients
  accept it only for the open menu and session. It carries no authority.
- `StatusStrip` draws a green or amber lamp with player-facing text: Online,
  Connecting, No controller, Two controllers connected, and so on
  (`gui.factorycore.status.network.*`). The text is truncated to the room available, and
  a hover tooltip shows it in full.
- The strip appears on all eight device screens:

  | Screen | Placement | Reason / power |
  | --- | --- | --- |
  | Drive | title row | none |
  | Terminal | right end of the tab row (the title row holds search and sort) | none |
  | Buffered Interface | title row before the tabs, hover for full text | import reason |
  | Machine Interface | line under the title, left of the tabs | runtime preset reason, or "Paused" |
  | Storage Connector | title row | container registration (this replaced the separate "Container:" line) |
  | Program Controller | title row (this replaced the old state line) | program state, or a runtime problem such as no FE; energy shown as stored / capacity FE |
  | Gateway Policy | title row, from a fourth vanilla data slot | none |
  | Network Access | title row | none |

- Empty-hand use now opens the Gateway policy screen and the Controller's Network
  Access screen; before, only sneak-use did. The action-bar status is gone for both.
  The Buffered Interface no longer prints five chat lines before opening its screen.
  Devices without a screen (Crafting Unit, Fuel Generator, Energy Bank, Access Point,
  Link Endpoint) keep their action-bar or chat status.
- The tooltips, guide pages 4 and 11, and the player guide are updated. Page 4 now
  also reflects U6: a placed buffer starts in Import.

Checks:

- `DeviceStatusGameTests`: plain use on a controller opens Network Access with an
  "Online" strip, and a connector with nothing behind it reports a not-ready reason.
- `python3 scripts/verify.py` PASS; all 345 required GameTests pass.
- `runPresentationClient`, `runTerminalRestartClient` (terminal and restart) and
  `runJeiClient` all PASS under the GUI lock and `caffeinate`. The first presentation
  pass showed the Machine Interface strip running under its tabs, a misleading "offline"
  reason for a paused preset, and a truncated program energy value. All three were fixed
  and re-run. I inspected the scale 4 machine, program, buffer, terminal, gateway and
  access screenshots: nothing overlaps, and the full energy value shows.
