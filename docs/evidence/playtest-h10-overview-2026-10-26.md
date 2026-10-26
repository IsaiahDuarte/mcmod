# H10 — Network overview

Scope: playtest plan H10 (K6). Amends [ADR 0055](../decisions/0055-access-and-recovery-screens.md).

Implemented:

- `NetworkTopology` counts each segment's members by role while publishing
  (`roleCounts(scope)`, a constant-time copy). `WorldNetworks.roleCounts(position)` returns null
  while the network is not valid.
- `OverviewMenu`: plain use on a controller opens it. It syncs the routing status and the
  per-role counts as data slots, refreshed every 10 ticks. Counts need View access (−1
  otherwise); the status is shown to anyone. Its **Access…** button opens Network Access.
- `OverviewScreen` (panel kit, 230 px) shows:
  - the status, with what to fix for each failure (two controllers, no controller, a Gateway
    bypass, a loop, …);
  - counts of storage blocks, terminals, machine ports, Crafters and Program Controllers;
  - a checklist (✔/✖/○, never colour alone): storage, a terminal, then optionally machines and
    Crafters, each with the next thing to do.

Checks:

- `NetworkTopologyTest.roleCountsCoverOneSegmentAndStopAtAGateway`: the counts stop at a Gateway,
  the child segment gets its own, and callers receive a copy.
- `OverviewGameTests` (new):
  - the owner sees the network online with one drive, one terminal and one machine port, and
    Access… opens Network Access;
  - a stranger sees the status but no counts.
- `DeviceStatusGameTests` and `NetworkAccessGameTests` now reach Access through the overview,
  because using a controller no longer opens Access directly.
- `runGameTestServer`: 412 of 412.
- The presentation tour captures `overview` at scales 1–4, then opens Access through the
  overview's button. `runMultiplayerCheck` PASS through the same route.
- I inspected `overview-scale-4.png` and fixed four things before commit:
  - a count label ran past the panel edge;
  - finished steps left empty lines;
  - the panel reserved worst-case space at the bottom (it is now sized to what it shows, 230×153
    when everything is done, and laid out again when the state changes);
  - the frame between a state change and that re-layout crashed (now drawn empty).

  The final tour run passed.
