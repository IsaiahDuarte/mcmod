# Authorized storage discovery and search

The P3 server-side all-key catalog and transient search collector are implemented.
They support items, mB and FE with exact components and arbitrary-precision aggregate
counts. The authenticated terminal is its network frontend. See
[search decision](../decisions/0015-bounded-storage-search.md) and
[exact-key routing/transfer contract](WORLD_STORAGE_TRANSFERS.md).

## Bounded digital key pages

`ResourceLedger.catalog(after, limit)` runs on its owning thread. `after` is an
exclusive exact-key position or null; `limit` is 1–16 and wrong kinds reject.
Entries contain key plus total/reserved/available/loaded Stock. Each call reads at
most sixteen keys plus one lookahead and admits at most 128 KiB estimated metadata
(128 + three registry-ID characters + component bytes per row). Even a maximal
65,536-byte key fits one row, so continuations advance. The immutable page's next
key is nonnull only when more data remains. Counts/claims/dirty state do not change.

Hash accounting and a derived TreeSet of occupied keys share the same owner.
Quantity-only changes leave the index untouched; positive first insertion/last
extraction update it. Restore rebuilds it from validated contents. No index is
serialized and world/ledger schemas are unchanged. Catalog/entry ceilings already
bound its size. `DeviceRegistry.catalog(handle, after, limit)` also validates the
current lease and marks unrouted/offline rows unavailable while retaining owned
counts. These core APIs authenticate no player; platform callers must authorize
before reading them.

## Scheduled all-key source pages

`service.allKeys().page(id, actor, origin, kind, cursor, completion)` returns shared
scheduler admission immediately. UUIDs and actor flags come from the trusted
server adapter. Origin is a server-derived NodeRegistry.Position; kind selects
ITEM/FLUID/ENERGY. A null cursor starts at the first digital owner. Completion runs on the server
thread and must remain bounded/nonthrowing. Callback Page
contains typed reason, at most sixteen Rows, its source endpoint (if any), source
completion and the next server-only cursor. Rows have exact key, observed whole
units, full backing/key reserved amount and nullable observable capacity. Items
use item units; fluids use mB; energy uses FE. Capacity is reported for observable
energy banks/providers only; fluid capacity attribution is intentionally absent.

A scheduled task examines at most eight metadata owners, then reads at most one
ledger page or sixteen provider indices. The owners come from the asking network
([ADR 0067](../decisions/0067-storage-discovery-by-network.md)): its devices
(`WorldNetworks.storageDevices`, the cells in its drives and its energy banks) and its connectors
(`storageConnectors`), so a page never visits another network's endpoints. Only while the network
is still being prepared does the read fall back to the world registries. Continuing a source retains its exact
lease/configuration/provider token and exclusive key or slot offset. Digital
owners finish before structural connectors; source ordering is stable UUID order.
Complete supported foreign enumeration covers at most sixty-four configured item
slots/tanks; oversized utility providers terminate UNSUPPORTED, never silently
truncate a supposedly complete view. Empty metadata pages may have continuations.
Opaque/overlapping unsupported registrations are not invented as observable stock.
Only currently loaded validated stock is listed; there is no offline cached listing.

Cursors retain actor UUID, kind, exact full topology scope and grant generation.
Every queued read validates them before provider access and before publishing rows.
Parent/operator views exclude child-private providers without marking, capability
probing or stock reads. Buffers are excluded, including from alias registrations.
Unknown/conflicted/offline metadata owners are skipped; an active source that
changes becomes stale. No cursor or native handle is an untrusted client credential.
Never serialize the raw cursor/endpoint records into a client packet.

```java
service.allKeys().page(UUID.randomUUID(), actor, serverOrigin,
    ResourceKind.ITEM, null, page -> {
  if (page.reason() == OperationResult.Reason.OK) {
    // Retain page.next() on the server and schedule it separately if nonnull.
    page.rows().forEach(row -> System.out.println(row.key() + " " + row.total()));
  }
});
```

Rows from different slot pages can describe the same backing/key. Their total
amounts add, but each Row.reserved is the full backing/key hold and must not add
repeatedly. `CatalogCounts.observe(backing, key, observedDelta, held, capacity)`
maintains incremental checked per-backing totals and applies the latest observed
hold once. Its result pages expose BigInteger total/reserved/available/known capacity.
Availability sums max(0, backingTotal - backingHeld). A foreign hold exceeding its
current stock remains visible without subtracting from another source. Per-backing
long overflow rejects TECHNICAL_LIMIT before changing counts. Global sums never
saturate, wrap, use floats or reject merely for exceeding Long.MAX_VALUE.

## Transient search lifecycle

`service.search().start(id, actor, origin, kind, text)` creates one server-owned
session and schedules discovery. Text is at most 128 UTF-16 characters, matched
case-insensitively against namespaced registry identity or server-language display
name (including custom item names), using Locale.ROOT. `@text` matches a registry
namespace (mod ID); `#text` matches loaded item/fluid tag IDs by substring. Energy has
no tags. The tag walk rejects resources exceeding 1,024 tags as UNSUPPORTED, never
silently truncates. Exact component variants stay separate. This does not inspect
arbitrary component fields or translate names using the client's language.

The overload `start(..., text, sort)` accepts NAME (0, default) and QUANTITY (1).
Name order is case-insensitive A–Z, using at most 256 display-name characters;
quantity order uses descending exact aggregate total, then name. Both use exact
resource identity as the final stable tiebreaker. An incremental ordered index
updates with each bounded source page; results never sort the whole catalog in a
tick. Each retained aggregate index entry adds 1,024 estimated bytes to the shared
session budget. There is no uncharged global name cache.

`result(id, actor, afterKey, limit)` authenticates the same actor and current scope/
grant generation. Limit is 1–32 with an estimated 128-KiB result metadata ceiling.
While scanning it returns complete=false, observed page count and no partial rows.
A successful complete session returns immutable exact aggregates in the selected
order and an exclusive exact-key next position. The anchor must still belong to
that session; an absent anchor rejects DENIED and the terminal returns to Home. Every result
read refreshes the idle timer. Known topology or grant changes invalidate cached
results even if the original owner still has VIEW. Another actor receives DENIED
and cannot close/reuse a session. This server API accepts no client-supplied root,
operator flag, raw cursor or endpoint.

```java
UUID search = UUID.randomUUID();
var admission = service.search().start(search, actor, serverOrigin,
    ResourceKind.ITEM, "iron");
// Poll from the authenticated server menu, which retains the search ID/cursors.
var page = service.search().result(search, actor, null, 32);
if (page.complete() && page.reason() == OperationResult.Reason.OK) {
  page.rows().forEach(row -> System.out.println(row.total().toString()));
}
service.search().close(search, actor);
```

At most sixteen sessions share sixteen MiB of estimated retained metadata. Each
backing/key contribution charges 256 + three registry-ID characters + component
bytes; source references charge 384 and sessions 2,048. Maximum contributions are
65,536, source references 5,120 and observed pages 32,768 per session. These are
technical query limits, independent of tier capacity/type storage. Exceeding them
terminates TECHNICAL_LIMIT with no partial rows; narrow the query or release other
sessions. Failed sessions release accumulated key/source references and retain
only their error until closed/expired. Only the original actor can inspect this
error, including after a fault invalidates topology; it exposes no stock.

`close` removes a matching actor's session and cancels undispatched page work when
outside execution. Idle sessions expire after 200 server ticks on the next service
access. Cancellation/removal/expiry clear accumulated metadata immediately even
when an already queued closure still exists; that closure performs no further
collection. A missing canceled page is not interpreted as a complete search.
Restart discards all sessions; owned stock/grants/staging do not change.

Observations span multiple ticks and are advisory. Stock/holds can change between
pages; a refresh obtains a new view. Exclusive cursors can miss newly inserted keys
before their position until refresh. Terminal transfers must use shared current
revalidation/accounting, never cached available counts. Frontends must cap payloads,
keep cursor/key tokens server-side, bound polling/deltas and present progress,
staleness/quota/provider errors. Terminal packets/screens and actual client actions
are implemented; full independent-file recovery and workload evidence remain partial.

## U3 display windows and item capacity

`window(id, actor, offset, limit)` is the terminal's server-only display projection.
Offset is 0–65,536 and limit 1–256; invalid bounds throw. It shares `result`'s scope,
principal/grant checks and idle lifecycle. The sorted index is materialized at most
32 references per shared scheduled query after catalog collection, with the existing
1,024-byte per-entry index charge covering these references. No partial index is
presented as complete. Completed windows use bounded random access, with cached
names and exact counts; they do not copy native components into client packets.
Cancellation, expiry or failure clears the index with the rest of the session.

The capacity summary is independent of search text and window. When an authorized
source first appears, a digital item's exact total/capacity/infinite flag is read from
its canonical owner. External item totals add once per observed provider page; their
effective item-unit capacity is marked unknown because stack limits/filters can vary.
The existing source alias/conflict checks prevent counting a backing twice. Empty
cells still contribute capacity. Used includes reserved stock; totals use BigInteger.
Only loaded validated authorized sources are observed; no private child capacity,
implicit chunk load or offline estimate is introduced. Summaries span ticks and are
advisory, just as the corresponding catalog is.
