# H4/H5 — Machines on cables through cable ports

Scope: playtest plan H4 and H5 (K5; gate HD1; [ADR 0065](../decisions/0065-cable-ports.md)).
Minecraft 1.21.1, NeoForge 21.1.252, Java 21, Linux x86-64 on Wayland.

Implemented:

- `cable_port` block: a cable with a port plate, aimed at a machine, wired on its other five
  faces, and backed by the Machine-port entity with a per-face backing (as ADR 0066).
- Label Tool on a machine:
  - relabels the port already aimed at it; or
  - converts the touching cable (clicked face first) under the player's EDIT, then binds the
    offered resource and applies the label once the network has rebuilt.

  Clear messages for no cable, an unreachable side, or no permission.
- Breaking a port:
  - idle: the presets are forgotten, the lease is detached and retired, and a plain cable drops;
  - busy, faulted or upgraded: the `cable_port` item drops carrying the lease. It has no recipe,
    is hidden from the creative tab, and is listed in `RecipeGameTests.NON_RECIPE`.
- `CablePortScreen` (panel kit) shows the machine label, the resource choice, a hint with the
  exact `machines("…")` call, status, Return and Apply.

Checks:

- `CablePortGameTests` (3 new):
  - the Label Tool makes a labelled port aimed at the furnace, the controller beyond the port stays
    in the network, only the touching cable changes, and labelling again relabels in place;
  - `move everything from machines("oven") side down to storage` moves all 9 ingots from the
    furnace output into storage through a port on the furnace's north face, and the fuel stays;
  - breaking the idle port drops exactly one plain cable and retires the lease, and the cut-off
    controller leaves the network.
- `python3 scripts/verify.py --full` PASS: 410 of 410 GameTests and every release suite.
- `runPresentationClient` PASS: `cable-port` fits at scales 1–4 (176×131). I inspected `cable-port-scale-4.png`, then fixed a status line cut off by the hidden Return button and folded the label caption into the field hint. A cable port shows no preset reason (it has no preset).

Not covered by a test: a machine removed mid-move, and a machine touching two networks. Both rely
on the unchanged Machine-port binding and recovery. Breaking a busy port uses the same unresolved
check as the Interface mode switch.
