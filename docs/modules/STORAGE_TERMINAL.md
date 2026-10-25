# Physical item storage terminal

The access-only Storage Terminal exposes the existing wired TERMINAL role and
shared item search/routes/player inventory endpoints. It stores no inventory,
network identity, grants or transferable authority. Copies are access shells;
the current world envelope remains schema 13 (cursor context alone uses nested schema 4). [ADR 0017](../decisions/0017-storage-terminal-menu.md)
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
Armor, offhand and crafting slots are excluded. The menu's real cursor is an explicit
session-bound player endpoint (slot 36), described in [player transfers](PLAYER_INVENTORY_TRANSFERS.md).
It shares the same backing/rates; it is never catalog stock or a second inventory.

Each menu accepts at most one action every two actual server ticks and one transfer
in flight. At most 256 server-retained exact row keys are the only withdrawal selections;
read-only paging/search is refused while an action runs. Search polls at most once
per five ticks. All observations and transfers use shared queues/limits/permissions,
not GUI-owned stock counters. Deposits need View and Deposit; withdrawals View and
Withdraw. Current View is required to use this physical menu even for owned return.

Closing releases the transient query/selections and cancels an undispatched route.
Accepted staging is retained canonically under its original actor. Completed,
zero-staged/no-pending records retire. Unresolved player backing blocks new moves.
Return finds only that player's canonical operation and uses guarded known progress,
returning to the original source without re-extraction. Uncertain calls remain frozen;
loss of View/original topology may require the Transfer Recovery screen or operator
reconciliation.
Independent player/world-file interruption safety remains unverified.

## Projection and controls

The wire protocol is version 4. The server projects up to 256 rows: token, base registry
icon, plain name (96 characters) and exact decimal total/reserved/available counts (32
characters). Full components, native endpoints and topology/query cursors stay server-only.
WINDOW uses selection as an offset (0–65,536) and amount as viewport size (1–256).
The returned view includes actual offset, total matching rows, size, resource kind and
unfiltered capacity summary. Index construction is scheduled in batches of at most 32 row
references; a completed window lookup is O(viewport size), including component-heavy keys.
Display names come from the observation's bounded cache, avoiding repeated component decoding.
Search scope and grant stamps are revalidated before every selection or window read.
The menu retains at most 256 exact rows/names across a periodic rescan to avoid
flashing. This bounded per-menu display snapshot is separate from the query budget
and is released on replacement, explicit refresh, scope invalidation or closure.

The adaptive screen displays at least 9×4 item cells at the minimum supported 427×240
GUI; larger GUIs show more columns/rows, bounded to 256. The mouse wheel scrolls by a row;
the scrollbar supports direct clicks and dragging. Items/Fluids/Energy/Jobs are visible tabs.
Search coalesces typing after four idle client ticks, retains unsent typing while busy,
and retries when the shared two-tick command gate admits it. Name/Qty and saved per-player
preferences retain their existing meaning. Main inventory slot identities/order remain
unchanged when client-only coordinates move down for extra grid rows.

TAKE_CURSOR resolves the current server row and its actual native maximum stack size,
then withdraws up to one stack. HALF_CURSOR withdraws half of that available stack rounded
up. WITHDRAW sends up to one stack to the player's main inventory (Shift-click).
DEPOSIT_CURSOR resolves the server's carried stack, checks its exact-key fingerprint,
and deposits up to 64; the real remainder stays on the cursor. Clicking any item-grid cell
with carried items deposits them. Shift-clicking an own inventory slot also deposits it.
The server revalidates menu/session/revision, scope, grants, rates and fit at execution through
the existing shared routes/accounting. Stale or changed cursors cannot substitute another key.
Closing cancels undispatched work; vanilla returns/drops carried items exactly once, while
known canonical staging remains owned. Explicit cursor staging recovery returns to the same
player's current main inventory, never a replacement menu's cursor; uncertain calls freeze.

Tab/Enter activate grid cells and tabs. Ctrl-click or 1–8 selects a network row for the
Use selected ghost authoring control. Hover shows exact total/reserved/available counts.
The item-capacity bar counts loaded authorized item cells once, independently of search or
window, including reserved units. Infinite shows no limit. External items contribute to used
units but make effective capacity unknown; label that limitation and suppress a finite ratio.
Hover retains exact used and known cell-capacity totals, using arbitrary-precision decimals.
Count overlays use vanilla item-decoration depth, with long compact labels scaled
to fit one cell. The crafting grid suppresses its redundant idle status label.
No offline/private storage or buffers are added to the network view.

Example: connect a terminal to an owned controller/drive, type `iron`, left-click its row
to take a stack, then click the grid with that carried stack to deposit it again. Right-click
picks up half; Shift-click goes to inventory. [Player guide](../PLAYER_GUIDE.md) records controls.

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


## Graphical client evidence and native initialization

The menu broadcast hook now ignores broadcasts while another menu is current.
Native ServerPlayer.initMenu synchronizes slots before assigning containerMenu;
closing on that early broadcast sent a prior-menu close after the open packet and
immediately dismissed the client screen. Native outgoing-packet regression coverage
and the actual graphical fixture now verify the corrected lifecycle. Execution
commands still require this menu to be current; the fix does not widen authority.

The screen preserves search text across native reinitialization and uses explicit
slot borders. [Graphical evidence](../evidence/terminal-client-v1-2026-10-13.md)
records actual hopper imports, physical opening, typed search, number-key selection,
Take1, a real deposit payload, paging, scale2/3/4 captures and normal shutdown.
This is partial client evidence: Shift gesture, tooltips/focus, other resolutions,
Return/Take64/denied/full/stale messages and full release acceptance remain pending.


## Manual crafting grid

[ADR0028](../decisions/0028-terminal-manual-crafting.md) defines the session-owned
ghost grid. `TerminalCraftingPackets.Command(menu, session, viewRevision,
gridRevision, action, cell, source, crafts, fingerprint)` actions are SET_OWN
(source = own slot 0–35, exact-key fingerprint), SET_ROW (source = current row under
`viewRevision`), CLEAR (cell -1 clears all) and CRAFT (1–64 crafts under the current
`gridRevision`). Stale revisions, mismatched fingerprints, busy sessions and edits
over 32 per second are ignored. `View` carries twelve base registry IDs (nine inputs/three outputs), per-cell counts,
a 12-bit fluid-kind mask, processing mode, the result
icon/name/count, status (`empty`, `ready`, `filled`, `no_recipe`, `gathering`, `crafted`,
`partial`, `shortage`, `full`, `reserved`, `unsupported`) and the last crafted
count. The server resolves keys, matches recipes and owns all quantities.

`TerminalCrafting.craft(player, crafts)` returns a deficit with nothing consumed
when owned main-inventory units cannot cover all crafts; the menu then gathers
through `WorldStorageRoutes.priorityGuarded` FROM_STORAGE into the player inventory,
bounded to 64 units per route and 18 routes per request. Crafting itself is an
atomic per-request commit over a copied inventory that proves output/remainder fit
per craft. Only `RecipeType.CRAFTING` recipes are used; the grid holds no items.


## Autocrafting jobs view

`TerminalJobPackets.Command(menu, session, revision, action, selection, amount)`
actions: WINDOW (scroll so craftable `selection`, 0–65,536, is first; the server clamps
to the last full window), PREVIEW (visible craftable row 0–7, amount from the ×1/×8/×64
buttons or the typed amount box, which refuses empty, zero, non-numeric and over-limit text
on the client with a reason,
1–4,096, current `revision`), START (the server-held preview under the current
revision), CANCEL (job row 0–3). Stale revisions are ignored; commands share the
menu's two-tick throttle. `View` carries up to eight craftable entries with their window
`offset` and the `total` craftable count, the preview
entry/reason/steps/crafts, up to three missing entries with exact amounts, up to four
of the viewer's jobs (output, done, total, state, reason) and a status key. Craftables
are the sorted output keys of `WorldCrafting.index` for the terminal's segment,
refreshed on open and scrolling. The client accumulates wheel notches into a pending offset
so quick scrolling is not lost before the server replies. Protocol 5 (parity slice P4)
replaced the HOME/NEXT commands and the `next` flag with WINDOW and `offset`/`total`. Preview/start/cancel delegate to
[`WorldCraftingJobs`](CRAFTING.md#jobs); the client never names a resource key.


## Fluids and energy

`TerminalPackets` adds KIND (selection 0/1/2), FILL (fluid row), EMPTY (own slot), CHARGE
and DISCHARGE (own slot); see [ADR0038](../decisions/0038-terminal-fluids-energy.md).
Status keys: `filled`, `emptied`, `charged`, `discharged`, `no_container`, `empty`,
`no_energy_item`, `item_full`, `item_empty`, `handler_fault`, `full`, `shortage`,
`wrong_resource`. Per-action limits: 16,000 mB and 1,000 FE.


## Recipe-viewer fill

`TerminalCraftingPackets.Fill(menu, session, items[9])` is applied by `TerminalMenu.gridFill`.
It sets crafting-mode ghost cells by registry identity; unknown IDs clear a cell. It shares
the ghost-edit budget and moves nothing. The JEI handler is in
`platform.client.integration.jei` ([ADR0043](../decisions/0043-jei-integration.md)).


## Search, sort and player preferences (R08/A11)

Items opens by default. Search plain text matches the registry ID or server-language
display name, including custom names; `@minecraft` matches a mod namespace and
`#minecraft:planks` matches a loaded item/fluid tag. Name sorts A–Z; Qty sorts exact
total units descending (reservations remain separately visible). Matching variants
remain distinct. Name sorting uses the first 256 characters and exact identity breaks
ties. Search/sort are read-only and use the same VIEW authorization and bounded
scheduled catalog as transfers. Selecting a sort restarts the query at the first window; a stale
page anchor also returns Home. No raw catalog cursor is sent to clients.

The Name/Qty button is keyboard-focusable. Search/sort state is stored per player
under `ForgeData.PlayerPersisted.FactoryCoreTerminal`, schema 1 (`Search`, at most
128 UTF-16 characters; `Sort`, 0 or 1). Missing data uses empty search/name order;
invalid known fields default without granting authority. Unknown schemas are left
untouched until the player explicitly changes a preference. Each authenticated
change writes only this preference compound. It survives screen closure, normal
player save/reload and NeoForge's persisted-player death cloning. These advisory
preferences contain no stock or ownership; interrupted writes can only restore an
older preference. The menu syncs accepted preferences; other players and terminal
blocks do not share them. Local unsent typing survives screen reinitialization.

`TerminalSearchSupportTest` exercises case-insensitive display/registry/mod matching,
exact component separation and arbitrary-precision stable sort order;
`TerminalGameTests.displayRegistryModTagSearchAndPlayerSortSurviveReopen` exercises
loaded tags, read-only totals and restored menu state. Packet tests round-trip
accepted preferences and reject invalid inbound bounds. `runTerminalRestartClient` now requires typed display-name/mod/tag queries, actual
Name/Qty clicks, physical close/reopen and nondefault preference capture, then a
fresh JVM compares the persisted preference compound and reopens the terminal.
The fixture is implemented; successful integrated/graphical outcomes remain to be
recorded.


## Container actions across wireless pairs

Fill/empty and FE item actions reserve the shared bidirectional movement allowance
on every wireless hop between the terminal and each owned cell/bank, before
changing owned resources. A fluid action is indivisible at the container's simulated
amount: a bucket needs its full 1,000 mB allowance, and a rejected partial plan
releases its reservation without extracting or inserting fluid. Energy actions may
use the remaining allowance, up to 1,000 FE per action and each bank's own rate.

Item capability execution uses disposable component-backed stack copies. Only an
exact successful storage plan publishes the changed item into the player's slot.
A simulation/execution disagreement reports handler_fault, preserves the live
item and owned resources, and releases unspent movement allowance. No compensation
is attempted against foreign inventories/tanks. Unexpected partial owned writes
are reversed into/from their exact original holders in the same synchronous
server-thread call; verified compensation bypasses another movement/rate admission
and only then releases the original permit. Bank rate debits may remain consumed
for the tick after compensation; quantities never become budget credits.

`TerminalWirelessContainerGameTests` builds an actual powered paired gateway path
and root storage used from a child terminal. It checks a residual 500 mB cannot
fill/empty a bucket, residual 500 FE limits charging, reverse discharge shares the
exhausted cap, the next tick replenishes it, and dishonest item execution changes
only disposable copies. This fixture remains unverified until the native suite
runs successfully.


## U4 crafting panel

The client keeps a collapsible right-side panel, closed for a new empty session.
The adjacent browser remains at least 9×4 at GUI 427×240; closing the panel restores
its width. Table, Inputs and Outputs are separate visible buttons. Inputs/Outputs
share the existing processing mode and preserve both declarations; Table uses the
existing rule that leaving processing drops fluid ghosts. Clear still clears all
inputs/outputs. The selected page/target and ghosts survive collapse and GUI resize.
A newly received recipe-viewer fill (`filled` status at a new grid revision) reveals the panel;
ordinary ghost edits and crafting outcomes do not reopen a deliberately collapsed panel.

Protocol 4 adds a validated fluid-kind bit mask to the grid projection. A bit may
name only a nonempty cell while processing. Exact components/keys remain server-side.
`View.amount(cell)` converts the existing 1–64 processing count to item units or mB
(250 mB per fluid step); counts and encoding limits are unchanged. The UI uses bucket
icons for fluids, compact mB-number overlays, exact selected/hover amounts with units,
and bounded +/− buttons or wheel input. Only Table shows a recipe-result preview;
processing outputs are the declared ghost cells, avoiding a misleading single-bucket
preview for a fractional fluid output. No quantity, mode or permission authority
moves to the client. No persistence format changes. Native projection/codec regressions
and graphical/JEI fixtures cover this slice; successful runs belong in acceptance evidence.
