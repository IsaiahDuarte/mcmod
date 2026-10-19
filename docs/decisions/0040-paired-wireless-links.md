# ADR 0040 — Paired wireless links

Status: selected; native headless pairing/power and actual endpoint chunk-unload
evidence. Shared resource caps extend this decision in [ADR 0050](0050-shared-wireless-throughput.md).
Measured wired/wireless throughput and complete graphical checks remain open.

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
Shared per-resource accounting is specified in ADR 0050 and applies to scheduled
transfers and direct container/crafting mutations.

## Consequences

Transfers share the scheduler with cables and additionally use per-pair limits
(256 items, 4,000 mB and 8,000 FE per tick in aggregate across directions).
Operating power is accounted separately from any delivered energy, and
endpoints can't power their own bootstrap.

## Endpoint eviction evidence

The required dedicated `runWirelessLifecycleServer` probe observes a genuine remote
chunk-unload event while the parent endpoint stays loaded. For 40 ticks, discovery
does not reload the remote chunk and cached cell stock/claims remain unavailable and
unchanged. Reload restores the original pair and cell lease before claimed extraction
resumes. [Local evidence](../evidence/wireless-lifecycle-v1-2026-10-18.md) records the
command and observations. This does not establish resource rate caps or interrupted
external-write recovery.
