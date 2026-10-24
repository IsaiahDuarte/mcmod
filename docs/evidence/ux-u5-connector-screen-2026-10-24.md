# U5 — Storage Connector screen

Scope: F2/F9, partial A11. Minecraft 1.21.1, NeoForge 21.1.252, Java 21, macOS arm64.

Implemented:

- With both hands empty and not crouching, use opens `ConnectorSettingsScreen` /
  `ConnectorSettingsMenu`. Opening needs current VIEW within 8 blocks; `canUse` moved
  from the Machine Interface to the shared `ConnectorEntity`.
- The form covers every offhand control: resource (crouch-use cycle), access (offhand
  redstone) and priority as a -1000..1000 number (offhand iron/gold stack size). It also
  covers the first slot, slot window and rate, which were server-API only before.
- The view shows the container side, the throughput tier limit and server load. It also
  polls the live container registration every 10 ticks, so the status line reads
  "Container: Ready" or the reason it is not.
- Apply reuses the bounded Machine Interface form, a session nonce, generation-fenced
  staleness, the tier check ("upgrade" status) and one `ConnectorEntity.configure` call
  under current EDIT. Without EDIT the form is read-only and Apply reports "denied".
- The offhand shortcuts remain until U16 removes them.

Checks:

- `ConnectorSettingsGameTests` (3 tests): one Apply edits access, priority, window and
  rate and closes; the resource selector switches to fluids and the status line then
  reports "unsupported" for a vanilla chest; a wrong nonce, an over-tier rate and a stale
  generation change nothing and reload the current form; VIEW-only is read-only and
  denied, and a player without VIEW cannot open the screen.
- `python3 scripts/verify.py` passed: all 340 required GameTests plus the JVM, static and
  architecture checks.
- `caffeinate -d -i -u ./gradlew runPresentationClient` under `/tmp/factorycore-gui.lock`
  PASS. The tour opens the connector placed on the screens network against its own chest
  and waits for "Container: Ready". Fit lines report a 300×190 panel with 9 controls at
  scales 1–4, including the 427×240 minimum GUI. `connector-scale-1.png` and
  `connector-scale-4.png` were inspected: no overlap or clipping, and the status line is
  green.

Remaining gaps: U7 still plans consistent status wording for every screen. The
`reason.factorycore.*` texts shown on this screen still say "provider" in places.
