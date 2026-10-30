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

The screen (playtest H2, [ADR 0064](../decisions/0064-panel-kit.md)) shows a 9-column grid
whose columns line up with the player inventory docked under it. It has six rows, or as many
as fit the window when the player's Tall preference is on (at least 3 rows; 4 at the minimum
supported 427×240 GUI), bounded to 256 cells. The mouse wheel scrolls by a row; the scrollbar
supports direct clicks and dragging. Items/Fluids/Energy/Jobs/Patterns are tabs on the panel's
top edge, padded to fit its width (`TabButton.widths`); the status lamp ends the search row.
The crafting grid opens as a panel to the right. The Jobs view replaces the grid and hides the
inventory slots while shown (they are neither drawn nor clickable; contents are unchanged).
The Patterns view (Q3) also replaces the grid but keeps the inventory, so patterns can be carried
to and from it; Shift-click does not deposit into storage while it is shown.
In code, `TerminalScreen` is a shell (tabs, search, lamp, mode switches, command packet) over
four parts: `TerminalStorageView` (grid and info row), `TerminalCraftingPanel`,
`TerminalJobsView` (ADR 0064, Q55 amendment) and `TerminalPatternsView`.
Search coalesces typing after four idle client ticks, retains unsent typing while busy,
and retries when the shared two-tick command gate admits it. Name/Qty and saved per-player
preferences retain their existing meaning. Main inventory slot identities/order remain
unchanged when client-only coordinates move for extra grid rows.

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


## Deposit all and search focus (Q18)

`TerminalPackets.DEPOSIT_ALL` (action 18, protocol 11) names one own main-inventory slot and its
key fingerprint. The menu queues every main-inventory slot holding that exact key and routes them
one at a time through the same priority deposit as DEPOSIT, starting the next when the previous
completes with OK and stopping on the first refusal or when the session closes. It spends one
terminal action for the whole batch. The client sends it on a second Shift-click on the same
slot within 250 ms (naming any slot that still holds the item, since the first click may
already have emptied that one). `TerminalSearchSupport.FOCUS` (bit 32) is a further client-only
preference flag in the saved sort, toggled by Shift-right-clicking the search box (Q61 made a
plain right-click clear it); SORT commands now
accept selections up to 63 for these flags.

## Manual crafting grid

[ADR0028](../decisions/0028-terminal-manual-crafting.md) defines the session-owned
ghost grid. `TerminalCraftingPackets.Command(menu, session, viewRevision,
gridRevision, action, cell, source, crafts, fingerprint)` actions are SET_OWN
(source = own slot 0–35, exact-key fingerprint), SET_ROW (source = current row under
`viewRevision`), CLEAR (cell -1 clears all), CRAFT (1–64 crafts under the current
`gridRevision`), ENCODE, MODE, COUNT and LOAD (Q4: source = own slot holding an encoded pattern).
LOAD fills the grid from a crafting pattern (Table cells; status `loaded_substitutes` when it
allows substitutes, so the client restores its toggle) or a processing pattern (inputs, then fluid
inputs, in cells 0–8; outputs in 9–11; counts as items or 250 mB units). A pattern the grid
cannot show (amounts not whole units or over 64 units, too many entries) is refused as
`unsupported` with the grid unchanged. The grid remembers the slot and pattern id: ENCODE then
replaces that stack (status `rewritten`, a fresh pattern id) while the same pattern is still in
that slot, otherwise it falls back to a blank. CLEAR forgets it. Protocol 9 added LOAD and the
three statuses. Stale revisions, mismatched fingerprints, busy sessions and edits
over 32 per second are ignored. `View` carries twelve base registry IDs (nine inputs/three outputs), per-cell counts,
a 12-bit fluid-kind mask, processing mode, the result
icon/name/count, status (`TerminalCraftingPackets.GridStatus`: `empty`, `ready`, `filled`,
`no_recipe`, `gathering`, `crafted`, `partial`, `encoded`, `no_blank`, `shortage`, `full`,
`reserved`, `no_power`, `processing_mode`, `unsupported`, `loaded`, `loaded_substitutes`,
`rewritten`) and the last crafted count. The server resolves keys, matches recipes and owns all quantities.

`TerminalCrafting.craft(player, crafts)` returns a deficit with nothing consumed
when owned main-inventory units cannot cover all crafts; the menu then gathers
through `WorldStorageRoutes.priorityGuarded` FROM_STORAGE into the player inventory,
bounded to 64 units per route and 18 routes per request. Crafting itself is an
atomic per-request commit over a copied inventory that proves output/remainder fit
per craft. Only `RecipeType.CRAFTING` recipes are used; the grid holds no items.


## Job notices and recipe-viewer requests (A5)

`WorldCraftingJobs` sends a finished or cancelled job's requester a `JobNotice` payload
(optional channel, protocol 1) when the client negotiated it, else the server chat line.
The client (`JobToast`, registered as the payload's sink at client setup so common code
loads no client class) shows a toast while any screen is open and the chat line otherwise;
the item is named in the client's language. With JEI, Ctrl on a crafting recipe's transfer
button calls `JeiRequests.request`, which sends `REQUEST` with the output's plain item ID in
the command text; the server opens it only if `TerminalJobs.craftableKeys` contains it.

## Craftable rows in the Items grid (A3)

Each search is started with the craftable output keys of the terminal segment's visible
patterns (`TerminalJobs.craftableKeys`: own Crafters plus branches whose gateways publish
CRAFT, at most `WorldStorageSearch.MAX_CRAFTABLE` = 4,096; more is refused). Rows carry a
`craftable` flag. With the per-player `Craftable` preference (saved beside `Sort`,
schema 1 unchanged; missing reads as off), matching craftable keys of the view's kind with
no stock are added once at completion as zero-count rows, charged like stocked rows and
cleared with the session; paging after one works because anchors also resolve ghosts.
`CRAFTABLES` sets the preference; `REQUEST` (visible row, current revision) asks
`TerminalJobs.focus` to scroll the Jobs list to that key, and the client selects it and
focuses the amount box. A request names a visible row, never a resource key. Protocol 6 (7 since Q3).

## Autocrafting jobs view

`TerminalJobPackets.Command(menu, session, revision, action, selection, amount)`
actions: WINDOW (scroll so craftable `selection`, 0–65,536, is first; the server clamps
to the last full window), PREVIEW (visible craftable row 0–7, amount from the ×1/×8/×64
buttons or the typed amount box, which refuses empty, zero, non-numeric and over-limit text
on the client with a reason,
1–4,096, current `revision`), MAX (Q7: the same row previewed at
`CraftingPlanner.maximum`, the largest amount up to 4,096 that plans as OK, found by binary search
over at most about 14 bounded plans; status `maximum`, `capped` at 4,096, or the single-unit
plan's reason such as `missing` when none can be made; the client copies the amount into the
box), START (the server-held preview under the current revision), CANCEL (job row 0–3). Stale revisions are ignored; commands share the
menu's two-tick throttle. `View` carries up to eight craftable entries with their window
`offset` and the `total` craftable count, the bounded plan breakdown (up to 64
`PlanRow`s of stored/crafted/missing units from `core.crafting.PlanRows`, missing rows
first, `planMore` for the rest, up to three cycle explanations by display name, built once
per preview change), the preview
entry/reason/steps/crafts, up to three missing entries with exact amounts, up to four
of the viewer's jobs (output, done, total, state, reason) and a status key. Craftables
are the sorted output keys of `WorldCrafting.index` for the terminal's segment,
refreshed on open and scrolling. The job list holds up to 16 rows: the viewer's own jobs,
then (A4) other players' jobs on the network when the viewer has Manage at the terminal,
each with the requester's name. DETAIL opens one listed job: up to 32 step rows (done,
running, waiting with the job's reason, queued) and whether this viewer may cancel it,
which is only its requester or an operator; Manage alone is read-only. A finished job
closes its detail view. The client accumulates wheel notches into a pending offset
so quick scrolling is not lost before the server replies. Protocol 5 (parity slice P4)
replaced the HOME/NEXT commands and the `next` flag with WINDOW and `offset`/`total`. Preview/start/cancel delegate to
[`WorldCraftingJobs`](CRAFTING.md#jobs); the client never names a resource key.


## Patterns view (Q3)

`TerminalPatternPackets.Command(menu, session, revision, action, selection, slot)` actions:
WINDOW (scroll so Crafter `selection`, 0–1,024, is first; the server clamps to the last Crafter so
a short screen that shows fewer than three rows can reach the end) and CLICK (slot 0–8 of visible
row 0–2 under the current pattern `revision`; a stale revision republishes instead). The server
lists `WorldCrafting.visible` for the terminal's segment: VALID, current holders in the segment
plus those of branches whose gateways publish CRAFT, in dimension and position order, bounded by
`WorldCrafting.MAX_UNITS`. A non-blank terminal search keeps the Crafters with a pattern whose
output or a byproduct matches it (`TerminalSearchSupport.matches`), and marks matching slots.
`View` carries up to three `Crafter` rows (position, dimension, `label`, `published`, `editable`, nine
`Entry` slots with the output's registry ID, fluid and processing flags, and `match`), the
window `offset`, `total` and the last click's `Status`.

A CLICK re-reads the row's Crafter, refuses it as GONE if it is no longer current or listed,
and checks `PatternHolderEntity.editableBy` (EDIT where the Crafter is, the same rule as the
Crafter screen). An empty cursor takes the slot's pattern; a cursor pattern goes into an empty
slot through the holder's own validation, or swaps with the slot when the cursor holds exactly
one; anything else is REFUSED. Clicks spend a wireless terminal's action energy as storage
actions do. The server builds nothing until the client first opens the view, then refreshes it
every 20 ticks, after each command and after a search change; an unchanged window is not
resent. Rows are named by the Crafter's Label Tool label (at most 64 characters), or by position
when it has none. Protocol 7 added these payloads; protocol 8 added `label` (Q107).

## Fluids and energy

`TerminalPackets` adds KIND (selection 0/1/2), FILL (fluid row), EMPTY (own slot), CHARGE
and DISCHARGE (own slot); see [ADR0038](../decisions/0038-terminal-fluids-energy.md).
Status keys: `filled`, `emptied`, `charged`, `discharged`, `no_container`, `empty`,
`no_energy_item`, `item_full`, `item_empty`, `handler_fault`, `full`, `shortage`,
`wrong_resource`. Per-action limits: 16,000 mB and 1,000 FE.


## Recipe-viewer fill

`TerminalCraftingPackets.Fill(menu, session, items[9])` is applied by `TerminalMenu.gridFill`.
It sets crafting-mode ghost cells by registry identity; unknown IDs clear a cell. It shares
the ghost-edit budget and moves nothing. `ProcessingFill(menu, session, inputs[1–9],
outputs[1–3])` (Q5, protocol 10), each `Cell(fluid, id, units)` with units 1–64, is applied by
`TerminalMenu.gridProcessingFill`: it clears the grid, switches to processing mode and sets inputs
from cell 0 and outputs from cell 9 with their counts; unknown IDs leave a cell empty. The client
shapes recipes with `core.crafting.ProcessingTransfer` (first three outputs kept and reported,
items one unit each, fluids rounded up to 250 mB units). The JEI handler is in
`platform.client.integration.jei` ([ADR0043](../decisions/0043-jei-integration.md)).


## Search, sort and player preferences (R08/A11)

Items opens by default. The search text is a `core.resource.SearchQuery` (Q61): words are
ANDed, `|` is OR, `!` NOT, parentheses group (OR loosest, NOT tightest). A word matches the
registry ID or server-language display name, including custom names; `"quoted text"` keeps
spaces; `@minecraft` matches a mod namespace, `#minecraft:planks` a loaded item/fluid tag,
`$text` a line of the item's server-side tooltip and `*text` only the ID. `>n`, `>=n`, `<n`,
`<=n` and `=n` compare the summed total. Queries are at most 128 characters, 32 terms and 8
levels of parentheses; a malformed one matches nothing and the client marks it. Text terms
filter rows as source pages arrive, with count terms unknown (three-valued logic); count
terms are settled when the finished index is built from summed totals, so windows and the
total stay exact. Craftable ghost rows are counted as zero. Name sorts A–Z; Qty sorts exact
total units descending (reservations remain separately visible). Matching variants
remain distinct. Name sorting uses the first 256 characters and exact identity breaks
ties. Search/sort are read-only and use the same VIEW authorization and bounded
scheduled catalog as transfers. Selecting a sort restarts the query at the first window; a stale
page anchor also returns Home. No raw catalog cursor is sent to clients.

The Name/Qty button is keyboard-focusable. Search/sort state is stored per player
under `ForgeData.PlayerPersisted.FactoryCoreTerminal`, schema 1 (`Search`, at most
128 UTF-16 characters; `Sort`: a mode 0–2, plus 4 for reversed and 8 for the Tall grid,
which changes only the client layout). Missing data uses empty search/name order;
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
