# Authenticated player inventory transfers

P3 shared-transfer endpoint implemented; physical terminal/menu/client controls
remain missing. This is not a complete player storage loop. See
[player boundary decision](../decisions/0016-player-inventory-transfers.md),
[shared transfers](WORLD_STORAGE_TRANSFERS.md) and [search](STORAGE_SEARCH.md).

## Identity, authority and selected slots

`WorldStorageTransfers.inventory(serverPlayer, slot)` creates a server-only
PlayerInventory endpoint for PlayerList's exact live ServerPlayer. Slot is 0–35
for one selected main-inventory slot, or -1 for the whole 36-slot destination.
Whole inventory cannot be a fresh extraction source. Armor/offhand/cursor/crafting
slots are excluded. Invalid slot/parameters throw IllegalArgumentException;
unauthenticated/offline/dead/wrong-server players or technical binding/rate-owner
limits throw IllegalStateException. All calls belong to the server thread.

The backing UUID is `TransferContext.playerBacking(worldUuid, playerUuid)`, derived
from both UUIDs and the fixed player-inventory-v1 domain. It must not alias canonical
stock/nodes. Each live player binding has a positive random connection nonce.
At most 256 live bindings/rate owners exist; logout releases matching bindings and
indexed native transfer references without deleting canonical staging. Stale body
logout cannot invalidate a replacement body with the same principal. Budgets reset
on server tick change, not connection/menu/wrapper creation, and share 128 item
units separately for input/output per backing/tick. No independent contents copy,
claim catalog or globally enumerable player stock is created.

Every request checks that the operation actor matches the endpoint's principal,
nonce/backing/current PlayerList object, live state and server, current dimension,
and distance within eight blocks of the server-derived origin. Captured ports
recheck the same object/binding/dimension/distance before every operation/slot.
Another actor or operator flag cannot spend that inventory. A future menu must
also validate its current lease/menu/session and selection revision; this endpoint
alone is not an authenticated client packet boundary.

The player's local source does not require network WITHDRAW merely to deposit its
own items. A digital/foreign network destination still requires current DEPOSIT.
Network withdrawal requires current WITHDRAW at the network source and insertion
into that same actor's inventory. Catalog search requires VIEW independently.
Existing catalog-based priority routes require their documented VIEW authority;
deposit-only selection/control remains a terminal frontend requirement.

## Native inventory and resource accounting

Simulated/actual insertion fills exact matching stacks before empty slots, scanning
at most two passes over 36 slots. Every slot respects native/container stack limits,
capped at 64; existing oversized stacks are not truncated. Source extraction uses
one selected slot and rechecks its exact item/components, offered count and shared
rate. Only known actual removal/acceptance spends allowances. Native reads/offers
are detached where reconstructed; different/unsupported existing metadata is not
overwritten. Unsupported offered identities reject before queued extraction.

Use the existing `submit` endpoint overload. Item quantum is at most 128 units,
with the existing shared sixteen-credit scheduler/queue/staging admission. PLAYER
requires ITEM; fluids/FE reject WRONG_RESOURCE before queueing. Full inventories,
changed source slots, revocation, distance/logout, actor mismatch and rate limits
leave resources with their current owners. Any unresolved canonical record involving
the player backing blocks a fresh transfer with RESERVED, including an interrupted
call with zero known staging. This avoids accumulating new extraction over evidence.

```java
var actor = new NetworkPermissions.Actor(player.getUUID(), player.hasPermissions(2));
var source = transfers.inventory(player, inventorySlot);
transfers.submit(UUID.randomUUID(), actor, serverOrigin, source,
    new WorldStorageTransfers.Digital(permittedCell), exactKey, 64, result -> {
  // Observe actual delivered/staged units; never assume the requested amount moved.
});
```

Destination preflight precedes extraction. Known partial outcomes stay in canonical
TransferRegistry staging; known completion retains actual counters for caller
retirement. This API creates neither items nor creative-mode exemptions. Callback
work must be bounded/nonthrowing; callers must retire only known COMPLETE/NONE
records and retain incomplete/uncertain operation IDs.

## Context persistence and owned recovery

World schema ten adds PLAYER to strict context schema three. Schemas one/two remain
byte-for-byte unchanged for prior digital/foreign/buffer-only operations; world
schemas one through nine migrate without changing tables/counts/claims/context.
New player contexts in older envelopes reject and preserve raw authority data.
Player endpoint fields are principal UUID, derived backing, accepted connection
nonce and configuration 1–36 for slots 0–35 or 37 for the whole destination.
Validation requires item staging, the operation's exact principal and derived
world/player identity; whole source, malformed/future/foreign identity data rejects.
Context metadata grants no permission and saves no second inventory. Unknown/future
world/context payloads remain quarantined rather than allocating replacements.

`progress(id, authenticatedOriginalActor, returning, callback)` only progresses
existing known staging. It can explicitly bind the current live body for the same
world/player identity after a connection changes; accepted context stays unchanged.
It never extracts a refill or adopts another actor. Normal delivery rechecks current
network source/destination permissions and original origin/root/segment; source-slot
cleanup does not require revoked resource grants, but retains current identity/
range/dimension/scope checks. A changed/full original slot retains staging; free it
and request return again. Unknown pending calls remain UNCERTAIN and cannot replay
or refund. Automatic buffer-import recovery never adopts PLAYER destinations.

Player files and world authority save independently. Fixed context/migration and
compressed staging checks are not proof of crash atomicity, complete reconnect/
restart recovery or independently interrupted file safety. Those remain D03/P7
release work; do not infer certainty from an old matching coordinate/player UUID.
No user-facing reconciliation or recovery menu exists yet.

## Evidence and next work

PlayerEndpointPersistenceTest freezes context-three identity/slot/nonce, old schema
one/two bytes, actor/kind/backing/whole-source/future rejection, exact nonempty
schema-nine migration and compressed staging reload. Eleven native GameTests use
the pinned platform's real PlayerList mock-login helper and observable inventory
items: deposit, full inventory/equipment exclusion, partial matching fills, stale
slot/other actor, deposit-only grants, shared directions/rates, revocation/distance,
owned blocked-slot cleanup after revocation, catalog exclusion, logout and pending
intent non-replay/fresh extraction blocking. These are headless platform checks;
terminal packets/screens, survival interactions, full restart/independent saves,
multiplayer and client verification remain pending.
