# Factory Core player guide

This development build provides Storage Drives, item/fluid cells and upgrade
modules on Minecraft 1.21.1 / NeoForge 21.1.252. Network Controllers, Gateways and Cables now have physical placement and
ownership. Valid wired networks now activate installed cells after bounded server rebuilding.
Finite Energy Banks expose real NeoForge energy ports on valid wired networks.
Interfaces in Buffer mode import real hopper items into permitted storage without code.
Storage Terminals provide item transfers, a manual crafting grid, saved patterns/jobs, and fluid/container and energy-item actions. Presets, friendly/Rust programs, wireless access/links and an in-game guide are implemented. Client and native checks cover parts of these flows; this remains an incomplete development build. The implementation plan records remaining behavior and verification gaps.

## Cells and drives

Craft a Storage Drive, then use a cell on it to install the cell in its first
empty slot. A drive holds four cells. Use the drive with an empty hand to open
its screen: each bay shows its tier, number of stored types, and how full it is.
Click a bay with a cell on your cursor to install it there, click a filled bay to pick its cell up, or
Shift-click it to send the cell to your inventory. Click a filled bay with an
upgrade module on your cursor to upgrade that cell without taking it out.
Sneak-use with an empty hand still takes out the first cell. Breaking the drive drops
its cells as portable references and drops the casing with the normal loot rules.
Creative installation also consumes the cell reference to preserve one physical
holder. Full drives and invalid cells reject without consuming your input.

| Cell tier | Items | Fluids |
| --- | --- | --- |
| Starter | 8,192 item units | 16,000 mB |
| Expanded | 65,536 item units | 256,000 mB |
| Advanced | 1,048,576 item units | 4,096,000 mB |
| Infinite | No gameplay capacity limit | Not available |

Item quantities count individual items, not stacks. Fluid quantities use mB
(1,000 mB is one ordinary bucket). Item cells hold only items; fluid cells hold
only fluids. Infinite holds only deposited resources and retains signed-long
and metadata limits. The detailed administrative quota UI is pending.

## Crafting and upgrades

All recipes use vanilla materials. The recipe book unlocks each recipe when
you obtain its relevant material. Cells and modules are also in the Factory Core
creative tab. Initial icons reference vanilla textures.

No other mod is needed. Tiers follow the materials you reach: Starter needs only iron, copper,
redstone, glass and simple vanilla blocks; Expanded adds gold, Nether quartz and ender
pearls; Advanced adds diamonds, eyes of ender and netherite; Infinite needs a Nether
star. Higher cells and banks are made by applying modules, never by a recipe that
consumes a cell or bank. Patterns are encoded from Blank Patterns in the terminal.

| Tier | Recipe | Materials |
| --- | --- | --- |
| Starter | Factory Guide | 1 book, 1 redstone dust (shapeless) |
| Starter | Network Controller | 4 iron ingots, 4 redstone dust, 1 glass block |
| Starter | Network Cable ×8 | 6 iron ingots, 3 redstone dust |
| Starter | Storage Drive | 4 iron ingots, 4 redstone dust, 1 hopper |
| Starter | Starter Item Cell | 4 iron ingots, 4 redstone dust, 1 chest |
| Starter | Starter Fluid Cell | 7 glass blocks, 1 empty bucket, 1 redstone dust |
| Starter | Storage Terminal | 4 iron ingots, 3 glass blocks, 2 redstone dust |
| Starter | Interface | 4 copper ingots, 4 redstone dust, 1 hopper |
| Starter | Fuel Generator | 4 iron ingots, 3 copper ingots, 1 furnace, 1 redstone dust |
| Starter | Starter Energy Bank | 4 iron ingots, 4 redstone dust, 1 copper ingot |
| Starter | Blank Pattern ×4 | 4 paper, 4 redstone dust, 1 iron ingot |
| Expanded | Expansion Module | 4 gold ingots, 2 redstone dust, 1 Nether quartz |
| Expanded | Crafter | 4 iron ingots, 2 gold ingots, 2 redstone dust, 1 crafting table |
| Expanded | Program Controller | 4 iron ingots, 1 gold ingot, 3 redstone dust, 1 redstone repeater |
| Expanded | Gateway | 4 iron ingots, 2 Nether quartz, 2 redstone dust, 1 ender pearl |
| Expanded | Wireless Access Point | 5 iron ingots, 1 gold ingot, 2 redstone dust, 1 ender pearl |
| Expanded | Wireless Terminal | 1 Storage Terminal, 5 copper ingots, 2 redstone dust, 1 ender pearl |
| Advanced | Advanced Module | 4 diamonds, 2 redstone dust, 1 netherite ingot |
| Advanced | Wireless Link | 1 Wireless Access Point, 2 diamonds, 1 eye of ender, 2 gold ingots, 3 iron ingots |
| Infinite | Infinite Module | 8 netherite ingots, 1 Nether star |

Open the drive and click the cell's bay with the module on your cursor, or
remove the cell, hold it in your offhand and use the module from your main hand.
Apply Expansion, Advanced and then Infinite in that order.
Fluids stop at Advanced. A successful survival upgrade consumes one module and
preserves the cell's backing identity, stored contents, reservations and custom
components. Creative use retains the module. Invalid or copied stale references
reject the upgrade without consuming ingredients. The offhand path requires a
portable cell. Tab selects a drive bay; Enter or Space activates it.

## Energy banks

Place a Starter Energy Bank beside your cables/controller. All six faces accept
and provide FE through compatible energy pipes; ports remain offline until the
network is valid. Starter/Expanded/Advanced hold 100,000/1,000,000/10,000,000 FE
and allow 1,000/4,000/16,000 FE each tick separately for input and output.
Faces share these rates. Reserved energy is unavailable to foreign pipes.
Use the bank with an empty hand to inspect its stored FE, capacity, rates and
routing state. Disconnecting its network immediately stops transfers and retains
the energy. A command-created empty bank is initialized by player inspection.

Break a bank to obtain one portable bank carrying its lease; creative placement
also consumes this physical reference. Hold the portable bank in your offhand and
apply Expansion then Advanced modules from your main hand. Contents, identity,
reservations and custom item components survive upgrades. Infinite energy banks
do not exist; an Infinite module rejects without consuming ingredients. Higher
tiers use modules rather than recipes consuming a populated bank.

Malformed/future bank leases break into a labeled recovery bank preserving the
original reference data. Conflicting block copies quarantine the canonical
backing; preserve the references and world backup for recovery. Full chunk/restart
and administrative reconciliation evidence is still pending. The built-in
[Fuel Generator](#fuel-generator) provides FE without other mods.

## Fuel Generator

Craft it from four iron, three copper, a furnace and redstone (ICI/CFC/IRI); the
recipe unlocks when you have a furnace. It needs no controller or cables.

Use furnace fuel on it, or feed fuel with a hopper from the top or sides. Each burn
tick makes 20 FE (coal: 1,600 ticks = 32,000 FE) into a 100,000 FE buffer. It pushes
up to 80 FE per tick into adjacent energy receivers such as an Energy Bank, and
pipes/connectors may pull from any face within the same 80 FE/tick limit. Lava
buckets leave their empty bucket in a container slot: pull it with a hopper below,
or sneak-use with an empty hand to take containers, then fuel.

Use it with an empty hand to see stored FE, remaining burn ticks and status. When
the buffer is full it pauses and keeps both the remaining burn and unused fuel; a
full container slot also stops it from taking more fuel. Breaking it drops its fuel
and containers, and the generator item keeps its stored FE and partial burn (shown
in its tooltip). A generator saved by a newer mod version stays inactive and keeps
that data unchanged.

## Ownership and recovery

A bound cell carries a reference to data in its original world's registry.
It does not carry a second copy of its contents. Moving it to another world,
copying it or using an old reference cannot create a second stock owner. A
conflicting live block copy disables its backing for recovery.

Cells are unavailable while portable, unloaded or disconnected. Unknown drive
schemas remain unavailable and break into a labeled recovery casing containing
the original data. Placing that casing preserves the recovery state. A failed
cell removal keeps its exact reference in a labeled recovery item. Automatic
repair and administrative reconciliation are pending; keep these items and
back up the world rather than discarding them.

The canonical world registry now uses schema ten. Existing schema-one through nine worlds
migrate while preserving cell identities, contents and reservations. Unknown
or malformed registry data remains unavailable with its original data preserved;
keep `world/data/factorycore_devices.dat` for recovery. Network grants, buffers, presets and owned transfer staging
save with this registry. Administrative reconciliation
remains pending; uncertain transfer intents never replay or receive automatic refunds. Wired availability uses current grants and leases;
permission screens are still pending.

Orderly registry save/reload and block lifecycle behavior are tested. Abrupt
crash recovery across independently saved chunks/player inventory, actual chunk
unload/reload and multiplayer/client interaction remain unverified. This is not
the first playable release; see [implementation evidence](IMPLEMENTATION.md).

Known staged remainder can be delivered or returned only through its accepted
owner and endpoints. A quarantined foreign call has an uncertain outcome: its
recorded amount is evidence, not a guaranteed refund. Restarting preserves that
evidence and does not replay the call. The broader administrator reconciliation
workflow remains unfinished.

## Controllers, gateways and cables

Craft a Network Controller from four iron, four redstone and one glass. Placement
creates a private network owned by the server placer. Break and move the reference
to preserve its owner/grants; creative placement also consumes it. Command-created
controllers have no owner until a player explicitly uses one. Copies cannot
create a second owner; conflicting references need recovery.

Eight Cables use six iron and three redstone. A Gateway uses four iron, two
quartz, two redstone and one ender pearl. Its amber cross face is upstream (toward
the controller) and its blue dot face is downstream (the branch); only those two faces connect. Use it to open
its policy screen. Controllers/cables connect on all six faces. The current textures
use vanilla assets.

Every device screen shows a status strip near the top: a green or amber lamp, the
network state (Online, Connecting, No controller, Two controllers connected, and
so on), the device's own reason (for example "Container: Ready" or a program's
"No FE"), and stored energy for powered devices. Hover a shortened strip to read
all of it. Devices with a screen open it on empty-hand use and no longer print
their status in chat; devices without a screen (Crafter, energy devices,
access points and links) still report in the action bar.

Connect drives by cables to exactly one commissioned controller. Installed cells
become available after server validation. Use the controller/gateway to inspect
current routing status. Disconnecting a cable or structural owner immediately
makes affected cells unavailable; contents and reservations are preserved.
Reconnection must finish validation before access resumes. Multiple roots,
gateway bypasses and other invalid topology suspend affected routing. Independent
valid regions stay available. Gateway child storage stays private from its parent;
child access to ancestor storage follows current gateway policies/grants.
An unloaded controller or upstream gateway also suspends child automation, even
when the child storage and machine chunks stay loaded. Stock and held quantities
are preserved. Reloading the original upstream devices must finish validation
before presets resume; the mod does not keep roots loaded implicitly.
The item terminal provides search/deposit/withdrawal; crafting and other terminal
tabs remain under development.

A long cable run can take several server ticks to validate. Routing stays
unavailable until required upstream chunks are loaded and validation finishes.
If a technical workload limit blocks rebuilding, resources remain owned and
unavailable rather than being discarded.


## Interface

One **Interface** block (four copper, four redstone and one hopper) does three jobs.
It picks its **mode** from what you place it against:

- against a chest, barrel or shulker box: **Storage**, where the container joins
  network storage;
- against another block that holds items, fluids or energy (a furnace or tank, for
  example): **Machine**, a labelled machine port with Import/Export/Stock presets;
- against nothing usable: **Buffer**, with its own nine slots for hoppers.

Storage and Machine have a socket that faces the container; cable the other faces.
A Buffer accepts cables on every face. Breaking an Interface keeps its mode, its
settings and anything it owns, so placing it again restores it.

To change mode, use the **Mode** buttons: at the bottom of the Storage screen and of
the Machine screen's Binding tab, or on the Buffer screen's Inventory tab. A switch
needs your interface-edit permission, nothing in transit and an empty buffer, and
the screen tells you which of these is missing. Interfaces with a Throughput or
Overdrive upgrade keep their mode so the upgrade is not lost. Switching starts the
new mode fresh: the old mode's settings, label and presets are not kept.

### Storage mode

Place its socket against a container and cable the other faces. Use it with both hands empty to
open its settings: resource (Items/Fluids/Energy), access, priority (-1000 to
1000; higher-priority containers are used first), first slot, slot window and
rate per tick. The screen also shows which side of the container is used, the
throughput upgrade limit and whether the container is ready, with the reason if
not. Apply & Close saves everything at once; Cancel discards your changes.
Saving needs your network's current interface-edit grant; without it the screen
is read-only. Gateway restrictions still apply. The older offhand shortcuts
(crouch-use to change resource, offhand redstone for access, iron/gold for
priority) still work for now.

A backing inventory is registered only once: another face or double-chest half
reports overlap. Vanilla item containers are supported when the wrapper identifies
the actual inventory. Other providers need an explicit backing-identity adapter;
unknown opaque providers report unsupported. Read-only withdraws from a provider,
write-only deposits into it. Default rates are 64 items or 1,000 mB/FE per tick
per direction. No stock is generated or copied into the connector.

This development slice exposes server transfer primitives, not an automatic
preset or terminal catalog. Automatic distribution and external stock
reservations are not implemented.
Runtime endpoint handles expire on changes/reload. Keep incomplete/uncertain
operation IDs and recovery casings; no automatic replay, refund or administrative
reconciliation tool is available yet. Persisted endpoint context and full world
restart/client evidence are still required before the player path is complete.


Supported providers now retain a world-scoped identity marker across normal block
NBT save/load. Replacing a provider without that marker creates a different
identity; copied identities cannot register twice. Unknown/foreign marker data
needs recovery and is preserved. This does not yet resume saved transfers:
operation context, recovery controls and actual full restart evidence remain
unfinished. Keep pending operation IDs and backups; never replay/refund uncertain
work based only on a matching block position.


The development save now retains a transfer's accepted network/branch, origin and
endpoint identities beside its staged resources. Root replacement cannot inherit
already queued work. Existing saves migrate without changing resource quantities
or assuming missing endpoint details. This still does not automatically resume
work after restart: current identity/grants/policy validation and recovery controls
are unfinished. Keep matching world backups; do not downgrade this save to an
older mod binary or replay an uncertain operation.


## Buffer mode

An Interface placed against nothing usable is a Buffer. Its top has nine raised slot
markers. A hopper pointing into any
face deposits real items into its nine inventory slots. Stack sizes and exact
item components are preserved; a tool occupies one item per slot. If all slots
are full, the hopper keeps its remaining items and resumes when space returns.
Adjacent automation can extract the real local contents from any face.

Connect the device to cables/controller and use it with an empty hand to inspect
item and occupied-slot counts; network VIEW permission is required for inspection.
Local hopper access works while the network is disconnected, until the physical
buffer fills. These buffered items are not automatically published as shared
network stock. Crouch-use with **both hands empty** to enable Import. Repeat to
pause. This needs current interface-edit/program-deployment rights and enabling
Import additionally needs view/deposit/withdraw rights. The controller owner has
these rights by default. A buffer you place starts in Import, in your name, once
its network is ready, if you hold all of those rights; otherwise it starts paused.
A buffer that already has a saved rule keeps it when moved.

In Import mode the network moves up to 64 items per buffer/tick into permitted
storage, using storage priority and backing-identity order. Faces and requests
share the rate, and server scheduling/limits can reduce throughput. Exact item
components are retained. Full/offline storage backs up the buffer and then the
hopper; nothing is voided. Normal empty-hand use shows mode, counts and an
explanation when work is blocked. Revocation stops queued work before mutation;
reconnection resumes when the accepted network/branch is valid again. Moving a
buffer or changing its accepted root/branch requires explicit reconfiguration.

Known partial transfers retain items in owned staging and block another extraction.
The matching current deployment retries delivery of that staging without extracting
refills. Pause before cleanup, then crouch-use with redstone in your **offhand**
and an empty main hand to return your known staging to the same buffer. Redstone
is not consumed. Only the operation's original principal can return it, including
after grant revocation/preset replacement; the physical lease and accepted network
scope must still match. Full buffers retain remaining staging, so make room and
repeat if needed. Unknown pending calls stay quarantined and never replay or
receive an automatic refund. Moved leases, root/scope changes and uncertain work
still need administrative recovery; keep their references and world backups.
Export/stock/filter presets, an inventory screen and the terminal loop remain
unfinished in this development build.

Breaking the block drops one portable Buffer-mode Interface reference containing
its canonical ownership identity, preserving the contents. Placing it again
moves that inventory and invalidates older references/capabilities. Creative
placement consumes the reference too. Copied stale/placed references cannot
produce another inventory; conflicting copies leave the contents in recovery
and block access. Preserve a faulted reference for administrative recovery.


## Storage terminal items

Place a terminal beside a cable/controller/drive on your network, then use it
with an empty hand. You need View permission; deposits additionally need Deposit
and withdrawals need Withdraw. The terminal is an access point and stores no
items itself. It unlocks in the recipe book when you obtain glass.

Type in Search to filter as you type: display names and registry names work, `@minecraft`
selects a mod, and `#minecraft:planks` selects a tag. Name/Qty changes the sort order;
your search and sort survive reopening. Scroll the grid with the mouse wheel or drag
the scrollbar. Larger GUI sizes show more items.

Left-click an item to pick up a stack; right-click takes half, rounded up. Shift-click
sends the stack into your inventory. Click the item grid with items on your cursor to
deposit them, or Shift-click an inventory slot. Full storage leaves the remainder
on your cursor/inventory. Armor and offhand slots are excluded. Closing normally
returns carried items to your inventory (or drops them if full).

Hover an item for exact total/reserved/available counts; short labels abbreviate large
counts. The capacity bar shows used/total item-cell units, including reserved items.
Infinite cells show no limit. External storage with unknown capacity is labelled.
Counts are observations, not reservations, so another user may take stock first.

Leaving the dimension/range, replacing/unloading the block, or losing View
permission closes the session. Closing cancels undispatched actions. If a move
already owns known staged items, Return requests their return to the original
source; free that source's space first. Uncertain writes are frozen and need
administrator recovery. If all View permission or the original network is gone,
the terminal cannot provide cleanup; the admin recovery interface remains pending.
Use the **Items**, **Fluids**, **Energy** and **Jobs** tabs to change the view. In Fluids,
select a fluid and press **Fill** to fill the first empty bucket or tank item in your
inventory, or Shift-click a filled container to empty it into storage. In Energy, hold an
FE item and press **Charge** or **Drain** (up to 1,000 FE each time), or
Shift-click an item to discharge it. Nothing moves if the container cannot take or give
the fluid, or if there is no room.

### Terminal crafting grid

Press **Crafting »** to open the side panel, then choose **Table** for the 3×3
crafting grid of *ghost* cells. **« Crafting** collapses it and gives the resource
browser more space. Collapsing or changing GUI scale preserves your selections. Click a
cell to highlight it, then Ctrl-click an item in your inventory, or Ctrl-click a network
item (or use 1–8) and press **Use selected**. No item moves into the grid,
so closing the screen never loses anything. Right-click a cell to clear it, or press
**Clear**. The result appears beside the grid when the pattern matches a crafting
recipe. The idle grid has no status label; failures such as missing items or a full
inventory appear when they affect a craft.

**Craft** (or C) crafts once; **×64** (or Shift+C) crafts up to 64 times. Ingredients
come from your main inventory (not armor, offhand or cursor). If some are missing
and you have Withdraw permission, the terminal first withdraws them from storage
into your inventory, then crafts. If storage runs short it crafts as many as it can
and shows *Partial*. The output and any container remainders (for example buckets
from milk) go to your inventory; if they would not fit, that craft is refused before
anything is used, and *Inventory full* is shown. Withdrawn ingredients that were not
used stay in your inventory. Manual Shift-click/hover/focus, other resolutions, multiplayer, restart and full
survival walkthrough remain unverified.


## Buffered import filters and rates

Keep your main hand empty and use the buffer with an item sample in your offhand
to allow that item type. Sneak-use a sample to allow its exact components. The
sample is not consumed, and changing selection keeps the current Import/Paused
mode. Empty-offhand sneak-use still toggles Import/Paused.

For an ID/tag rule, rename a name tag in an anvil to `minecraft:iron_ingot` or
`#minecraft:logs`, hold it in the offhand and use the buffer. Sneak-use that name
tag to exclude the ID/tag. Missing tags match nothing; invalid names leave the
old configuration intact. Only matching items drain automatically; other hopper
items stay buffered and may back up the input. Registry/tag matching does not
merge component variants in storage.

Sneak-use with an offhand clock to cycle the shared network rate through64,32,16,
8,4,1 items/tick. Sneak-use with paper to clear selection while keeping rate.
Clock/paper/redstone sneak controls take precedence over exact sample selection;
redstone remains known-staging cleanup. Rates apply to network moves and returns,
independently each way; ordinary hopper access remains physical inventory access.
Inspect with an empty offhand to see rate/filter mode/rule count. You need Edit/
Deploy and the relevant active Import grants. New controls have headless native
evidence, with actual client help/interaction checks pending. Multiple rules,
export/stock and the complete settings screen remain unfinished.

## Export and Stock buffers

Use an empty main hand and sneak-use a buffer with a compass in the offhand to
cycle Paused, Import, Export, Stock. Export withdraws permitted matching storage
into the real buffer so a hopper below can pull it out. Stock withdraws only the
missing amount toward its physical goal, counting all currently matching buffer
items; another job's reserved source supply is unavailable. Targets are best
effort and can remain short when capacity, permissions, holds or rates block work.

Sneak-use with an offhand chest cycles the stock goal through0,8,16,32,64,128,256,
512,576. Initial goal64; zero stops stock withdrawal. Selection/rate edits retain
the goal, and mode/goal tools are not consumed. Items fed physically beyond a goal
remain owned; Stock does not remove or void them. Rate limits and partial staging
still apply. Old staged operations remain under their original owner after edits;
redstone cleanup returns known resources to the recorded source when valid.
A full settings screen and actual client verification of these controls remain
pending; the current native controls have headless behavior evidence.


## Buffer settings screen

With both hands empty, use a Buffer-mode Interface to open Settings. Choose a mode,
filter, rate and stock goal; type an item ID or tag and add it, or choose Sample
and click your item to copy an exact rule without consuming it. Select a rule to
remove it. Changes stay in a draft until Apply; Reset discards the draft. Up to
sixteen rules are supported. Rate is network items per tick in each direction;
Goal is the total matching physical stock, not a reservation.

Inventory shows the nine buffer slots and your inventory. Select a buffer slot
and Take1 or Take64; shift-click your own stack to deposit. Full destinations and
Stock goal caps leave unaccepted items owned. Return cleans up known staged items;
uncertain transfers require administrator recovery. Nearby VIEW access is required;
settings also require EDIT/DEPLOY and active-mode transfer permissions.
Partial inventory moves show the actual moved/requested item count; a full goal
shows zero moved and leaves your stack owned.
The screen is implemented, but actual client verification is still pending.


## Machine mode

Place an Interface with its socket against a machine and connect cables to another
face.
With an empty main hand, use renamed paper/name tag in your offhand to copy a
local label; sneak-paper clears it. Tools are not consumed. Labels are case-sensitive
and private to the current segment, even when storage is shared through a gateway.
Machine stock stays out of the storage terminal. Use with both hands empty to edit label, resource, access, priority, slot/tank window
and per-tick rate. Apply & Close saves the complete form; Reset restores saved
values; Cancel discards local edits. Enter in a field also saves. Changing resource
resets the draft window/rate to that resource’s defaults. Slots start at zero;
fluid0/64 selects the whole capability, while other fluid windows require indexed
tank support from the provider. Energy uses0/64. A stale form reloads current
server values before retry.
Open Presets to author Paused, Import (machine → storage), Export (storage →
machine), or Stock (fill matching resident units to a goal). Binding settings must
be saved first. The preset draft is kept by the server and changes no automation
until Apply, which saves the draft, including the typed goal, and starts it in one
step. Enter in the goal field also applies.
Goals use items, mB or FE according to the saved binding. Zero Stock stops filling.

All matches the resource kind; Allow selected and Block selected apply up to16
registry IDs, tags or exact samples. Enter a namespaced ID, then Add ID or Add tag.
Copy sample uses the displayed slot in your own main inventory: exact items keep
components, fluid samples read a filled container, and FE needs no sample item.
Nothing is consumed. Cycle the slot button to choose another occupied slot.
Prev/Next select a rule; Remove/Clear edit the draft. Hover a row for its full name.
Reset reloads the saved preset; Cancel discards the unsaved draft.

Deployment requires current view, edit and deployment grants; active modes also
need deposit and withdrawal grants. A changed binding/deployment reloads the draft
before accepting another edit. If you deployed the preset, your own binding edits
and throughput upgrades keep it running. When someone else edits or upgrades the
machine, the preset pauses. The Presets page then shows it as paused, already
filled in, and one Apply by a player with deployment rights resumes it in that
player's name. After a server restart a paused preset must be set up again. Review the selected
provider side, access and rate in Binding if work is blocked; the Return
button tooltip explains the latest runtime reason. Return recovers only
your known staging to its original source, even after edit/deploy revocation while
VIEW remains. An unavailable source can block recovery; uncertain provider calls
never replay or fabricate refunds. Providers with explicit fixed global partitions
can bind separate interfaces to their exact disjoint slot/tank windows. Ordinary
providers remain exclusive for each resource kind, and unsupported tank selection
blocks without moving fluid. Disjoint windows share the physical provider's
128 item or1000mB per-tick ceiling plus each interface's configured rate.
See [binding contract](modules/MACHINE_INTERFACES.md) for current limits.

The compact machine editor fits Minecraft’s minimum240-pixel GUI height. Rules show four at a time; Prev/Next selects across all sixteen supported rules.


## Network Access screen

Use a Network Controller, or press **Access…** on a gateway's policy screen, to manage who can use the network.

- **Roles.** Each player has a role button. Click it to move to the next role: **Guest** (view),
  **Member** (also deposit, withdraw and craft), **Builder** (also edit interfaces/patterns and
  deploy programs) and **Admin** (also manage access and gateways). A player whose grants match no
  role shows **Custom**; an online player without grants shows **No access**.
- **Advanced.** Shows each player's seven toggles instead: V view, D deposit, W withdraw, C craft,
  E edit interfaces/patterns, P deploy programs and M manage. **Roles** switches back.
- **Gateway limits.** The header shows the limits that gateways above this point apply. A toggle
  marked `*` is granted but blocked by a gateway here.
- **Revoke** removes all of that player's grants.
- **Owner** hands the network to that player. You must press **Confirm** within 10 seconds. Only
  the owner or a server operator can transfer ownership; a player who was given Manage cannot.
- **Losing Manage.** If you lose Manage while the screen is open, it says so and turns read-only.
  Without Manage you see only your own access.
- **Recovery….** This button opens the Transfer Recovery screen.

## Transfer Recovery screen

`/factorycore recovery screen` lists interrupted transfers you own; operators see everyone's.

- Pick a record to see its safe next step, then press **Return**, **Settle** or **Retire**.
  These follow the same rules as the recovery commands below.
- Uncertain transfers offer no action. They are never replayed or refunded automatically.

## Recovery commands

Operators (permission level2) and server console can use
`/factorycore recovery list` for up to16 transfer records. Continue with
`/factorycore recovery list <next-UUID>` using the printed cursor.
`/factorycore recovery show <transfer-UUID>` reports the original owner, exact
counts, root/segment, origin/device IDs and a safe next action. It reads metadata;
it does not contact providers or load chunks.

The original logged-in owner can request `/factorycore recovery return <UUID>`
for known staged remainder. Use your own command source; console or `execute as`
cannot impersonate another owner, even for operators. Cleanup returns only existing
staging to its original source. The accepted current scope/endpoints must validate;
unload, movement, full source, rate/access limits or changed bindings can block it.
This global recovery command does not require standing at the original menu.
It does not grant new extraction or resource access after permission revocation.

Before queuing, the command writes exact before-state evidence under the world's
`factorycore-recovery` directory and prints the file name/admission result. The
later message reports cumulative returned units. The record remains available
for inspection. An operator may explicitly `/factorycore recovery retire <UUID>`
only after it is known complete and empty; this archives its completed state
before freeing record quota. Uncertain/live records cannot retire, replay or refund.

If evidence cannot be written, no cleanup/removal is admitted. The audit directory
admits512 files,512KiB each and32MiB total. Keep full world/provider backups and
archive existing files outside that directory when it fills; do not overwrite
corrupt evidence or treat audit files as resource-restoration instructions.
Commands throttle recovery requests; wait one second before retrying. Slow storage
can delay these explicit recovery commands. Unknown foreign outcomes still need backup/manual investigation.


### Old records without context

A transfer from before accepted endpoints were recorded may show "No accepted
endpoint context". Its original logged-in owner can run
`/factorycore recovery settle <UUID>` instead of `return`:

- Staging from a Factory Core cell, bank or Buffer-mode Interface returns there under
  the owned-cleanup exemption, without requiring a new resource grant.
- Otherwise, staging destined for a Factory Core cell or bank can be delivered
  there only with current Deposit permission.
- Foreign or unknown targets remain preserved for investigation. Uncertain records
  never settle, and operators cannot impersonate the recorded owner.

The chosen canonical holder must be loaded and on a valid network. A known source
always takes precedence, even when offline; settlement does not switch to another
end just because it is available. Evidence is saved first, no new extraction occurs,
and each scheduled step offers at most 128 items or 1,000 mB/FE. A full holder
retains the remainder. Retry explicitly after space returns or after a moved
holder invalidates the admitted network/segment. An operator may retire a known
complete, empty record.


Operators can inspect digital cell ownership with `/factorycore recovery cell list`
(up to16 metadata rows; append the printed cursor for the next page) or
`/factorycore recovery cell show <backing-UUID>`. Rows include the current
generation, conflict flag, exact total and canonical dimension/position/slot.
These commands read no providers and load no chunks. The displayed drive slot
is zero-based (0–3).

For a duplicated placed cell, preserve a full backup, load the original canonical
drive normally, inspect its UUID/generation, then use
`/factorycore recovery cell repair <backing-UUID> <generation>`. Only the existing
canonical slot with the exact matching cell can be repaired. The command archives
the complete ledger, claims and physical cell in factorycore-device-recovery
before rotating its lease. Stock and reservations stay unchanged; copied old
references become inert. Valid network membership must be rebuilt before use.

Repair refuses unresolved transfers, wrong/stale/missing references, incompatible
cell tiers, unreadable drives and other device kinds. It cannot select a different
survivor or fix an interrupted canonical/chunk save mismatch. Investigate retained
evidence/backups in those cases; never replace quantities by guessing. Device
archives have independent limits of512 files,9MiB/file and32MiB total. Archive
outside that directory to free quota; no command deletes audits. An evidence write
failure leaves ownership untouched. Explicit repairs are throttled to one attempt
per second per server and may be delayed by storage IO.


Finite Energy Banks have the matching operator commands:
`/factorycore recovery bank list [after-UUID]`, `show <backing-UUID>` and
`repair <backing-UUID> <generation>` under the same prefix. Repair requires the
loaded original bank, matching lease/tier and no unresolved transfer. It archives
full FE/claims, block state and reference before rotating the lease. Stored FE and
reservations stay unchanged; old clones/cached clone capabilities stay inert.
Repair keeps rate spending already used in the same tick and requires normal
network revalidation before FE transfer resumes.

Bank and cell repairs share the20-tick throttle and device-audit quotas. Their
list commands examine at most16 canonical handles per page and display only the
selected kind; follow a printed cursor even if that page has zero matches. Bank
archives use binary audit schema2, while existing cell files remain schema1.
Neither command chooses another survivor or resolves interrupted independent-save
mismatches, unsupported raw data or uncertain external outcomes. Preserve backups
and retained evidence for those cases.

Buffer-mode Interfaces have operator `/factorycore recovery buffer list [after-UUID]`,
`show <backing-UUID>` and `repair <backing-UUID> <generation>` commands. Show reports
the canonical position/count and original accepted preset principal/settings.
Repair requires the loaded original holder with the exact reference and no unresolved
transfer. It preserves all nine slots, item components and preset evidence, archives
the before-state, and rotates the lease so old copies/handlers stay inert.

After repair, the old preset stays inactive. An authorized player opens the recovered
buffer's Settings and presses Apply to republish under current permissions. If DEPLOY
or required transfer grants were revoked, Apply remains denied. The operator repair
does not adopt the original deployer's identity. Local hopper access to the real
owned buffer inventory can resume without an active preset, as usual.

Buffer archives use standalone binary schema3 and share the device-audit quotas
and20-tick server repair throttle with cells/banks. List pages examine at most16
structural IDs and may show zero matches with a continuation cursor. Missing/stale
original references, unsupported data, independent-save mismatches and unresolved
staging still require backup/evidence investigation. No command guesses quantities,
replays unknown provider calls, deletes evidence or chooses another survivor.


Controller and gateway conflicts use operator `/factorycore recovery node list
[after-UUID]`, `show <node-UUID>` and `repair <node-UUID> <generation>`. Only the
loaded original canonical holder with its exact matching reference can be repaired.
Show reports the original owner/grant metadata or canonical gateway policy. Repair
archives the full node/policy/physical reference and controller grants before
rotating the lease. It never recommissions an owner, changes a grant or opens a
policy. Old copies become stale, and normal wiring revalidation precedes routing.

Network/segment identities stay the same, and ordinary accepted transfer evidence
is left unchanged under existing authorization/uncertainty checks. Repair performs
no provider call, transfer replay or refund. Unsupported records using a controller/
gateway itself as a storage endpoint block repair. Missing/stale original references
or independent-save mismatches still need backup/evidence investigation.

Node archives use standalone binary schema4 with the same device archive quotas
and20-tick server repair throttle. List visits at most16 structural handles and
may show zero matching records with a next cursor. Storage- and Machine-mode Interface
conflicts use the separate port command below.


Storage- and Machine-mode Interface conflicts use operator `/factorycore recovery port
list [after-UUID]`, `show <port-UUID>` and `repair <port-UUID> <generation>`. Only
the loaded original canonical holder with its exact matching reference can be
repaired. Show reports kind/generation/position and, for a machine, the accepted
deployer/mode/revision/lease/configuration and canonical fence. Repair archives
the node, remembered provider registration metadata, machine preset/fence and full
physical reference before rotating the lease. It keeps settings, label, face and
configuration; it never opens, reads or changes the attached container.

Old copies and old mod ports become stale; the provider is reacquired normally
after wiring revalidation. A repaired machine preset does not run until a player
with current DEPLOY permission explicitly redeploys it from the machine menu; the
operator never becomes the deployer. A saved provider fault, a mismatched machine
fence, an unresolved transfer naming the port or its remembered provider, or an
uncertain context-less record naming an unknown foreign backing (when no provider
is remembered) blocks repair unchanged. Investigate those with backups and
evidence. Known legacy staging can settle into canonical storage through the
original-owner workflow above and does not by itself block port repair.

Port archives use standalone binary schema5 with the same device archive quotas
and20-tick repair throttle. List visits at most16 structural handles.


## Patterns and the Crafter

One **Crafter** holds up to nine patterns of either kind. Crafting-table patterns run
inside it; processing patterns use the machine it faces. Hoppers can feed patterns
from every face except the machine's.

Craft Blank Patterns (four paper, four redstone and an iron ingot make four). Set up a
recipe in the terminal crafting grid and press **Encode** with a blank in your
inventory: one blank becomes a Crafting Pattern that remembers the exact ingredients,
result and container remainders. Your ingredients are not used.

Place a Crafter (iron, gold, redstone and a crafting table) on your network and
use a pattern on it, or feed patterns with a hopper. It holds nine. Use it with an
empty hand to see how many patterns it has and its network status; sneak-use to take
the last pattern back. Breaking it drops its patterns. Patterns are offered only to
the network segment the unit is wired into and, through each gateway that lets its
branch craft (the default), to the segments above it. The parent can then request
those recipes; the branch's machines and storage stay private, ingredients come from
the requester's storage and results go back there. Autocrafting requests that use these patterns are not available yet.


### Autocrafting jobs (server side)

Autocrafting jobs now exist on the server: a request plans with the patterns in your
segment's Crafters and reserves exactly the ingredients it will use from your
network's cells, so other players cannot take them. The Crafter crafts one step
at a time; intermediate items stay inside the job and the finished items are put back
into storage. Cancelling releases unused ingredients and returns any items the job was
holding; ingredients already crafted are not restored. If a pattern is removed, the
network goes offline, your Craft permission is revoked or storage is full, the job
waits with a reason and continues when the problem is fixed. Jobs survive a normal
save and restart.

In the terminal, press **Jobs** to switch from stored items to craftable items (the
outputs of the patterns in your segment's Crafters). Select one (click or 1–8)
and press **×1**, **×8** or **×64** to preview: the screen shows how many steps and
crafts are needed, or the first missing ingredient. Nothing is reserved by a preview.
Press **Start** to begin; you need Craft permission. Your jobs appear in the column on
the right with crafts done/total and a state symbol (▶ running, ⏸ waiting, ⇩ storing
results, ✕ cancelling); hover for the reason. Select a job and press **Cancel** to stop
it. You can run up to eight jobs; a network holds at most 64.


### Processing patterns and Crafters

For machine recipes (smelting and similar), open **Crafting »** and choose **Inputs**.
Set the machine's ingredient ghosts, then choose **Outputs** and fill up to three
output cells. Select a ghost and use **+ / −**, or scroll over it, to adjust its
amount; Shift-scroll changes eight steps. Item amounts are 1–64. Press **Encode**
with a Blank Pattern to get a Processing Pattern. Switching between Inputs and
Outputs keeps both declarations; **Clear** clears both.

Place a Crafter against the machine (it faces the block you click) and wire
it to your network; insert the processing pattern. Autocrafting then puts the inputs
into that machine and takes only the expected outputs back out of it. Use the machine
only for this while a job runs, and keep its output slot empty: a job waits while the
output slot already holds the product, while another job is using the machine, or
while the machine is full. If you cancel during processing, the job keeps collecting
that batch's output for about a minute before giving up and leaving it in the machine.


### Keep-stock

In Jobs mode, select a craftable item, preview an amount, and press **Keep**. The
network then crafts that item whenever storage holds fewer than that amount, up to
it, one job at a time, using your Craft permission. Rows show **≥N** while kept;
press **Keep** again to stop. If storage is full, results wait in the job until there
is room. If you lose Craft permission, the rule stops starting jobs.


## Program Controllers

A Program Controller (iron, gold, redstone and a repeater) runs one program for its
network segment. Power it with FE (for example from a Fuel Generator or Energy Bank):
each rule run costs 4 FE plus a little for long rules, and an unpowered controller
waits. Use it with an empty hand to see its state, FE and last message.

Programs use the friendly language (see the programs reference): rules like
`every 10 ticks { move item("iron_ingot") from machines("smelter_out") to storage }`.
Label Machine-mode Interfaces in their settings to target them. Deploying needs Deploy
permission; the program then acts with the deployer's current permissions, and pauses
if the deployer loses Deploy. Reading storage with `if stock(...)` needs View. A rule
that does too much work at once stops the program with an error instead of doing part
of it. Use the controller to open its editor: type in the text box, **Save draft**, **Check**
for errors (shown as line:column under the editor), **Deploy**, **Pause/Resume** or
**Stop**. **Template** cycles through built-in examples you can edit. Saving needs Edit
permission; the running program only changes when you deploy.

The editor colors the language and underlines local errors; hover for the first
positioned error. Ctrl+Z undoes, Ctrl+Y (or Ctrl+Shift+Z) redoes, and Tab indents.
**Insert / complete** searches items, fluids, local labels and keywords without JEI;
click its category to switch. Ctrl+Space completes the token at your cursor. Choose a
row, or Escape to return. Local-label results cover your current segment only and
page separately; **View scope** shows its network/segment IDs on hover.

**Preview next rule** saves your draft and shows one rule's current resolved targets
and actions. Repeated clicks select the next rule; arrows page long text. This needs
View, and saving also needs Edit. A View-only player previews the already saved draft.
The preview moves nothing, creates no job and spends no FE. It samples present stock
and labels; actual execution still checks authority, power, stock and machine ports.
Escape returns to editing. Wasm programs use their upload validation instead.

To run a Rust program, build it with the SDK (see `sdk/rust/README.md`), copy the
`.wasm` file into `factorycore/programs` in your game folder and press **Upload Wasm**:
the newest file is sent and deployed. A Wasm rule run needs about 260 FE available and
costs 4 FE plus 1 FE per 256 instructions it actually uses. If it traps, it stops;
Resume starts it fresh. When a Rust program panics, the log shows the panic message and
source line, such as `trap fail: factory_rule: guest panicked: src/lib.rs:47: bedrock in
storage`.

When a rule cannot act, its log line says why:
- **busy: a crafting job is using this machine.** A processing job leases that machine. A
  cancelled job keeps the lease for up to a minute while it collects outputs.
- **matching stock is reserved by crafting jobs.** Running jobs have claimed all the matching
  stock.
- **rate limit reached this tick.**
- **throttled / running late: server busy.** The server is overloaded.

Every rule retries at its next interval without losing items.

## Wireless access

Place a **Wireless Access Point** on your network and feed it FE. Use a **Wireless
Terminal** on it to bind it, charge the handheld (for example from the terminal's Energy
view), then right-click the handheld anywhere within 64 blocks in the same dimension to
open the storage terminal. The access point uses 16 FE per tick while you have it open,
and every action that moves items, fluids or energy costs 64 FE from the handheld. The
screen closes if you leave range, lose View permission, or the access point runs dry.


## Wireless links

To connect a distant branch, use a **Wireless Link** on the parent side's gateway and
another on the child branch's gateway, up to 256 blocks apart in the same dimension.
Use it on the gateway's amber cross (upstream) or blue dot (downstream) face; that is
the side the wireless connection joins. Then, with an empty hand, sneak-use one linked
gateway and then the other to pair them. Each linked gateway needs its own FE, accepts
it on any face and uses 32 FE per tick. The branch disconnects if either end loses
power or is broken, and gateway rules still decide what it can see. Open the gateway
to see the link's state on its status line. Breaking a linked gateway drops the link.

Each pair shares 256 items, 4,000 mB and 8,000 FE per tick across both directions,
players, presets, programs and crafting. Crossing several pairs uses each one's
allowance. Container fills/empties and table crafts wait until the complete action
fits; charging can use a partial allowance. Operating FE is separate from energy
sent through the link. Wired connections retain their device limits.


## Gateway rules

Use a gateway to open its policy screen. It opens on four choices:

- **Shared** (the default): the branch sees and uses all parent storage, like the
  main network.
- **Read-only**: the branch can see parent stock but cannot deposit into, take from
  or craft with it.
- **Isolated**: parent storage is hidden from the branch.
- **Custom**: shows every toggle. **This subnetwork can…** limits what anyone may do
  inside the branch. **…using parent storage** and **Parent storage kinds** decide
  which of those actions reach parent stock, and for which resource kinds.

Every preset lets the branch use its own storage normally. Players still need their
own access grants. You need Manage to change the choice; others see it read-only.
Nested gateways can only narrow access. Wait a moment between clicks while the
branch updates.

## Factory Guide

You receive a **Factory Guide** the first time you join a world with this mod. Use it to
read the step-by-step guide from your first network to wireless links; its last page lists
which materials each progression tier needs. Craft another
from a book and a redstone dust.


## JEI (optional)

With Just Enough Items installed, press JEI's **+** button on a crafting recipe while the
terminal is open to reveal the crafting panel and fill its Table grid. This places ghost entries only; nothing is moved until
you craft. Drag an item or fluid from JEI onto the Program editor to type its
`item("…")` or `fluid("…")` expression (a space separates it from the text before it). Without JEI, everything still works through the
terminal rows and by typing IDs.


### Fluid processing

With the crafting panel on **Inputs** or **Outputs**, switch the resource browser to
**Fluids**, select the fluid, highlight a ghost cell and press **Use selected**.
Fluid ghosts show bucket icons and mB amounts. The selected amount and hover give
exact mB; **+ / −** changes 250 mB per step, from 250 to 16,000 mB. Patterns may mix
items and fluids. The Crafter uses the machine's fluid tanks, so the
machine must accept fluids from that side. Choosing **Table** removes fluid ghosts.


### After a crash: adopting a cell

If a server stops between saving its chunks and saving Factory Core's data, a drive may
show a "stale placed cell" message, and that cell's contents are unavailable. Nothing is
lost or duplicated. An operator can inspect the cell with `/factorycore recovery cell show
<backing>`. Then run `/factorycore recovery cell adopt <backing> <generation> <x y z>
<slot>`, naming the drive slot that physically holds the cell. The original location
must be loaded and must not still hold the cell. Keep backups. The command records
evidence before changing anything.

Energy banks work the same way: `/factorycore recovery bank adopt <backing> <generation>
<x y z>` names the bank that physically holds the reference.

Controllers and gateways: `/factorycore recovery node adopt <id> <generation> <x y z>`.
Ownership, grants and gateway rules stay as they were.

Buffer-mode Interfaces: `/factorycore recovery buffer adopt <id> <generation> <x y z>`. Their
items stay. Re-enable Import, Export or Stock afterwards.


Storage- and Machine-mode Interfaces also support `/factorycore recovery port adopt
<id> <generation> <x y z>`. The old canonical position must be loaded and no longer
reference the port; the selected stale holder must have matching identity and
casing with no provider fault. Provider contents are untouched. Machine presets
remain paused until a player with current permissions deploys them again.

## Sharing a network

Networks start private. At a loaded block in your valid network, use
`/factorycore access list <x> <y> <z>` to inspect the owner and grants. The owner,
a player with Manage, or an operator can grant specific rights:

```text
/factorycore access grant <x> <y> <z> <online-name-or-UUID> view,deposit,withdraw
/factorycore access revoke <x> <y> <z> <online-name-or-UUID> withdraw
/factorycore access revoke <x> <y> <z> <online-name-or-UUID>
```

Available rights are `view`, `deposit`, `withdraw`, `craft`, `edit`, `deploy` and
`manage`; `all` names every right. Grant adds rights, and revoke without rights
removes every explicit grant. Offline players require their UUID. Manage allows
administration, so grant it deliberately. Gateway branch policies still apply at
the chosen block. Operators have management access but must receive resource rights
to use someone else's stock. A grant change invalidates old observations; open
terminal stock views also refresh after other players or automation move resources.


## Server configuration

Server owners can set the maximum nested gateways in the world's
`serverconfig/factorycore-server.toml`: under `[network]`, set `gatewayDepth = 8`
to an integer from 1 through 32. Reopen the world or restart the server after editing.
Every gateway on a root-to-branch path counts once, including wireless-linked
gateways. A network exceeding the setting reports `DEPTH_LIMIT` and pauses access
without deleting stored resources; shorten the branch path or raise the setting.


Terminal search accepts names or registry IDs, `@minecraft` for a mod ID, and
`#minecraft:planks` for a loaded item/fluid tag. Use Name/Qty to switch between A–Z
and highest total quantity first. Exact component variants stay separate, and
reserved/available counts remain visible. Your accepted search and sort follow
you between terminal screens and normal saves; each screen opens in Items.
Display-name matching uses the server's language and custom item names.


Processing jobs lease their exact physical machine across multiple Crafting
Interfaces and Factory Core script/preset ports. Busy ports retry after the job's
cleanup; other machines keep working. Keep external pipes/hoppers from feeding
processing machines. If you rotate a dispatched interface, restore its original
orientation before it can continue. An old saved dispatch without a proven
target asks you to cancel and inspect the original machine; known staging is
returned, but already inserted ingredients and unproven outputs stay in that
machine. The old cleanup fence expires after its bounded passes without a
guessed refund.


A processing job also remembers the original machine's persistent identity. A new
machine at the same position cannot continue that job or donate its outputs to
old cancellation cleanup. Restore the original saved machine, or cancel and
inspect already inserted inputs/outputs manually. An older dispatch without
provider identity asks for cancellation and inspection; it never adopts whichever
machine is now present.

## Paid speed upgrades

Capacity modules and speed upgrades are separate. Craft a Throughput Upgrade
from four iron, two diamonds, two redstone and a hopper. Use it on a placed
Interface, then use Overdrive
for the next step. Four netherite, four diamonds and a Nether star craft four
Overdrives. Repeated, skipped or denied steps consume nothing.

| Hardware | Base | Throughput | Overdrive |
| --- | --- | --- | --- |
| Connector/machine items per tick | 128 max (default 64) | 256 | 1,024 |
| Connector/machine mB per tick | 1,000 | 4,000 | 16,000 |
| Connector/machine FE per tick | 1,000 | 8,000 | 32,000 |
| Buffer network items per tick | 64 | 256 | 576 |

For example, place a Machine-mode Interface facing a barrel, apply Throughput, open
Binding and verify its 256-item rate. Installation pauses the machine preset;
reapply its filter/mode/goal to resume with your current grants. You may lower
the rate afterwards. Buffers require the normal deployment grants; links require
Manage or ownership of an orphan. The shared server work budget can visibly
throttle any tier; an upgrade does not guarantee achieved throughput.
Automatic machine item presets now schedule successive stacks through that same
budget instead of waiting for a fresh preset sweep after every successful stack.
Large storage catalogs can still limit achieved speed before the hardware cap.

Ports and buffers retain upgrades when moved. Linked gateways take Overdrive
directly; both ends need it to raise their shared bidirectional caps from
256/4,000/8,000 to 1,024/16,000/32,000 items/mB/FE per tick. Breaking a known
upgraded link drops its Overdrive separately for reinstallation. Unsupported
link data becomes one inert recovery item with its original evidence; keep it
for a compatible build or investigation. It never produces a guessed refund.

## Device appearance and status

The four drive bays show which slots contain cells. Removing a cell clears only
its bay marker. Controller and gateway lamps show a connected network with a lit
symbol and a fault with an exclamation mark; use empty-hand inspection for the
cause. During rebuilding, the lamp keeps its previous appearance until validation
finishes. Program lamps distinguish running, paused (two bars), trapped (fault),
and stopped (dark). An access-point lamp follows its network and power state.
Link lamps indicate local pairing/power; inspect gateway/network status to check
routing. An unpaired link stays dark. Lamp refresh can take up to one second.
Tooltips include the legend.

The supported minimum framebuffer is 1708×960 for GUI scale 4 (427×240 GUI
pixels). GUI scales 1–4 have a dedicated presentation fixture; its verification
status is tracked in the implementation plan. Screens below this minimum are
not established as supported by that fixture.
