# U11b — Interface mode switching

Scope: U11 step b ([ADR 0059](../decisions/0059-unified-interface.md)).

Implemented:

- Core retirement: `NodeRegistry.retire`, `BufferRegistry.retire` and
  `MachinePresets.forget`. Each is limited to an idle, detached foreign-port lease
  and removes the records that load-time validation cross-checks.
- `InterfaceSwitch.request` checks permission, idleness, an empty buffer and the
  absence of upgrades before it changes anything. It then detaches the old device as
  breaking does, retires its records, replaces the block entity and places the new
  kind fresh.
- A Mode row (Storage / Machine / Buffer) appears on the three Interface screens.
  The current mode is disabled. A success closes the screen with an action-bar
  message; a refusal shows the reason in the screen's status line.

Checks:

- JVM: retire and forget unit tests in `NodeRegistryTest`, `BufferRegistryTest` and
  `MachinePresetsTest`, covering refusals, released byte charges and reload after
  retirement.
- GameTests in `InterfaceSwitchGameTests`:
  - Buffer to Machine to Storage: the old lease, buffer record, preset and fence are
    gone.
  - Six consecutive switches leave exactly one lease at the position, and every old
    id is retired.
  - Refusals (VIEW only, same mode, items in the buffer, installed upgrade) change
    nothing.
  - The screen button switches the Interface and closes the screen.
- `python3 scripts/verify.py` PASS; all 353 required GameTests pass.
- `runPresentationClient` PASS and `runTerminalRestartClient` PASS (terminal and
  restart). I inspected the scale 4 Storage, Machine (Binding tab) and Buffer
  (Inventory tab) captures: the mode buttons fit at the 427×240 minimum GUI.

Limitations: an upgraded Interface cannot change mode, because upgrades cannot be
removed. Switching does not carry over settings.
