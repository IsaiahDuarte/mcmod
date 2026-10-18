# ADR 0040 — Paired wireless links

Status: selected; native headless evidence. Per-resource link rate caps, unload-while-
linked GameTests, measured wired/wireless throughput and graphical checks remain open.

## Decision

A **Wireless Link Endpoint** is placed with its back face against a gateway. Two
endpoints in the same dimension within 256 blocks are paired one-to-one by sneak-using
one and then the other within 60 seconds. Pairing needs Manage at each endpoint whose
network is valid, or ownership (the placer) of an endpoint whose branch has no root;
re-pairing clears the previous partner.

Discovery (`WorldWireReader`) gives an endpoint a wired back-face port and, only while
the pair is **active** (paired, back face on a gateway, in range, powered), a second
port to its partner. Edges need reciprocal ports, so both ends must be active. The pair
therefore behaves as one cable edge between two gateways: the ordinary generation,
multiple-parent, cycle, bypass and gateway-policy validation applies, and no
all-to-all mesh is possible.

Each endpoint has its own 20,000 FE buffer (1,000 FE/tick input) and pays 32 FE per tick
while paired. When power is lost or regained, both ends are re-discovered. An unloaded
partner is read from cached geometry as an unloaded cable, which suspends the affected
segment instead of losing staged contents. Endpoints never load chunks; every cross-
chunk lookup uses `getChunkNow`.

## Alternatives

A link-specific router with its own rate caps would duplicate the transfer scheduler.
Modelling the link as a topology edge keeps authorization and visibility in one place.
Per-resource caps are deferred to a later change on the scheduler side.

## Consequences

Transfers over a link currently use the same scheduled throughput as cables, so wired
throughput is equal, not higher, for now. R11's per-pair resource caps are still to
be implemented. Operating power is accounted separately from any delivered energy, and
endpoints can't power their own bootstrap.
