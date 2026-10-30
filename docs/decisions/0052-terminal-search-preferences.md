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
parity slice P2). A Tall flag (bit 8, playtest H2) rides in the same integer: it sets
only the client grid height, `mode` masks it out, and an unknown bit is still refused.
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

## Amendment — recipe-viewer search sync (2026-10-30, ergonomics plan Q17)

A third flag, `SYNC` (bit 16), rides in the saved `Sort` integer like `TALL`: it never changes
order or schema, `mode` masks it out, and bit 32 and up are still refused. While it is set, the
client mirrors the terminal search with a recipe viewer's search box both ways: a change typed
in the terminal is written to the viewer, and a change in the viewer is written to the terminal
search box, which then searches through the normal debounced SEARCH command. Turning it on copies
the terminal search to the viewer. The viewer is reached through `RecipeViewerSearch`, a client
bridge the optional JEI plugin installs when its runtime is ready (EMI can install one after
Q16), so the terminal never loads JEI classes. The **J** tool appears only when a bridge exists,
so the search row is unchanged without JEI. Alternatives: a client-only config option (rejected:
the other terminal preferences are per player and saved on the server, ADR 0052), and syncing
only from the terminal to JEI (rejected: the slice asks for both ways).

`FOCUS` (bit 32, ergonomics Q18) is a further client-only flag in the same integer: when the
first server view arrives, the client focuses the search box. Bit 64 and up are refused.

## Amendment — recently crafted items first (2026-10-30, ergonomics plan Q109)

Items crafted at a terminal lead its Items grid until the screen closes. The menu keeps up to
nine keys (`WorldStorageSearch.MAX_PINNED`), newest first: a grid craft's result once at least
one craft succeeded, and a job's output when it is started from this terminal's Jobs tab (the
row then appears first as soon as there is stock, or as a craftable ghost). The search orders
pinned rows first, in pin order, then everything else by the chosen sort; Reversed applies only
to the rest. Pins still have to match the search text, and they are not saved.

Pinned rows take the first page's leading slots of the one sorted index, rather than a separate
strip: offsets, windows and `NEXT` continuation keep working unchanged, and every row still
appears exactly once. A separate strip was rejected because it would need its own paging and
would show a row twice. Saving pins across openings was rejected because the slice asks for
"until the screen closes" and saved per-player lists would need a schema change.
