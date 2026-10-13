# Physical item storage terminal

The access-only Storage Terminal exposes the existing wired TERMINAL role and
shared item search/routes/player inventory endpoints. It stores no inventory,
network identity, grants or transferable authority. Copies are access shells;
world schema ten is unchanged. [ADR 0017](../decisions/0017-storage-terminal-menu.md)
records alternatives and scope. All mutable operations run on the server thread.

## Authentication and lifecycle

Opening requires the exact live PlayerList body, loaded captured block entity,
same dimension, distance at most eight blocks and current View permission. The
server creates a random per-open session UUID and keeps the exact physical holder.
Every command requires the authenticated sender's current menu, matching menu ID
and session; mutations also require the current server view revision. Replacing,
unloading, closing, disconnecting or leaving range invalidates that holder/session.
Grants and the query's complete captured topology scope are revalidated. Read/search
payloads supply no actor, operator, root, segment, provider or resource key.

The bounded command contains action, own slot/row token, amount 1–64, search text
at most 128 characters and a 32-byte SHA-256 fingerprint. A deposit resolves the
actual own slot 0–35, normalizes its exact components and compares the fingerprint
before admission; the player port rechecks that exact key before extraction. Vanilla
quick-move returns empty, so stale vanilla shift-click packets cannot substitute
a changed item. Ordinary own-inventory rearrangement uses native container slots.
Armor, offhand, cursor and crafting slots are excluded from network transfers.

Each menu accepts at most one action every two actual server ticks and one transfer
in flight. Eight server-retained exact row keys are the only withdrawal selections;
read-only paging/search is refused while an action runs. Search polls at most once
per five ticks. All observations and transfers use shared queues/limits/permissions,
not GUI-owned stock counters. Deposits need View and Deposit; withdrawals View and
Withdraw. Current View is required to use this physical menu even for owned return.

Closing releases the transient query/selections and cancels an undispatched route.
Accepted staging is retained canonically under its original actor. Completed,
zero-staged/no-pending records retire. Unresolved player backing blocks new moves.
Return finds only that player's canonical operation and uses guarded known progress,
returning to the original source without re-extraction. Uncertain calls remain frozen;
loss of View/original topology may require the unfinished administrator recovery UI.
Independent player/world-file interruption safety remains unverified.

## Projection and controls

The server projects at most eight rows: token, base item registry icon, plain name
at most 96 characters and exact decimal total/held/free counts at most 32 characters.
Full components, provider references and search cursors remain server-only. The
client sends back only the row token under that revision. Exact identity and stable
resource order come from the server query. Current counts are advisory, not claims.
The query's bounded session memory and idle expiry are specified in
[storage search](STORAGE_SEARCH.md); this menu touches its query while open.

The native 280×232 screen includes registry-text search, Home/Next paging, Take 1,
Take 64, Return and native main inventory slots. Shift-click deposits up to 64;
1–8 select rows when the search field is unfocused, Enter submits focused search,
and action buttons use native keyboard focus/tooltips. Labels abbreviate counts
using integer division; row hover shows exact decimal amounts. Icons deliberately
omit native components. Metadata-rich tooltips, display-name/tag search, sorting,
offline cache, crafting/fluid/energy/job tabs remain unfinished release work.

Example: craft the terminal with four iron, three glass and two redstone; connect
it beside an owned controller/drive, use it, search `iron`, select an exact row,
and Take 1. Shift-click the resulting own slot to deposit it again. Recipe unlock
uses glass. [Player guide](../PLAYER_GUIDE.md) documents the player/recovery limits.

## Verification limits

TerminalPacketsTest covers actual codec round trips, exact large decimal counts,
count-independent/detached exact-key fingerprints and malformed/oversized rejection.
The compiled architecture check excludes client dependencies from common/server
classes. TerminalGameTests use actual native block interaction/menu creation and
real PlayerList bodies, with the loader's explicit test-only mock channel negotiation.
They cover exact deposit/withdraw, full inventory, stale sessions/revisions/slots,
queued close/replacement, range/revocation, the full vanilla hopper/buffer/cell/menu/
player loop and starter recipe/unlock. They do not establish real client rendering,
GUI scales, keyboard/mouse packets, multiplayer, chunks/restart, independent saves,
survival progression or representative performance. Those are required release evidence.
