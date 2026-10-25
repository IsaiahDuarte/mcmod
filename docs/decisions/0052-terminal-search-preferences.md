# ADR 0052: bounded terminal search, sort and player preferences

Status: implemented; headless, graphical and fresh-JVM restart checks passed 2026-10-19.

## Decision

R08/A11 requires display-name/registry/mod/tag matching, name/quantity sorting and
per-player preserved search/sort. Extend the existing scheduled authorized collector
rather than shipping a separate client catalog. Plain text matches names/IDs, `@`
selects namespace and `#` selects loaded item/fluid tags; matching is case-insensitive.
Names use the server language and custom item name. Name order uses at most 256
characters; quantity order is descending BigInteger total; mod order groups by registry
namespace, then name. Exact keys break ties. A reversed flag (bit 4) flips any mode; the
saved `Sort` integer keeps 0/1 meaning Name/Qty, so schema 1 is unchanged (amended by
parity slice P2).
An ordered index updates at most one bounded catalog page per scheduled callback,
with 1,024 bytes charged per aggregate. Tag walks reject more than 1,024 tags.

Keep accepted query/sort under schema-1 persisted player data, sync them in the
bounded terminal View, and reopen Items. Terminal payload registration advances
to protocol 2 because the View wire layout gains these fields; peers must use the
same protocol and old payload layouts are not guessed. Store no topology credentials or stock.
Unknown preference schemas are preserved until an explicit player edit. The
shared authorization/invalidation paths remain required on every read and action.

## Alternatives and consequences

Client-wide catalogs would send privileged component identity and duplicate server
state; sorting all results after collection would add an unbounded completion spike.
The incremental index costs bounded extra memory and logarithmic work per observed
row. Per-terminal state would violate per-player preferences. Locale-specific client
search would require a separate translated index or untrusted name projection;
server-language matching is documented. Client languages can still translate screen
controls. A disappeared sorted-page anchor returns to Home on revalidation.

## Evidence

Focused unit/packet and native menu tests are included. Normal save/reopen uses
NeoForge persisted-player data. The graphical tour and
fresh-JVM restart fixture now require display/mod/tag queries, name/quantity
buttons and persisted nondefault preference projection, with required captures.
They passed on 2026-10-19 ([evidence](../evidence/program-authoring-client-v1-2026-10-19.md)); this is partial R08/A11 evidence.
