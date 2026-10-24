# ADR 0041 — Gateway policy editor

Status: selected; native headless evidence. Graphical checks remain open.

## Decision

Sneak-using a gateway opens `GatewayPolicyMenu`, a slotless menu with 17 toggles: seven
branch grants, seven inherited permissions and three inherited resource kinds. The
encoding is `GatewayPolicy.mask()`/`fromMask()`/`toggled()`, with bit order following enum
declaration order.

Toggles use vanilla menu-button clicks, so there is no new payload type. On every click the
server re-checks Manage at the gateway and its range, then writes the canonical node policy
(`NodeRegistry.setPolicy`) and re-discovers the gateway. Edits fail closed while the edited
branch is rebuilding, so a second click in the same tick is refused, not queued.
The client receives only the mask and a may-edit flag, never grants. Viewers without
Manage see a read-only editor.

## Alternatives

A custom payload like the terminal's would add a codec without adding expressiveness;
17 bits fit into two vanilla data slots. Allowing edits during a rebuild would authorize
against stale topology.

## Consequences

Child policies still intersect with all ancestors (R04/A07). Labels and per-player grant
editing for controllers are separate work.


## Presets extension — 2026-10-24 (U8)

Three exact presets (Shared, Read-only, Isolated) plus Custom replace a first screen
of 17 toggles. The presets are ordinary policies: there is no new storage, no
encoding change, and recognition is exact equality, so any other mask is Custom.
Every preset keeps all branch grants, because removing a branch grant disables that
action inside the branch itself, which is rarely what a player choosing "Read-only"
expects. The presets differ only in what reaches parent storage. An alternative that
stored a preset name was rejected: it would drift from the actual mask that
`NetworkAuthority` enforces.

