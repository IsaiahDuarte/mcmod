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
