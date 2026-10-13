# Resource selectors and stock-goal planning

P3 prerequisite implementation, not a deployed preset or player settings screen.
The physical buffer now consumes these selectors in guarded Import/Paused policy;
[canonical integration](BUFFERED_INTERFACES.md) records save/rate/player controls. These
shared components are intended for export/stock controls and later SDK/crafting
frontends; buffered Export/Stock now consumes these models; full multi-rule settings and
remaining machine-interface/client integration stay unfinished.
The original selector/planner checkpoint introduced no schema change; later
buffer integration uses nested buffers-v3 under world schema ten.

## Immutable filter contract

`ResourceFilter(kind, mode, terms)` accepts one ITEM/FLUID/ENERGY kind, ALL/INCLUDE/
EXCLUDE mode and an immutable snapshot of at most sixteen terms. Registry terms
match registry identity regardless of components (the default selection policy).
Exact terms match the complete immutable ResourceKey. Tag terms deliberately match
current membership through a supplied resolver; they never merge/change stored
identities. All matches stay within the selected kind.

ALL requires no terms. Empty INCLUDE matches nothing; empty EXCLUDE matches all
of its kind. Terms within a list are OR-ed, with exclusion negating that result.
Registry/tag IDs require a namespace, valid ResourceKey ID syntax and at most256
characters; tag IDs omit the leading `#`. Exact terms must share the filter kind.
Energy has only `neoforge:energy`; energy tags are rejected, with no implicit item
charge/chemical conversion. Missing native tags/registry entries match nothing.

Admission rejects over sixteen terms or over256KiB of conservatively charged
metadata. `bytes()` includes the256-byte definition charge and each256-byte term
charge, three bytes per ID character and exact component bytes. Future canonical
owners must include this charge in their shared memory/persistence admission;
this local quota is not permission to bypass the existing shared buffer budget.
Limits are technical bounds, not a gameplay cell type-count limit. Null inputs
fail, invalid/oversized definitions throw IllegalArgumentException before admission,
and the caller's list cannot mutate an accepted filter.

`matches(candidate, tags)` makes at most sixteen term probes, stops on the first
match and never probes tags for another resource kind. The pure definition contains
no inventories, quantities, threads or scope authority. Resolver exceptions
propagate; frontends must report/block them before extraction, never broaden to ALL.
A match is selection only: current scope/permissions/leases/reservations/capacity
and exact identity must still pass through shared scheduled transfer admission.

The platform `PlatformFilterTags(server)` reads current item/fluid holder tags,
without world scans, chunk loading or cached counts. It requires that server's
owner thread for every call. It uses current bindings each time, so registry/tag
reloads affect matching without rebuilding filter objects. Upcoming deployment
integration must invalidate/wake sleeping rules on reload and recheck before each
mutation; pure live matching alone does not implement that lifecycle.

## Stock planning contract

`StockTarget(kind, target, quantum)` uses individual items, mB or FE. Target is
nonnegative through Long.MAX_VALUE. Quantum is1–128 items or1–1000mB/FE, matching
the existing shared transfer request bounds; it is not a throughput guarantee.
The physical buffer's64-item directional budget still applies when integrated.

`missing(resident, inbound)` returns min(quantum, max(0, target-resident-inbound))
without summing resident/inbound and overflowing. Both inputs are nonnegative
through Long.MAX_VALUE. Resident is actual physical stock in the intended target,
including items reserved for someone else: reservations do not erase physical
stock or authorize its consumption. Inbound includes known owned staging for the
same key/target. Unknown/quarantined writes must block new operations, never be
represented by zero or guessed counts. This function owns no resources and makes
no reservation; competing goals remain best effort. Execution must revalidate
observations, authority and accounting under the shared scheduler.

Example, after authenticated server discovery of an item endpoint:

```java
// Invoke on the owning server thread after authenticated endpoint observation.
static long requested(ServerPlayer player, ResourceKey exactObservedKey,
                      long actualResident, long knownInbound) {
  var selection = new ResourceFilter(ResourceKind.ITEM, ResourceFilter.Mode.INCLUDE,
      List.of(new ResourceFilter.Registry("minecraft:iron_ingot")));
  var tags = new PlatformFilterTags(player.server);
  var goal = new StockTarget(ResourceKind.ITEM, 64, 64);
  return selection.matches(exactObservedKey, tags)
      ? goal.missing(actualResident, knownInbound) : 0;
}
// Imports: java.util.List, ServerPlayer, ResourceFilter, ResourceKey,
// ResourceKind, StockTarget and PlatformFilterTags from their documented packages.
// The returned amount is planning only. Enqueue through existing guarded routes;
// source keys remain exact even for broad matching, and all execution checks apply.
```

## Evidence and remaining integration

Four ResourceFilterTest cases cover component distinctions, empty/complement/live
membership semantics, immutable/byte/term/kind admission, bounded probes, resolver
failure propagation and5000 generated candidate evaluations (seed0xF117E221).
Three StockTargetTest cases cover resident/inbound/reserved semantics, units/quanta,
negative/zero/full-long boundaries and10000 generated plans compared with unbounded
BigInteger arithmetic (seed0x570C221). Three native ResourceFilterGameTests check
actual loaded vanilla item/fluid tags, component preservation, missing tags and
worker-thread rejection. These are component evidence only: no canonical filter
save/reload, actual automatic filtered import/export/stock, contention, tag-reload
wake, player controls/client or performance acceptance is claimed yet.
