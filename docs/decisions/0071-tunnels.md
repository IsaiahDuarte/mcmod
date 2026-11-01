# 0071 — Point-to-point Tunnels

Status: accepted 2026-11-01 (ergonomics plan Q37, gate QD10).

## Context

AE2's P2P tunnels carry items, fluids, FE or redstone from one face to faraway faces over the
network. QD10 approved them, scheduled after cross-dimension links.

## Decision

- **One block for every kind:** a single **Tunnel** block carries items, fluids, FE and redstone,
  rather than one tunnel per resource.
  - Its front face is its working face; its other five faces join the network as a terminal
    vertex.
  - Its role is UNSET, INPUT or OUTPUT, kept in the block state. Changing the role updates only
    clients, and redstone updates touch only the block in front, so a role change does not make
    the network rebuild.
- **Pairing:** sneak-use with an empty hand.
  - Sneak-using a tunnel makes it an input with a new frequency (an existing input keeps its
    own). Each further sneak-use within 60 seconds makes that tunnel an output of it, and
    sneak-using the input again finishes.
  - Setting a role needs MANAGE on a valid network, or being the placer while off-network.
- **Links:** an input and an output are linked only while both:
  - are loaded;
  - share a frequency;
  - are VALID members of the same network.

  This can span dimensions through cross-dimension links (ADR 0040). Only loaded tunnels are
  known (`WorldTunnels`), so nothing loads a chunk.
- **Moving things:**
  - **Items, fluids and FE:** the input's front face exposes item, fluid and energy handlers.
    Inserting offers the amount to each linked output's target in turn, first fit, starting
    after the last output that accepted. A target is the block in front of the output, if its
    chunk is loaded and it is not itself a tunnel. Whatever no target accepts is returned, so
    nothing is buffered or lost. Simulated moves change nothing, including the rotation.
    Extraction is refused.
  - **Redstone:** the input reads the signal its front receives; outputs emit it, weak and
    strong, toward the block they face.
  - **Re-checks:** an input re-reads its signal on neighbour changes, and every 10 ticks both
    roles re-check, which covers network changes.
- **Re-entry:** a move that re-enters any tunnel is refused, so no loop can recurse.

## Consequences

- Tunnels are passive pass-throughs at the speed of the inserting machine, outside the scheduled
  transfer engine. So they do not count against per-interface throughput or the wireless pair
  budget (ADR 0050). A tunnel crossing a wireless link therefore does not use the link's
  per-pair limit; this is accepted for now, in the spirit of AE2.
- The frequency is saved in the tunnel (`FactoryCoreTunnel`, schema 1). Breaking an input
  orphans its outputs, which then carry nothing until re-paired.

## Alternatives

- **A tunnel per resource (AE2):** rejected. It means more blocks for one idea, and "attunement"
  is a known AE2 stumbling block.
- **Routing through storage (an Importer-Exporter pair):** this already exists through ports. Tunnels
  exist for direct, unbuffered, face-to-face connections.
