# ADR 0017 — Physical storage terminal and authenticated menu

Status: accepted; P3 item/menu implementation has headless evidence, actual client verification pending.

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
