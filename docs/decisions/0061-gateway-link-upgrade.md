# 0061 — Wireless link as a Gateway upgrade

Status: accepted 2026-10-24 (UX plan U13, gates UD2 and UD7). Amends
[ADR 0040](0040-paired-wireless-links.md) and keeps [ADR 0050](0050-shared-wireless-throughput.md).

## Context

A Wireless Link Endpoint is a separate block. It only works with its back face on a
gateway's upstream or downstream face, so players had to place and power a second
block for every gateway they wanted to link. UD2 decided to fold the link into the
gateway as an upgrade. UD7 removes the endpoint block with no migration.

## Decision

- **Item.** `factorycore:wireless_link` is an upgrade. Using it on a gateway's
  upstream (amber cross) or downstream (blue dot) face installs a link on that side;
  using it on any other face is refused. Installing needs Manage at the gateway, the
  same right that pairing needs. One gateway holds at most one link.
- **State.** The link lives in the gateway's block entity as a `LinkModule`: side,
  partner gateway position, owner, a 20,000 FE buffer (1,000 FE/tick input), 32 FE/tick
  while paired, the throughput tier and recovery data. This is exactly the endpoint's
  former state, moved, with every rule unchanged: same dimension, range 256,
  one-to-one pairing within 60 seconds, power loss suspending the pair, never loading
  chunks, and an unloaded partner suspending the segment.
- **Power and pairing.** While a link is installed, the gateway accepts FE from any
  face. Pairing is sneak-use on one linked gateway, then on the other, with an empty
  hand. Sneak-use on a gateway without a link still opens its policy screen.
- **Discovery.** `WorldWireReader` adds one port to the linked side's vertex
  (upstream or downstream) pointing at the partner gateway, only while the pair is
  active. The edge remains reciprocal and behaves as one cable edge, so the
  generation, multiple-parent, cycle, bypass and policy checks are unchanged. The
  per-pair throughput caps of ADR 0050 are unchanged.
- **Overdrive and break.** Overdrive installs on a linked gateway as it did on an
  endpoint. Breaking the gateway drops the link item and any installed Overdrive, as
  breaking an endpoint did, and clears the partner's pairing.
- **Removed.** The `wireless_link_endpoint` block, item, block entity, recipe and
  assets (UD7). The link item takes over the endpoint's recipe.

## Alternatives

- **Keep the endpoint block** and only shorten its setup. UD2 rejected this.
- **Link as a separate block entity attached to the gateway.** This would keep two
  persistence owners for one physical block; rejected in favour of a module inside
  the gateway entity.

## Consequences

Wireless tests and the dedicated lifecycle suite move from endpoint blocks to linked
gateways. The gateway's saved data gains an optional link section, which is absent
for unlinked gateways. Presentation and guide content show one fewer block.
