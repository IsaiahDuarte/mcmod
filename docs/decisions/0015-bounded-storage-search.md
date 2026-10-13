# ADR 0015 — Bounded all-key storage discovery and search

Status: accepted P3 server query design; component/headless checks passed as
recorded in the implementation plan. Terminal packets/screens, full client/server restart and P7 performance
acceptance remain unfinished. This does not complete A01–A17.

## Context and decision

A03/A04/A11/A12 require a searchable authorized view with exact identities/counts,
backing deduplication, reserved/available distinctions and bounded client work.
The existing exact-key catalog and transfer routes remain the execution path.
They cannot enumerate unknown keys without a new read-only boundary. Snapshotting
all ledgers or rescanning every foreign slot synchronously would bypass the shared
work budget and create unnecessary copies.

Retain ResourceLedger's HashMap accounting and add one derived sorted key reference
per occupied identity. Catalog pages seek an exclusive exact-key cursor, read at
most sixteen rows plus one lookahead, and retain at most 128 KiB estimated row
metadata. The index is maintained only on type creation/removal and rebuilt from
owned contents on restore. It adds no quantity owner or save format. Existing
per-device/world entry limits bound the index. Hot updates keep hash lookup;
new-type/last-item changes additionally update the tree.

WorldStorageCatalog reads one authorized backing's key/slot page through the
existing shared sixteen-credit query admission. A task examines at most eight
metadata owners and one bounded ledger/provider page. Buffers are not stock.
Provider observation remains advisory and nonmutating; item windows and complete
supported utility enumeration are limited to sixty-four slots/tanks. An oversized
provider fails UNSUPPORTED rather than silently producing a complete partial view.

WorldStorageSearch collects these pages incrementally, filters namespaced registry
identity text case-insensitively and provides bounded stable-order result pages.
At most sixteen transient searches share sixteen MiB of estimated metadata; a
search retains at most 65,536 backing/key contributions, 5,120 backing references
and 32,768 discovery pages. Idle sessions expire after 200 server ticks; close,
fault, cancellation and expiry release accumulated metadata. Limits reject with
TECHNICAL_LIMIT, discard partial rows and must be explained by the terminal UI.
They do not impose a gameplay storage type/capacity limit.

Apply each backing/key's full hold once after summing its observed slot pages.
Aggregate totals/reserved/available and known energy capacities use BigInteger,
as selected in ADR 0002. Per-backing observed sums remain checked longs. Availability
is the sum of each backing's nonnegative total-minus-held, so an over-held foreign
backing cannot consume another backing's availability. Observed quantities are
not reservations or authoritative transfer forecasts.

## Authorization and alternatives

Cursors/sessions retain exact authenticated actor, full accepted topology scope
(region/generation/root/segment/node) and canonical grant generation. Each queued
page and every result retrieval rechecks them and current VIEW. Topology rebuild,
revocation/ownership/grant edits, another actor and stale endpoint generations
cannot inherit a retained view. Provider reads follow scope checks; parent/operator
queries never probe child-private stock. No raw cursor/provider handle goes to a
client. A future terminal keeps them behind its server-owned menu/session state.
Error-only failed sessions remain readable by their original actor after a fault
invalidates topology: they contain no partial stock and preserve the actual failure
reason rather than masking a handler fault as stale data.

A whole-network snapshot was rejected for unbounded tick work/copies and weak
invalidation. Independent provider threads were rejected because Minecraft world
access and handlers belong to the server thread. Treating every slot-page hold as
an independently subtractable reservation would double-count foreign claims.
Using a long for aggregate queries would contradict ADR 0002 and wrap large
Infinite-cell views. Persisting search sessions would confuse advisory data with
owned stock and require needless migrations.

## Consequences and evidence

Pages/results are live observations across time, not one atomic network snapshot.
A refresh restarts enumeration; keys inserted before an exclusive cursor can be
seen on that refresh. Transfers still revalidate exact identity, current loaded
stock, grants, reservations and destination space. Offline caches, display-name/
tag search, quantity sorting, terminal controls and client deltas remain later
player-path work; registry-text filtering/stable ordering are implemented now.

CatalogPageTest covers claims, component identity, metadata limits, deletion,
offline/stale leases, generated mutation/restore sequences and exact totals beyond
signed long. StorageSearchGameTests covers real multi-slot sources and holds,
private providers, queued revocation, retained-view invalidation, typed fault
reporting, close/expiry and FE/mB units/capacity. The standalone ledger comparison
uses the prior production ledger from d36268b and records first-page allocation/
latency and existing-key/type-churn tradeoffs. Required checks and concrete final
measurements belong in the implementation plan/evidence files, not inferred here.
