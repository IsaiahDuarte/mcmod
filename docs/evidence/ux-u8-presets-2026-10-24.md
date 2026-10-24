# U8 — Gateway and access presets

Scope: F8. Minecraft 1.21.1, NeoForge 21.1.252, Java 21, macOS arm64.

Implemented:

- **Gateway presets** (U8a, commit `189228c`). `GatewayPolicy.Preset` defines three
  presets, and every one keeps all branch grants:
  - SHARED = `open()`;
  - READ_ONLY = inherited VIEW only, all kinds;
  - ISOLATED = nothing inherited, no kinds.

  The gateway screen opens on Shared / Read-only / Isolated / Custom with a plain
  description of the chosen preset. Custom shows the 17 toggles under the new titles
  "This subnetwork can…", "…using parent storage" and "Parent storage kinds". It opens
  on its own when the saved policy matches no preset, decided only after the data
  slots have synced. Menu buttons 32–34 write a preset after the server-side Manage
  check.
- **Access roles** (U8b). `NetworkPermissions.AccessRole` defines four roles:
  - GUEST = {VIEW};
  - MEMBER = GUEST + {DEPOSIT, WITHDRAW, CRAFT};
  - BUILDER = MEMBER + {EDIT, DEPLOY};
  - ADMIN = all.

  Each access row has a role button that cycles through the roles and shows Custom or
  No access when no role matches. Advanced switches to the letter toggles, and Roles
  switches back. The status line moved below the footer buttons. `AccessPackets.ROLE`
  sets exact grants through the existing administration path.

Checks:

- `AccessPresetsTest` (JVM) maps each preset and role to an exact policy or grant set.
  Recognition round-trips through the editor mask, and a policy one toggle away from
  any preset reads as Custom.
- `GatewayPresetGameTests` writes each preset exactly, refuses an out-of-range id, and
  refuses a player without Manage.
- `NetworkAccessGameTests.roleButtonsSetExactGrantsOnlyWithManage` sets each role
  exactly; a Guest without Manage cannot promote itself.
- `python3 scripts/verify.py` PASS; all 347 required GameTests pass.
- `runPresentationClient` PASS, under the GUI lock and `caffeinate`. The
  gateway-policy, gateway-custom, access and access-advanced screens fit at scales
  1–4. Before the final run, two problems were found and fixed: Custom had opened
  before sync, and the column titles were truncated. I inspected the scale 4
  screenshots of both gateway and both access views.

- The candidate-listing access test became flaky once U8 added more concurrently
  online mock players: the screen lists at most 8 online candidates, and two of three
  runs failed. It now runs in its own `access_candidates` batch, with unchanged
  assertions; three consecutive runs pass.
- The presentation tour now grants its sample player the Member role, so the access
  capture shows a named role.

Not run: the manual two-client `runMultiplayerCheck` was updated to press Custom
before toggling, but has not been re-run.
