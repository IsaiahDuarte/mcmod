# Factory Core player guide

This development build provides Storage Drives, item/fluid cells and upgrade
modules on Minecraft 1.21.1 / NeoForge 21.1.252. Network Controllers, Gateways and Cables now have physical placement and
ownership. Valid wired networks now activate installed cells after bounded server rebuilding.
Finite Energy Banks expose real NeoForge energy ports on valid wired networks.
Buffered Interfaces now import real hopper items into permitted storage without code.
Storage Terminals now provide item search/deposit/withdrawal controls. Automated client checks cover item search/Take1/paging and GUI scales2–4; advanced presets, crafting, other terminal tabs and
wireless access are still unfinished.

## Cells and drives

Craft a Storage Drive, then use a cell on it to install the cell in its first
empty slot. A drive holds four cells. Sneak-use the drive with an empty main
hand to remove its first occupied cell into your hand. Breaking the drive drops
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

| Recipe | Materials |
| --- | --- |
| Starter Item Cell | 4 iron ingots, 4 redstone dust, 1 chest |
| Starter Fluid Cell | 7 glass blocks, 1 empty bucket, 1 redstone dust |
| Storage Drive | 4 iron ingots, 4 redstone dust, 1 hopper |
| Storage Terminal | 4 iron ingots, 3 glass blocks, 2 redstone dust |
| Buffered Interface | 4 iron ingots, 2 glass blocks, 2 redstone dust, 1 hopper |
| Starter Energy Bank | 4 iron ingots, 4 redstone dust, 1 copper ingot |
| Expansion Module | 4 gold ingots, 2 redstone dust, 1 diamond |
| Advanced Module | 4 diamonds, 2 redstone dust, 1 netherite ingot |
| Infinite Module | 8 netherite ingots, 1 Nether star |

Remove a cell from its drive. Hold it in your offhand and use an upgrade module
from your main hand. Apply Expansion, Advanced and then Infinite in that order.
Fluids stop at Advanced. A successful survival upgrade consumes one module and
preserves the cell's backing identity, stored contents, reservations and custom
components. Creative use retains the module. Invalid, placed or copied stale
references reject the upgrade without consuming ingredients.

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
quartz, two redstone and one ender pearl. Its red output face is downstream and
its eye face is upstream; only those two faces connect. Use it to inspect the
directions. Controllers/cables connect on all six faces. The current textures
use vanilla assets.

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


## Storage Connector (partial development feature)

Craft with four copper, four redstone and one hopper. Place its copper socket
against a container and cable the other faces. Empty-hand use prints type,
access, provider side, priority, configured rate and readiness in chat. With both
hands empty, crouch-use cycles Items/Fluids/Energy. Normal empty-main-hand use
with offhand redstone cycles access; iron/gold selects positive/negative
stack-size priority. Materials are not consumed. Changing settings needs your
network's current interface-edit grant; gateway restrictions still apply.

A backing inventory is registered only once: another face or double-chest half
reports overlap. Vanilla item containers are supported when the wrapper identifies
the actual inventory. Other providers need an explicit backing-identity adapter;
unknown opaque providers report unsupported. Read-only withdraws from a provider,
write-only deposits into it. Default rates are 64 items or 1,000 mB/FE per tick
per direction. No stock is generated or copied into the connector.

This development slice exposes server transfer primitives, not an automatic
preset or terminal catalog. Priority is stored/displayed; automatic distribution,
advanced settings screens and external stock reservations are not implemented.
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


## Buffered Interfaces

Craft a Buffered Interface using four iron ingots, two glass blocks, two redstone
and a hopper. Its top has nine raised slot markers. A hopper pointing into any
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
these rights by default. Legacy and newly placed buffers start paused; connecting
storage alone does not enable a rule.

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

Breaking the block drops one portable Buffered Interface reference containing
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

Search matches registry names, for example `iron` or `minecraft:iron_ingot`.
Click a visible item (or press 1–8 while outside the search field), then Take 1
or Take 64. Shift-click one of your inventory slots to deposit up to 64 of that
exact item. Full destinations keep the remainder owned; they never void items.
Armor, offhand and cursor stacks are excluded from transfers. Home/Next page
through eight exact item identities at a time. Search again to refresh stock.
Hover a row for exact total/held/free counts; short labels abbreviate large counts.
Counts are observations, not reservations, so another user may take stock first.

Leaving the dimension/range, replacing/unloading the block, or losing View
permission closes the session. Closing cancels undispatched actions. If a move
already owns known staged items, Return requests their return to the original
source; free that source's space first. Uncertain writes are frozen and need
administrator recovery. If all View permission or the original network is gone,
the terminal cannot provide cleanup; the admin recovery interface remains pending.
Crafting, sorting, fluid containers, energy actions and job controls are not yet
implemented in this screen. Manual Shift-click/hover/focus, other resolutions, multiplayer, restart and full
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

With both hands empty, use a Buffered Interface to open Settings. Choose a mode,
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


## Local machine bindings

Craft a Machine Interface with four iron, four copper and one redstone (ICI /
CRC / ICI). Place its socket against a machine and connect wires to another face.
With an empty main hand, use renamed paper/name tag in your offhand to copy a
local label; sneak-paper clears it. Tools are not consumed. Labels are case-sensitive
and private to the current segment, even when storage is shared through a gateway.
Machine stock stays out of the storage terminal. Use with both hands empty to edit label, resource, access, priority, item window
and per-tick rate. Apply & Close saves the complete form; Reset restores saved
values; Cancel discards local edits. Enter in a field also saves. Changing resource
resets the draft window/rate to that resource’s defaults. Slots start at zero;
fluid/energy bind the whole selected capability. A stale form reloads current
server values before retry.
Open Presets to author Paused, Import (machine → storage), Export (storage →
machine), or Stock (fill matching resident units to a goal). Binding settings must
be saved first. The preset draft is kept by the server and changes no automation
until Deploy. Set goal updates the draft; Deploy also uses the current typed goal.
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
before accepting another edit. Binding edits pause the preset. Review the selected
provider side, access and rate in Binding if work is blocked; the Return
button tooltip explains the latest runtime reason. Return recovers only
your known staging to its original source, even after edit/deploy revocation while
VIEW remains. An unavailable source can block recovery; uncertain provider calls
never replay or fabricate refunds. Selected-tank adapters and coordinated disjoint
ports remain pending.
See [binding contract](modules/MACHINE_INTERFACES.md) for current limits.

The compact machine editor fits Minecraft’s minimum240-pixel GUI height. Rules show four at a time; Prev/Next selects across all sixteen supported rules.


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

Buffered Interfaces have operator `/factorycore recovery buffer list [after-UUID]`,
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
may show zero matching records with a next cursor. Connector and Machine Interface
conflicts use the separate port command below.


Connector and Machine Interface conflicts use operator `/factorycore recovery port
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
fence, an unresolved transfer naming the port or its remembered provider, or any
legacy unresolved record without device context when no provider is remembered
blocks repair unchanged. Investigate those with backups and evidence.

Port archives use standalone binary schema5 with the same device archive quotas
and20-tick repair throttle. List visits at most16 structural handles.
