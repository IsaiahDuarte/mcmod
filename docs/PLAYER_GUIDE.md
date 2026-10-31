# Factory Core player guide

Factory Core adds a storage network, autocrafting, machine automation, programs and
wireless access to Minecraft 1.21.1 (NeoForge 21.1.252). No other mod is needed. This
is a development build; the [implementation plan](IMPLEMENTATION.md) lists what is
still unverified. Server owners and operators: see the [admin guide](ADMIN_GUIDE.md)
for permissions commands, configuration and recovery.

## Play order

1. **Read the Factory Guide.** You get one when you first join; craft another from a
   book and a redstone dust.
2. **Place a Network Controller** and run **Network Cables** from it.
3. **Add a Storage Drive** on the cables and put a **Starter Item Cell** in it.
4. **Place a Storage Terminal** on the network to deposit, withdraw and craft.
5. **Move things in and out.** Put an **Importer** on a chest or machine to pull its
   contents into storage, and an **Exporter** to push chosen items into it.
   A chest with an **Interface** becomes part of storage.
6. **Power it.** A **Fuel Generator** makes FE, and an **Energy Bank** stores it.
7. **Automate crafting.** Encode **Patterns** in the terminal and place a **Crafter**.
8. **Grow.** Add **Gateways** for subnetworks, **Program Controllers** for rules,
   wireless access and links, upgrade modules and speed upgrades.

Every block with a screen opens it when you use it with an empty main hand. Each
screen has a status line near the top: a green or amber lamp, the network state
(Online, Connecting, No controller, …), the device's own reason, and stored energy
for powered devices. Hover a shortened status line to read all of it.

## Recipes

All recipes use vanilla materials and unlock in the recipe book when you obtain a
relevant material. Higher cells and banks come from upgrade modules, not from recipes
that consume them.

| Tier | Item | Materials |
| --- | --- | --- |
| Starter | Factory Guide | 1 book, 1 redstone dust (shapeless) |
| Starter | Network Controller | 4 iron ingots, 4 redstone dust, 1 glass block |
| Starter | Network Cable ×8 | 6 iron ingots, 3 redstone dust |
| Starter | Storage Drive | 4 iron ingots, 4 redstone dust, 1 hopper |
| Starter | Starter Item Cell | 4 iron ingots, 4 redstone dust, 1 chest |
| Starter | Starter Fluid Cell | 7 glass blocks, 1 empty bucket, 1 redstone dust |
| Starter | Storage Terminal | 4 iron ingots, 3 glass blocks, 2 redstone dust |
| Starter | Interface | 4 copper ingots, 4 redstone dust, 1 hopper |
| Starter | Importer | 4 copper ingots, 1 hopper, 1 redstone dust (redstone at the bottom) |
| Starter | Exporter | 4 copper ingots, 1 hopper, 1 redstone dust (redstone at the top) |
| Starter | Fuel Generator | 4 iron ingots, 3 copper ingots, 1 furnace, 1 redstone dust |
| Starter | Starter Energy Bank | 4 iron ingots, 4 redstone dust, 1 copper ingot |
| Starter | Blank Pattern ×4 | 4 paper, 4 redstone dust, 1 iron ingot |
| Starter | Wrench | 4 iron ingots, 1 copper ingot |
| Expanded | Expansion Module | 4 gold ingots, 2 redstone dust, 1 Nether quartz |
| Expanded | Crafter | 4 iron ingots, 2 gold ingots, 2 redstone dust, 1 crafting table |
| Expanded | Program Controller | 4 iron ingots, 1 gold ingot, 3 redstone dust, 1 redstone repeater |
| Expanded | Gateway | 4 iron ingots, 2 Nether quartz, 2 redstone dust, 1 ender pearl |
| Expanded | Wireless Access Point | 5 iron ingots, 1 gold ingot, 2 redstone dust, 1 ender pearl |
| Expanded | Wireless Terminal | 1 Storage Terminal, 5 copper ingots, 2 redstone dust, 1 ender pearl |
| Advanced | Advanced Module | 4 diamonds, 2 redstone dust, 1 netherite ingot |
| Advanced | Wireless Link | 1 Wireless Access Point, 2 diamonds, 1 eye of ender, 2 gold ingots, 3 iron ingots |
| Advanced | Throughput Upgrade | 4 iron ingots, 2 diamonds, 2 redstone dust, 1 hopper |
| Infinite | Infinite Module | 8 netherite ingots, 1 Nether star |
| Infinite | Overdrive Upgrade ×4 | 4 netherite ingots, 4 diamonds, 1 Nether star |

## Factory Guide

The in-game book walks from your first network to wireless links, with pictures and
recipes. Its last page lists the materials for each tier.

## Network Controller and cables

A Controller starts a private network owned by whoever placed it. Cables connect on
all six faces. Connect everything to exactly one controller: two connected
controllers stop the network until you separate them. A long cable run can take a few
ticks to come online. Use the controller to open the **Network** overview: whether the
network works (and what to fix if not), what it has, and a checklist of what a first
network needs: storage, a terminal, then machines and Crafters. **Access…** opens the
Network Access screen (see [Sharing a network](#sharing-a-network)).

Breaking and replacing a controller keeps its owner and grants. If the controller's
chunk unloads, its network pauses; nothing is lost.

## Storage Drive and cells

Use a cell on a drive to install it in the first empty bay; a drive holds four. Use
the drive with an empty hand to open its screen. Each bay shows its tier, the number
of stored types and how full it is. With a cell on your cursor, click a bay to
install it. Click a filled bay to pick its cell up, or Shift-click it to send the
cell to your inventory. Sneak-use with an empty hand takes out the first cell.
Breaking the drive drops its cells with their contents.

**Drive priority:** type a number from -1000 to 1000 in **Priority** and press Enter.
The network fills higher-priority storage first, counting drives and Storage-mode
Interfaces together. With a partition, a high priority makes a cell collect its items
before they reach general storage.

**Partitioning a cell:** the **⚙** button on a bay opens that cell's partition. It
works like the other filter slots: click a slot holding an item, drag from JEI, or
**Add by ID…**, then choose whether the network may put in only the listed items or
everything but them. The cell keeps its partition when it is moved or upgraded, and
everything already in it stays visible and can be taken out. Changing a partition
needs Edit access.

| Cell tier | Items | Fluids |
| --- | --- | --- |
| Starter | 8,192 items | 16,000 mB |
| Expanded | 65,536 items | 256,000 mB |
| Advanced | 1,048,576 items | 4,096,000 mB |
| Infinite | No limit | Not available |

Items count individual items, not stacks; 1,000 mB is one bucket. Item cells hold only
items and fluid cells only fluids.

**Upgrading a cell:** in the drive screen, click the cell's bay with a module on your
cursor. Alternatively, hold the cell in your offhand and use the module. Apply
Expansion, then Advanced, then Infinite (items only). The cell keeps its contents.
A rejected upgrade consumes nothing.

## Storage Terminal

Place a terminal on the network and use it. You need View access; depositing needs
Deposit and withdrawing needs Withdraw.

- **Search:** type a name or ID. Every word must match. `@minecraft` matches a mod,
  `#minecraft:planks` a tag, `$fuel` a tooltip line and `*oak_` only the ID; `"oak log"` keeps
  its space. `a | b` means either, `!a` means not, and parentheses group: `(#logs | #planks)
  !@minecraft`. Counts filter by stock: `>64`, `>=64`, `<10`, `<=10`, `=0`. The parts are
  coloured as you type; a mistake turns red and shows no items (hover it to see why).
- **Search box:** Up and Down recall your last 16 searches, right-click clears it, and
  **Ctrl+F** (rebindable as "Focus terminal search") puts the cursor in it. Shift-right-click
  makes it take the cursor whenever the terminal opens (again to stop); this is remembered.
- **Saved views:** **★** at the end of the search row swaps the search box and sort tools
  for five numbered buttons. Click an empty (grey) one to save the current search, sort,
  direction and item view in it, and a saved one to use it again. Shift-click saves over a
  view, and right-click empties it. Saved views are kept with your player, in every terminal.
- **Sort:** the sort button cycles Name, Qty, Mod, ID and New (most recently stored or
  taken first, since the server started); the arrow beside it reverses the order. **⇕** makes the grid fill the window's height instead of six rows. Your search,
  sort and grid height are remembered.
- **Craftable items:** items your Crafters can make have an orange corner. **⚒** switches
  the view: stored items (grey), stored plus craftable items you have none of, marked **+**
  (gold), or only craftable items (aqua). Click a **+** item (or middle-click any craftable
  item) to open it in Jobs, ready to type an amount. The view is remembered.
- **Taking items:** left-click takes a stack and right-click takes half. Shift-click
  sends the stack to your inventory. Over an item, Shift+wheel up takes one and Shift+wheel
  down stores one; Ctrl+wheel up adds one to your cursor and Ctrl+wheel down stores one from
  it. A hotbar key (1–9) over an item fills that hotbar slot with a stack of it, storing what
  was there first. Holding Shift keeps the grid's order still while counts change.
- **Depositing:** click the grid with items on your cursor, or Shift-click an
  inventory slot. Shift-double-click deposits every stack of that item in your inventory;
  Space-click stores the whole hotbar or the whole main inventory, whichever you clicked in.
  With items on your cursor the grid's tooltip says what a click stores. Under a search the
  line below the grid reads "Showing X of Y".
  Things you craft at the terminal (in its grid, or jobs you start in its Jobs tab) show first
  in the Items grid until you close the screen. If
  storage is full, the rest stays with you.
- **Counts:** hover an item for its total, reserved and available counts. The bar
  under the grid shows how full your item cells are.
- **Status:** the lamp at the end of the search row is green when the network is online;
  hover it for details. If the network is offline, the reason shows over the grid.
- **Fluids and energy:** the **Fluids** tab fills buckets and tanks (**Fill**), or
  empties them when you Shift-click a filled container. The **Energy** tab charges or
  drains FE items, up to 1,000 FE per click. On the Fluids tab, **mB** switches amounts to
  whole buckets (**B**: `5B`, or `<1B` for less than one); tooltips keep the exact mB, and
  the choice is remembered.
- **Returning a stuck move:** if a move is interrupted, **Return** sends the items in
  transit back to where they came from.

**Crafting grid.** Press **Crafting »** under the grid to open the crafting panel beside the
terminal, and choose **Table** for a 3×3 grid of *ghost*
cells: nothing moves into the grid, so closing never loses items.
- **Filling the grid:** click a cell, then Ctrl-click an item in your inventory.
  Alternatively, Ctrl-click a network item (or press 1–8) and press **Use selected**.
- **Clearing:** right-click a cell to clear it, or press **Clear** to clear them all.
- **Crafting:** **Craft** (C) crafts once and **×64** (Shift+C) crafts up to 64
  times. Missing ingredients are withdrawn from storage first if you have Withdraw.
- **Running short:** if storage runs out, it crafts what it can and shows
  *Partial*. A craft whose output would not fit in your inventory is refused before
  anything is used.

**Patterns.** Press the **Patterns** tab to see every Crafter this terminal can use and the
patterns in each, a row of nine slots per Crafter, named by its label (see
[Label Tool](#label-tool)) or else by position. Crafters in a
subnetwork appear while its gateway lets it share crafting. Click a pattern to take it onto
your cursor; click an empty slot with a pattern to put it there, or a full one to swap. Your
inventory stays open below, so you can move patterns between Crafters or back to your
inventory. Moving patterns needs Edit where that Crafter is; otherwise its row says *view
only*. The search box keeps only Crafters with a pattern that makes what you typed, and greys
the other slots. Scroll over the list to see more Crafters. Below the list, **Visible**
cycles to **Not full** (only Crafters with a free slot) and **All** (hidden Crafters too), and
**Out** cycles what the search looks at: what patterns make, what they use (**In**), or Crafter
labels (**Name**). **hide** beside a Crafter's name takes it out of the list until you show it
again under **All**; jobs still use it. Shift-click a pattern in your inventory to put it in
the first listed Crafter with a free slot.

**Jobs.** A green number on the **Jobs** tab counts your jobs that are still running. Press the
**Jobs** tab to list what your Crafters can make in the strip at the top.
Scroll over the strip (Shift: eight at a time) or click its scrollbar to see more. Your
inventory is hidden while Jobs is open.
- **Preview:** select an item and press **×64**, or type an amount (1–4,096) in the box and
  press Enter. The box takes sums too: `64*9`, `(10+6)*4`, `128/2` (the result must be whole).
  `=500` previews only what is needed to have 500 stored, or says there are enough already.
  **−** and **+** (or the wheel, or Up/Down in the box) change the amount by 1, by 10 with
  Shift or by 64 with Ctrl, and preview it. A preview shows the steps needed or the first
  missing ingredient. For more than 4,096, start several jobs.
- **Max:** previews the most the network can make right now from stock and patterns, up to
  4,096, and puts that number in the amount box. If it can make none, it shows what is missing
  for one; if it stops at 4,096, it says more could be made.
- **Plan:** after a preview, **Plan** lists every item the job touches: how many come
  from storage, how many are crafted and how many are missing (red, listed first). A
  **Loop** line means two recipes make each other; keep some of one in storage to break
  it. **Tree** draws the same plan as a tree of what each step needs (red is missing);
  Ctrl+wheel zooms it and **List** goes back. **Back** returns to the craftable strip and
  your jobs. If a preview fails, **Start** reads **Retry**: it previews the same amount again,
  for example after you add the missing items.
- **Start:** press **Start** to run the job; you need Craft. **Shift+Start** previews the typed
  amount and starts it at once. Its ingredients are
  reserved so no one else takes them.
- **Your jobs:** they are listed under the strip with progress and a state symbol (▶
  running, ⏸ waiting, ⇩ storing, ✕ cancelling); hover for the reason, scroll for more.
  Click a job to see each step: done, crafting, waiting (and why) or next, and what runs it
  (the Crafter's label, or the machine beside it; hover a step for the full line). **Cancel**
  stops a job and returns unused ingredients; **Back** closes the steps.
- **Finished jobs:** while a screen is open, or while you carry a charged, bound Wireless
  Terminal, you get a toast in the corner; otherwise the usual chat line. Turn **✉** grey before **Start** for jobs that should finish quietly; it is
  green again the next time you open the terminal.
- **From JEI:** with JEI installed and a terminal open, Ctrl-click a crafting recipe's
  **+** button to open its output in Jobs (if your Crafters can make it) instead of
  filling the grid.
- **Everyone's jobs:** with Manage access you also see other players' jobs on the
  network, with who asked for them. Only the player who started a job (or an operator)
  can cancel it.
- **Limits:** you can run 8 jobs at once, and a network can hold 64.

**Keep-stock:** select an item in Jobs, preview an amount and press **Keep**. The
network then crafts it whenever storage falls below that amount. Press **Keep** again
to stop.

The **Wireless Terminal** opens the same screen from a distance (see
[Wireless access](#wireless-access)).

## Importer and Exporter

Place one against a chest or machine; its socket faces the block you clicked, and cables
connect to its back and sides. Use it with an empty hand to open its screen.

- **Importer** (a "V" chevron): pulls from that side into network storage. A new Importer
  starts straight away and takes **everything**. To take only some things, click a filter slot
  with an item (or drag from JEI) and choose **Only these** or **All but these**.
- **Exporter** (a "Λ" chevron): pushes from storage into that side. It sends nothing until you
  choose what: click a filter slot with an item, then **Apply**. Leave **Keep** blank to send
  everything that matches, or type a number to keep the machine stocked up to that amount.
  A dot in each item slot's corner says what storage holds: green, some; cyan, none but a
  pattern can craft it; red, none. Tag slots have no dot.
- **Resource:** Items, Fluids or Energy. A new one picks what the machine offers on that side.
- **Stop** pauses it; **Apply** starts it again in your name. You need Edit and Deploy access,
  plus Deposit and Withdraw to move things.
- **Several on one machine:** each one uses only the side it touches, so a machine can have an
  Importer and Exporters on different sides. A chest that is part of storage (through an
  Interface) can't also have an Importer or Exporter.
- Redstone control, speed upgrades and labels work as on an Interface.

**Auto smelter:** on a furnace, put an Exporter on top with raw ores in its filter, an Exporter
on a side with coal and **Keep** 8, and an Importer underneath. Ores in storage are smelted and
the ingots come back.

## Interface

One **Interface** connects the network to a block next to it. Its **mode** depends on
what you place it against:

- a chest, barrel or shulker box: **Storage**, where the container joins network storage;
- anything else: **Buffer**, with nine slots of its own for hoppers.

Machines such as furnaces don't take an Interface: use an [Importer or Exporter](#importer-and-exporter),
or label them with the [Label Tool](#label-tool) for programs.

Storage has a socket that faces the container; cable the other faces. A Buffer accepts
cables on every face. Breaking an Interface keeps its mode, settings and
contents. To change mode, use the **Mode** buttons on its screen. A switch needs Edit
access, nothing in transit and an empty buffer, and the screen says which is missing.
An upgraded Interface keeps its mode so the upgrade is not lost. Use an Interface with
an empty main hand to open its screen; held items and sneaking do nothing else.

### Storage mode

The screen sets the following:
- **Resource:** Items, Fluids or Energy.
- **Access:** deposit and withdraw, read-only or write-only.
- **Priority:** −1000 to 1000. Higher-priority containers are used first.
- **Filter…:** limits what the network puts into this container (only the listed
  items, or all but them), using the same filter slots as a Buffer. Items that do
  not match go to the next container by priority. What is already inside stays
  visible and can still be taken out.
- **Slots and rate per tick.** For a container with up to 64 slots (or tanks) the screen
  shows a strip of them: click a slot to use only it, Shift-click to use a range. Larger
  containers show First slot and Slot count fields instead.

It also shows which side of the container is used and whether the container is
ready. **Apply & Close** saves, and **Cancel** discards. Saving needs Edit; without
it the screen is read-only. A container can be connected by only one Interface.

### Buffer mode

A hopper pointing into a buffer fills its nine slots, and other automation can pull
from it. A buffer you place starts in **Import** under your name, once its network is
ready and if you have the rights; otherwise it starts **Paused**.

The **Settings** tab controls what the network does with the buffer:
- **Mode** cycles Paused, Import, Export and Stock:
  - Import moves buffered items into storage.
  - Export pulls matching items from storage into the buffer.
  - Stock keeps the buffer filled to the **Goal**.
- **Filter slots:** click a slot while holding an item to add it, drag an item
  from JEI, or use **Sample**. Click a filled slot to change how it matches:
  the item (any components), **=** that exact item, or **#** each of its tags.
  Right-click removes it. **Add by ID…** lets you type an ID or #tag instead.
  **Filter** chooses all items, allow the slots, or block the slots. **Clear**
  resets to all items.
- **Rate** and **Goal** take a number and **Set**. **Apply** saves your changes;
  **Reset** discards them.

The **Inventory** tab shows the nine slots.
- **Take 1 / Take 64** move a selected item to you; Shift-click your own item to
  deposit it.
- **Return** sends items stuck in transit back.

Full storage backs items up into the buffer and then the hopper; nothing is voided.

### Redstone control

Importers, Exporters and Buffer settings have a **Redstone** button. Each click
selects the next setting:

- **ignored**: the preset runs whatever the input (the default).
- **run when on** / **run when off**: it runs only while this Interface is powered, or
  only while it is not.
- **once per pulse**: each time the input turns on, it makes one move. Holding the signal
  on does not repeat; several quick pulses while it is busy count as one.

While it waits, its status says *Waiting for redstone*. A move already in flight always
finishes. Changing the setting needs Edit access. The setting stays with the placed
Interface; breaking it resets it to *ignored*.

### Signal mode

A Signal-mode Interface is a redstone output for the network. Choose it from the Mode row
of any Interface screen. In its screen:

- **Watch**: click the slot holding an item (a filled bucket or tank watches its fluid),
  drag from JEI, or use **Add by ID…**.
- **When**: *amount stored* compares network stock with a number (≥ or <; items, or mB for
  fluids), or *a crafting job runs* turns on while a job for that item is running.
- **Output**: *full 15*, or *proportional*, which rises like a comparator to 15 at the
  number.
- **Apply** saves it. The signal then reads storage with your access, so you need Edit
  access to apply. It updates about once a second.

Give it a **Label** if a program should drive it (`emit redstone …`). While a program
does, it shows "set by a program" and ignores its own setting. It turns off and shows why
when nothing is chosen, the network is offline, or whoever
applied it can no longer view storage. Breaking it drops a plain Interface; its settings
are not kept.

## Patterns and the Crafter

Craft Blank Patterns.
- **Crafting Pattern:** set up a recipe in the terminal's Table grid and press
  **Encode** with a blank in your inventory. Your ingredients are not used. Turn
  **Substitutes** on first if any item the recipe accepts may be used, for example any
  planks for sticks or chests. Jobs then take whichever is most plentiful, and can mix
  them. With it off, the pattern uses exactly the items you placed.
- **Processing Pattern** (for machines): choose **Inputs** and set the ingredients,
  then **Outputs** (up to three), then **Encode**. Select a slot to change its amount with
  **−**/**+** or type it in the box between them (items, or mB for fluids, rounded up to
  250 mB steps) and press Enter. The first output is what the pattern makes; select another
  output and press **Main** to make it the main one (the rest become byproducts).
- **Amounts:** use **+ / −** or scroll to change them; Shift-scroll changes them
  faster.
- **Fluids:** switch the browser to **Fluids** and use **Use selected**. Amounts go
  from 250 to 16,000 mB.
- **Editing a pattern:** with the crafting panel open, Ctrl-click an encoded pattern in your
  inventory. Its recipe, Substitutes choice, or inputs and outputs with amounts fill the grid.
  Change them and press **Encode** to rewrite that pattern; no blank is needed while it stays in
  the same slot. **Clear** forgets it.
- **Reading a pattern:** its tooltip draws the recipe: the grid and output for a Crafting
  Pattern (cells that accept substitutes cycle through them), inputs and outputs with amounts
  for a Processing Pattern. Hold Shift to see each pattern's output on its icon in any screen.
- **Wiping a pattern:** put an encoded pattern alone in a crafting grid, or sneak and use it in
  the air, to get a Blank Pattern back.

A **Crafter** holds nine patterns of either kind. Place it on the network next to the
machines its processing patterns use, on any side. Then use patterns on it or feed them
with a hopper. Crafting patterns run inside it. Use it with an empty hand to open its
screen: its nine pattern slots (click one to take it, click with a pattern to insert it,
Shift-click to move it), its speed, what it is doing or waiting for, and each machine
beside it with the job using it. Moving patterns needs Edit there. Sneak-use with an
empty hand still takes the last pattern back.
- **Several machines:** a Crafter with machines on more than one side sends each
  processing step to the next free one, so two furnaces smelt twice as fast. A machine
  already holding the output is skipped. Hoppers pointing into the Crafter, cables and
  Factory Core blocks are never treated as machines.
- **Machine use:** while a job runs, keep the machine's output slot empty and don't
  feed the machine with other pipes.
- **Subnetworks:** patterns in a subnetwork can serve the network above it unless its
  gateway forbids crafting.

## Fuel Generator

Put furnace fuel in it by hand, or with a hopper from the top or sides. Each burn
tick makes 20 FE (coal: 32,000 FE) into a 100,000 FE buffer, pushed at up to 80 FE per
tick into neighbours such as an Energy Bank. Empty lava buckets go to a container
slot: take them with a hopper below, or sneak-use with an empty hand. It pauses when
full and keeps its fuel. It needs no network.

## Energy Bank

A bank stores FE for the network. All six faces accept and provide FE once the
network is online.

| Bank tier | Capacity | Input and output per tick |
| --- | --- | --- |
| Starter | 100,000 FE | 1,000 FE |
| Expanded | 1,000,000 FE | 4,000 FE |
| Advanced | 10,000,000 FE | 16,000 FE |

Use it with an empty hand to open its screen: the charge, the FE that went in and out
last tick, its limit and the network state. To upgrade it where it stands, put an
Expansion Module (then an Advanced Module) in the screen's **Module** slot; it keeps its
energy, and a module that does not fit stays in the slot. On a network this needs Edit
rights. You can still break it, hold it in your offhand and use the module.

## Program Controller

A Program Controller runs rules for its network. Power it with FE; each rule run
costs about 4 FE. Use it to open its editor.

```text
every 10 ticks { move item("iron_ingot") from machines("smelter_out") to storage }
```

Programs find machines by label. To label one, run a cable to it and use the
[Label Tool](#label-tool) on the machine. The **Auto smelter** template runs every furnace
labelled "furnace": raw ores in the top, coal kept at 8 on the side, ingots out the bottom.

- **Editing:** type a program, then **Save draft** and **Check** (errors show as
  line:column). **Deploy** runs it with your permissions. **Pause/Resume** and
  **Stop** control it. **Templates…** lists the built-in examples by name; choosing one replaces the draft (Ctrl+Z undoes it).
- **Rights:** saving needs Edit and deploying needs Deploy.
- **Helpers:** **Insert / complete** (Ctrl+Space) finds items, fluids and machine
  labels. **Preview next rule** shows what a rule would do without moving anything.
- **Rust programs:** build one with the SDK (`sdk/rust/README.md`), put the `.wasm`
  in `factorycore/programs` in your game folder and press **Upload Wasm**.
- **Machine to machine:** `move` may name machines on both sides, for example
  `move item("iron_ingot") from machines("furnace") to machines("chest")`. Items go
  straight across without passing through storage. With several machines on each side,
  every source gives to every target (up to 64 pairs per line). Storage to storage is
  not a move.
- **Sides and slots:** after a machine in `move`, `side up` (or down, north, south,
  west, east) uses that face of the machine, and `slots 2` or `slots 0..3` uses only those
  slots. For example, one cable port can run a furnace:
  `side up` for the input, `side north` for fuel and `side down slots 0` for the output.
  Slot numbers count the slots that face shows. A range outside what the port allows is
  refused with a log line.
- **Conditions:** `if` takes `else` and `else if`, and combines checks with `and`, `or`,
  `not` and parentheses. `machine has < 8 of item("coal")` counts what a machine (or a
  whole label) holds. For example:
  `if f has < 8 of item("coal") { move item("coal") from storage to f side north limit 4 }`.
- **Patterns:** one `*` matches any part of an ID: `item("*_ingot")` is every mod's
  ingots, `item("minecraft:raw_*")` only vanilla raw ores. `except` removes items:
  `item("*_ingot") except item("iron_ingot")`.
- **Whole inventories:** `everything` (or `items`) means every item and `fluids` every
  fluid, so `move everything from machines("chest") to storage` empties a chest. They take
  `except` too: `move everything except item("dirt") from machines("chest") to storage`.
- **Redstone:** `redstone(machines("button"))` is true while that cable port (or
  Importer or Exporter) is powered. `emit redstone 15 on machines("alarm")` sets every Signal-mode Interface
  labelled "alarm" (set the label in its screen) while the program runs. Stopping or
  pausing the program hands those Interfaces back to their own settings.
- **Long rules:** a rule's moves run one after another, and the next run of the rule
  replaces any it did not reach. Give slow or important moves their own `every … as "…"`
  rule rather than putting many in one.
- **When a rule can't act:** its log line says why, for example a machine busy with
  a crafting job, stock reserved by jobs, or the rate limit. It retries at its next
  interval without losing items.

## Gateway

A Gateway starts a **subnetwork**. Its amber cross face is upstream (toward the
controller) and its blue dot face is downstream (the subnetwork); only those faces
connect. Use it to choose what the subnetwork may do:

- **Shared** (default): the subnetwork uses parent storage like the main network.
- **Read-only**: it can see parent stock but not change it.
- **Isolated**: parent storage is hidden from it.
- **Custom**: every toggle.

The subnetwork always uses its own storage normally, and players still need their
own access. Changing the choice needs Manage. Nested gateways can only narrow access.
**Access…** opens the access screen for this point in the network.

## Wireless access

Place a **Wireless Access Point** on the network and feed it FE. Use a **Wireless
Terminal** on it to bind it, and charge the handheld (for example in the terminal's
Energy tab). Then use the handheld within 64 blocks in the same dimension to open the
storage terminal.
- **Costs:** the access point uses 16 FE per tick while you are connected, and each
  move costs 64 FE from the handheld.
- **Closing:** the screen closes if you leave range, lose View or the access point
  runs dry.
- **Open key:** bind **Open Wireless Terminal** under Controls → Factory Core. It opens the one
  charged, bound Wireless Terminal in your inventory without holding it, and closes the terminal
  when pressed again. With two charged ones it asks you to keep one. Leave the handheld where it
  is while the screen is open: moving it closes the terminal.
- **Pick Block:** with a bound Wireless Terminal anywhere in your inventory, Pick Block (middle
  click) on a block you have none of takes a stack of it from the network into your hotbar and
  selects it, if you are in the access point's range and may withdraw there. Each pick costs
  64 FE from the handheld, like any move.

## Wireless links

A **Wireless Link** joins a distant subnetwork without cables.
1. **Install:** use a link on the parent's gateway and another on the subnetwork's
   gateway, up to 256 blocks apart in one dimension. Click the gateway's cross or dot
   face; the wireless connection joins that side.
2. **Pair:** with an empty hand, sneak-use one linked gateway, then the other.
3. **Power:** each linked gateway takes FE on any face and uses 32 FE per tick.

- **Status:** open the gateway to see the link's state on its status line.
- **Shared limit:** a pair shares 256 items, 4,000 mB and 8,000 FE per tick across
  everything that uses it.
- **Breaking:** breaking a linked gateway drops the link.

## Speed upgrades

Throughput and Overdrive raise transfer speed; capacity modules do not. Use
Throughput on an Interface, then Overdrive. Steps that are repeated, skipped or
denied consume nothing, and upgrades stay when the Interface moves.

Every device that takes upgrades also shows an **upgrade slot** on a small panel at the
top right of its screen (Importer, Exporter, Interface, cable port, Crafter, and a
Gateway with a link). Click it while holding an upgrade to install it. Click it
empty-handed to take the top upgrade out (Overdrive before Throughput); it goes to your
cursor, or to your inventory if the change closes the screen. Taking one out lowers the
device's rate to the new limit and needs the same rights as installing. The upgrade's
tooltip lists the devices it fits.

| Hardware | Base | Throughput | Overdrive |
| --- | --- | --- | --- |
| Storage, Importer, Exporter, cable port items per tick | 128 (default 64) | 256 | 1,024 |
| Storage, Importer, Exporter, cable port mB per tick | 1,000 | 4,000 | 16,000 |
| Storage, Importer, Exporter, cable port FE per tick | 1,000 | 8,000 | 32,000 |
| Buffer items per tick | 64 | 256 | 576 |
| Wireless Access Point range (blocks) | 64 | 128 | 256 |
| Wireless Access Point FE per tick while a terminal is open | 16 | 32 | 64 |

- **Importers and Exporters:** your own upgrade keeps them running; an upgrade by
  someone else pauses it.
- **Crafter:** Throughput, then Overdrive, makes it do 8, then 32, crafting-table
  crafts each time a job runs instead of 1. Processing patterns are unchanged. Breaking
  the Crafter gives the upgrades back.
- **More Crafters:** put copies of the same crafting pattern in several Crafters and a
  job shares its crafts between them (up to 32 crafts per run in total).
- **Access Points:** use Throughput, then Overdrive, on a Wireless Access Point to reach
  farther; each step doubles the range and the FE it uses while a terminal is open. Breaking
  it gives the upgrades back.
- **Links:** linked gateways take Overdrive directly. Both ends need it to raise the
  pair's limit to 1,024 items, 16,000 mB and 32,000 FE per tick.
- **Busy servers:** a busy server can still slow transfers below these numbers.

## Sharing a network

Networks start private. Use the controller and press **Access…**, or **Access…** on a
gateway screen.
- **Roles:** each player's role button cycles **Guest** (view), **Member** (also
  deposit, withdraw, craft) and **Builder** (also edit and deploy). Next comes
  **Admin** (also manage access and gateways).
- **Names:** players are listed by name. Hover a name to see the player's ID. A player
  who has never joined this server shows as *Unknown* with a short ID.
- **Advanced:** shows the seven individual rights.
- **Revoke:** removes a player's access.
- **Owner:** hands the network over after **Confirm**; only the owner or an
  operator can do this.
- **Gateway limits:** limits that gateways apply at this point are shown in the
  header.

Commands for the same rights are in the [admin guide](ADMIN_GUIDE.md#access-commands).

## JEI (optional)

With JEI installed, the **+** button on a crafting recipe fills the terminal's Table
grid with ghosts. Where a slot accepts several items, it picks the one you have most of
(undamaged items in your inventory plus the terminal's stock). If the grid was in the other
mode, it switches and says so above the hotbar. On any other recipe (furnace, blasting, or another mod's machine) it fills
**Inputs** and **Outputs** for a Processing Pattern, items and fluids with their amounts (fluids
rounded up to 250 mB steps). A recipe with more than three outputs keeps the first three, and a
message says so; one with more than nine inputs cannot be transferred. Dragging an item or fluid onto the program editor types its
expression. With JEI installed the terminal's search row also has a **J** button: while it is
green, what you type in the terminal search is copied into JEI's search and the other way
round. Right-click **J** to have JEI's search emptied each time the terminal opens (the J is
underlined while that is on). Both settings are remembered with your sort. Everything also works
without JEI.

## Label Tool

Crafted from a copper ingot over paper over an iron ingot. Use it in the air to type the label it
applies.

- **Label a machine:** run a network cable so it touches the machine, then use the tool on
  the machine. The cable turns into a **cable port** aimed at the machine (a cable with a small
  plate) and the machine gets the label, so programs can find it with `machines("label")` and use
  any of its sides. Using the tool on it again relabels it. Use the port with an empty hand to
  change its label or what programs move through it (items, fluids or energy). Breaking an idle
  port gives the cable back.
- **Name a Crafter:** use the tool on a Crafter (Edit needed). Its screen and the terminal's
  Patterns tab show the name, and the Crafter keeps it when broken. A Crafter renamed in an
  anvil gets that name too.
- **Label a whole row:** hold **Left Alt** (rebind it under Controls → Factory Core) while
  using the tool on a machine. Every machine of the same kind that touches the same cable run
  gets the label, up to 64, nearest first; a chest beside a row of furnaces is left alone. The
  message says how many were labelled and how many were not (for example a machine whose only
  cable already serves another machine, or one on a network where you lack Edit). Machines that
  needed a new port show their label once the network has rebuilt, a moment later.
- **See labels:** while you hold the tool, labels float over labelled machines and Signal
  Interfaces within 32 blocks, and unlabelled machines that touch a cable show a faint dot. Only
  networks you can view are shown. Bind **Label Tool: labels, network view or off** under
  Controls → Factory Core to switch between the label view, the network view and nothing.
- **Network view:** outlines the network you look at (within 16 blocks): cables, ports, devices
  and gateways, one colour per branch, and a white box on each machine a port reaches. In this
  view, using the tool on a machine shows what it offers on each side and the `move` lines a
  program needs for it; **Copy move lines** puts them on the clipboard. An unlabelled machine's
  lines say `machines("label")`: label it and replace the word.
- **Pick a label:** with the tool in hand, press the **middle mouse button** (Controls →
  Factory Core) on a labelled machine, cable port or Signal Interface. The tool takes that label,
  ready to put on the next machine. You need View access on that network.
- **Label a Signal Interface:** use it on a Signal Interface to give it the label.
- **Copy:** sneak and use it on a device to copy its settings: a cable port, Importer, Exporter
  or Storage Interface (resource kind, access, priority, slots, rate and, for Storage, its insert
  filter, and label); a Buffer (mode and filter, rate and goal); a Signal Interface (what it
  watches, its condition and output, and its label); a Drive (priority); a Gateway (its policy);
  a Program Controller (the program in its editor); or a Crafter (its patterns).
- **Paste:** use it on another of the same kind (a machine port, or a Storage Interface). The first use asks you to use
  it again to confirm. After that, other Interfaces paste straight away for 30 seconds.
  If someone changed the Interface between the prompt and your confirming use, nothing is
  pasted and it asks again. A rate higher than the target's upgrade allows is lowered to
  fit, and the message says so. You need Edit access on each device's network, and Manage for a
  Gateway. A paste also installs the copied throughput upgrades from your inventory first, and
  says so if some were missing. A Crafter paste adds the copied patterns it lacks to empty slots,
  using one Blank Pattern from your inventory for each, and says how many it could not add. A
  Program Controller gets the program in its editor only; deploy it there.
- **Forget copy** in its screen makes it label again.

## Wrench

- **Turn:** use it on an Importer, Exporter, cable port, Storage Interface or Crafter to
  turn it to the next side that touches a block (not a cable or another device). A turned
  port works on the new side at once. It needs Edit rights. It waits while something is
  still moving through the port; try again a moment later. A turned Importer or Exporter
  pauses and resumes for whoever started it, as after any settings change.
- **Pick up:** sneak-use picks the device up straight into your inventory, exactly as
  breaking it does: it keeps its settings, patterns and upgrades when placed again.

## Lamps and status

- **Controllers and gateways** show a lit symbol when connected and an exclamation
  mark on a fault.
- **Programs** show running, paused (two bars), fault or stopped (dark).
- **Access points** follow their network and power.
- **Drive bays** show which slots hold cells.

Lamps can take a second to update; open the device for the reason. Item tooltips
include the lamp legend.

## When something goes wrong

Nothing is deleted when a network goes offline, a chunk unloads or storage is full:
work waits and resumes. If an action says items are *in transit*, press **Return** on
the screen you used. Transfers whose result could not be confirmed are never repeated
automatically; ask a server operator, who can use the
[recovery tools](ADMIN_GUIDE.md#recovery). Labelled *recovery* items and casings
hold data that needs an operator: keep them.
