# ADR 0039 — Wireless access point and handheld terminal

Status: selected; native headless evidence. Paired links, unload of a held session's
access point across chunk boundaries, and graphical checks remain open.

## Decision

A **Wireless Access Point** is a wired network member (terminal role) with a 20,000 FE
buffer, 1,000 FE/tick input and a 64-block spherical range in its own dimension. A
**Wireless Terminal** (handheld, 20,000 FE, 1,000 FE/tick charge) is bound by using it
on an access point; the binding stores only dimension and block position.

A handheld session reuses `TerminalMenu` through the `TerminalAccess` interface. Every
tick it re-checks: the bound handheld is still in the same hand, the access point block
entity is current and loaded, the player is in the same level within range, and the
player has View at the access point. The access point pays 16 FE per open tick; each
mutating action (withdraw, deposit, return, fill/empty/charge/discharge, encode, grid
craft, job start) costs 64 FE from the handheld and is refused with `no_power` and no
effect when unaffordable. Failure of any check closes the session.

Player-inventory endpoints use the access point as origin; `WorldPlayerInventories`
extends the 8-block reach to the access point range only for the registered session's
origin and clears it when the session closes or the player disconnects.

Access points change player access only: they do not discover machines, route
resources, or load chunks.

## Alternatives

A separate wireless menu would duplicate authorization and throttling; reuse keeps one
path (R04). Storing grants or stock on the item was rejected (credentials stay
server-side).

## Consequences

Wireless throughput is the same scheduled path as wired, so equivalent-tier wired
throughput is never lower (A16). Paired wireless links (network-to-network) are a
separate slice.
