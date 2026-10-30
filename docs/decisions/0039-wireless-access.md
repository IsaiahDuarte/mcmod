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

## Amendment — Pick Block from the network (2026-10-30, ergonomics plan Q18)

A survival player's Pick Block on a block they have none of, with a bound Wireless Terminal
anywhere in the main inventory, sends `WirelessPick.Pick(pos)`. The server reads the block itself
(within the player's reach), finds the bound handheld and checks what a handheld withdrawal
checks: same dimension, access point loaded and current, within its range (Q15), holding a tick
of FE, and Withdraw at the point. It then routes up to one stack into the selected hotbar slot
if empty, else the first empty hotbar slot, selects it when items arrive, and charges the
handheld's 64 FE action cost. The handheld need not be held, unlike opening the terminal; the
pick is one bounded move, not a session, so the access point's per-tick cost does not apply.
Alternatives: requiring the handheld in hand (rejected: then Pick Block could not land in that
slot) and taking a single item (rejected: vanilla creative pick gives a stack).
