# ADR 0017 — Physical storage terminal and authenticated menu

Status: accepted; P3 item/menu implementation has headless evidence, partial graphical item evidence recorded; remaining client/release verification pending.

## Decision

A Storage Terminal is an access-only wired block with no owned contents/grants.
Its block entity stores no stock or transferable authority. Physical holder object,
loaded chunk identity and a random server menu-session UUID invalidate stale opens,
replacement, movement and logout. Unlike a cell/buffer/controller it needs no
canonical portable ownership lease: copying a terminal cannot duplicate resources
or grant access. Wired geometry exposes the existing TERMINAL role at a stable
world/position-derived vertex; current root/segment/current player grants control
all actions. No persistence schema/table change is introduced by this shell.

Use one common AbstractContainerMenu and client-only screen registration. Player
inventory slots remain ordinary local slots; network rows are display/selection
buttons backed by a server-owned exact key page. Custom commands contain only
menu ID, session UUID, view revision, action, selected row/own inventory slot,
bounded amount/search text and a bounded exact-key fingerprint for own deposits.
They contain no actor/operator/root/provider/cursor/resource quantity authority.
Handlers execute on the server main thread and authenticate context.player/current
menu, physical holder, range/dimension, grants and current selection revision.

Deposit commands resolve the actual server inventory key and check a client exact
SHA-256 identity fingerprint, avoiding stale-slot substitution without shipping
large component payloads to the server. Queued transfers also recheck the exact
source key and menu/holder/scope. Inventory-only rearrangement remains local.
Withdraw commands select server-retained keys under a fresh page revision. Shared
scheduled search/routes/transfers/accounting and one in-flight menu action are used.
Known completion retires records; staging/uncertainty retains original ownership
and permits explicit original-owner return. Closing cancels undispatched work and
releases queries/selections, never discards resources already in staging.

Client projections contain at most eight rows: base registry icon, bounded plain
name and exact decimal aggregate counts. Full components and native endpoints/cursors
stay on the server. Progress/staleness/quota/full/denied/offline/uncertain states
are translatable. Search is registry-text/stable identity order initially; additional
sorting/display-name/tag filtering, crafting/fluid/energy actions remain owning-stage
work. A 280-by-232 native screen provides search, eight visible icons, counts/status,
withdraw controls, Home/Next paging, owned return and local inventory shift-deposit.
Keyboard primary actions and discoverable tooltips accompany it.

Starter recipe: four iron, three glass and two redstone, unlocked by glass. Native
JSON casing/screen/contact geometry uses the existing dark deepslate/copper/iron
palette and Minecraft-supplied textures. No optional mod/diamond/Nether dependency.
Actual rendering/scales/client interactions and full survival/restart/multiplayer
acceptance remain required evidence; headless menu tests alone do not prove them.

## Alternatives and consequences

Persisting another inventory/grant in a terminal would create unnecessary resource
ownership/migration. Vanilla virtual network slots with huge counts would invite
client prediction and container-move authority errors. Sending raw keys/roots or
whole catalogs would exceed bounded payloads and weaken stale/permission checks.
Direct GUI remove/add calls would bypass shared scheduler/staging/accounting.
Those alternatives are rejected. Menus expose only current authorized observations;
future wireless access must explicitly replace the physical range/power boundary.


## Graphical verification addition

A separately compiled clientTest mod and runTerminalClient prepare only an isolated
generated world and drive production physical use/screens/payloads. The canonical
build compiles/static-checks it; actual graphical execution is explicit and never
silently counted on headless hosts. It is excluded from ordinary runs and packaging.
The fixture exposed native initMenu's broadcast-before-assignment order; menu hooks
must not close another current menu during that early sync. An outbound packet
regression and [graphical evidence](../evidence/terminal-client-v1-2026-10-13.md)
verify the fix. No persistence/authority/scope change is introduced by verification.


## U3 — Adaptive grid and bounded display windows (2026-10-20)

Replace item paging/buttons with a responsive grid, mouse wheel and draggable scrollbar.
At the minimum supported 427×240 GUI it shows at least 9 columns × 4 rows; larger GUI
sizes show more cells, bounded to 256. Items/Fluids/Energy/Jobs are explicit tabs. Search
updates after four idle client ticks and coalesces unsent text while a transfer is busy.
Name/Qty sorting and per-player schema-one preferences remain. Crafting/processing controls
retain their semantics and move down with extra grid rows; U4 owns their tab/panel redesign.

The wire protocol advances to version 3. WINDOW carries a bounded offset (0–65,536) and
size (1–256), never a key or topology cursor. The search builds its sorted random-access
index at most 32 row references per shared scheduled query; existing per-row metadata charges
cover this bounded index. Only completed authorized observations can be windowed. Windows
project base icons/names/exact decimal counts; full component bytes stay server-side, including
for component-heavy keys. Current row tokens and revisions still guard every mutation.
Legacy HOME/NEXT/WITHDRAW commands remain server-compatible for native callers; item-screen
Home/Next/Search/Take 1/Take 64 controls are removed.

Left click takes up to one actual item stack onto the cursor, right click takes half that
available stack rounded up, Shift-click takes a stack into the player's main inventory, and
clicking the grid with carried items deposits those exact items. Ctrl-click selects a row
for the existing ghost authoring controls. The server derives stack size and exact identity,
rechecks cursor fingerprints for deposits, and uses ADR 0016's menu-bound player port through
shared scheduled routes/staging. Ordinary carried items remain subject to vanilla close/death
handling. No client count is authoritative.

The capacity bar summarizes loaded authorized item cells, independent of the search/window.
Used units include reservations. Infinite item cells show no limit. Accessible external item
stock contributes to the used count, but its effective shared item capacity can depend on
stack sizes and filters: label capacity unknown and do not draw a finite ratio. Hover shows
known cell capacity and the external limitation. Source backings are counted once under the
existing catalog alias checks; child-private or unavailable storage is not added. Totals use
BigInteger and remain exact in the payload and hover.

This amends the original eight-row display and cursor exclusion, without changing network
permission, reservation or storage semantics. Verification is tracked in the UX/implementation
plans; implementation alone is not graphical or restart evidence.
