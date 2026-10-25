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
5. **Connect chests and machines** with **Interfaces**.
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
ticks to come online. Use the controller to open the **Network Access** screen (see
[Sharing a network](#sharing-a-network)).

Breaking and replacing a controller keeps its owner and grants. If the controller's
chunk unloads, its network pauses; nothing is lost.

## Storage Drive and cells

Use a cell on a drive to install it in the first empty bay; a drive holds four. Use
the drive with an empty hand to open its screen. Each bay shows its tier, the number
of stored types and how full it is. With a cell on your cursor, click a bay to
install it. Click a filled bay to pick its cell up, or Shift-click it to send the
cell to your inventory. Sneak-use with an empty hand takes out the first cell.
Breaking the drive drops its cells with their contents.

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

- **Search:** type a name or ID; `@minecraft` matches a mod and
  `#minecraft:planks` matches a tag.
- **Sort:** the sort button cycles Name, Qty and Mod; the arrow beside it reverses the
  order. Your search and sort are remembered.
- **Craftable items:** items your Crafters can make have an orange corner. **⚒** also
  lists craftable items you have none of, marked **+**. Click one (or middle-click any
  craftable item) to open it in Jobs, ready to type an amount. The setting is remembered.
- **Taking items:** left-click takes a stack and right-click takes half. Shift-click
  sends the stack to your inventory.
- **Depositing:** click the grid with items on your cursor, or Shift-click an
  inventory slot. If storage is full, the rest stays with you.
- **Counts:** hover an item for its total, reserved and available counts. The bar
  shows how full your item cells are.
- **Fluids and energy:** the **Fluids** tab fills buckets and tanks (**Fill**), or
  empties them when you Shift-click a filled container. The **Energy** tab charges or
  drains FE items, up to 1,000 FE per click.
- **Returning a stuck move:** if a move is interrupted, **Return** sends the items in
  transit back to where they came from.

**Crafting grid.** Press **Crafting »** and choose **Table** for a 3×3 grid of *ghost*
cells: nothing moves into the grid, so closing never loses items.
- **Filling the grid:** click a cell, then Ctrl-click an item in your inventory.
  Alternatively, Ctrl-click a network item (or press 1–8) and press **Use selected**.
- **Clearing:** right-click a cell to clear it, or press **Clear** to clear them all.
- **Crafting:** **Craft** (C) crafts once and **×64** (Shift+C) crafts up to 64
  times. Missing ingredients are withdrawn from storage first if you have Withdraw.
- **Running short:** if storage runs out, it crafts what it can and shows
  *Partial*. A craft whose output would not fit in your inventory is refused before
  anything is used.

**Jobs.** Press **Jobs** to list what your Crafters can make. Scroll over the list (Shift:
eight at a time) or click its scrollbar to see more.
- **Preview:** select an item and press **×1**, **×8** or **×64**, or type an amount
  (1–4,096) in the box beside them and press Enter. This shows the steps needed or the
  first missing ingredient. For more than 4,096, start several jobs.
- **Plan:** after a preview, **Plan** lists every item the job touches: how many come
  from storage, how many are crafted and how many are missing (red, listed first). A
  **Loop** line means two recipes make each other; keep some of one in storage to break
  it. **Back** returns to the craftable list.
- **Start:** press **Start** to run the job; you need Craft. Its ingredients are
  reserved so no one else takes them.
- **Your jobs:** they appear on the right with progress and a state symbol (▶
  running, ⏸ waiting, ⇩ storing, ✕ cancelling); hover for the reason, scroll for more.
  Click a job to see each step: done, crafting, waiting (and why) or next. **Cancel**
  stops a job and returns unused ingredients; **Back** closes the steps.
- **Everyone's jobs:** with Manage access you also see other players' jobs on the
  network, with who asked for them. Only the player who started a job (or an operator)
  can cancel it.
- **Limits:** you can run 8 jobs at once, and a network can hold 64.

**Keep-stock:** select an item in Jobs, preview an amount and press **Keep**. The
network then crafts it whenever storage falls below that amount. Press **Keep** again
to stop.

The **Wireless Terminal** opens the same screen from a distance (see
[Wireless access](#wireless-access)).

## Interface

One **Interface** connects the network to a block next to it. Its **mode** depends on
what you place it against:

- a chest, barrel or shulker box: **Storage**, where the container joins network storage;
- another block that holds items, fluids or energy (a furnace or tank, for example):
  **Machine**, a labelled port with Import/Export/Stock presets;
- nothing usable: **Buffer**, with nine slots of its own for hoppers.

Storage and Machine have a socket that faces the container; cable the other faces. A
Buffer accepts cables on every face. Breaking an Interface keeps its mode, settings and
contents. To change mode, use the **Mode** buttons on its screen. A switch needs Edit
access, nothing in transit and an empty buffer, and the screen says which is missing.
An upgraded Interface keeps its mode so the upgrade is not lost. Use an Interface with
an empty main hand to open its screen; held items and sneaking do nothing else.

### Storage mode

The screen sets the following:
- **Resource:** Items, Fluids or Energy.
- **Access:** deposit and withdraw, read-only or write-only.
- **Priority:** −1000 to 1000. Higher-priority containers are used first.
- **Slots and rate per tick.** For a container with up to 64 slots (or tanks) the screen
  shows a strip of them: click a slot to use only it, Shift-click to use a range. Larger
  containers show First slot and Slot count fields instead.

It also shows which side of the container is used and whether the container is
ready. **Apply & Close** saves, and **Cancel** discards. Saving needs Edit; without
it the screen is read-only. A container can be connected by only one Interface.

### Machine mode

**Binding** sets a label, the resource, access, the slots or tanks to use (the same slot
strip as Storage mode) and rate. Labels are private to your network and let programs target the machine.

**Presets** sets up one of:
- **Import:** machine → storage.
- **Export:** storage → machine.
- **Stock:** fill the machine to a goal.
- **Paused.**

Add rules by ID, tag or by copying a sample from your inventory; nothing is
consumed. Up to 16 rules are allowed. **Apply** saves and starts the preset in your
name; it needs Edit and Deploy, and moving items also needs Deposit and Withdraw. If
you applied the preset, your own edits and upgrades keep it running. Someone else's
edits pause it until a player with rights applies it again. **Return** sends items
stuck in transit back to their source.

### Buffer mode

A hopper pointing into a buffer fills its nine slots, and other automation can pull
from it. A buffer you place starts in **Import** under your name, once its network is
ready and if you have the rights; otherwise it starts **Paused**.

The **Settings** tab controls what the network does with the buffer:
- **Mode** cycles Paused, Import, Export and Stock:
  - Import moves buffered items into storage.
  - Export pulls matching items from storage into the buffer.
  - Stock keeps the buffer filled to the **Goal**.
- **Item ID**, **Tag** and **Sample** add rules. **Filter** chooses all items,
  allow the rules, or block the rules. **Clear** resets to all items.
- **Rate** and **Goal** take a number and **Set**. **Apply** saves your changes;
  **Reset** discards them.

The **Inventory** tab shows the nine slots.
- **Take 1 / Take 64** move a selected item to you; Shift-click your own item to
  deposit it.
- **Return** sends items stuck in transit back.

Full storage backs items up into the buffer and then the hopper; nothing is voided.

## Patterns and the Crafter

Craft Blank Patterns.
- **Crafting Pattern:** set up a recipe in the terminal's Table grid and press
  **Encode** with a blank in your inventory. Your ingredients are not used.
- **Processing Pattern** (for machines): choose **Inputs** and set the ingredients,
  then **Outputs** (up to three), then **Encode**.
- **Amounts:** use **+ / −** or scroll to change them; Shift-scroll changes them
  faster.
- **Fluids:** switch the browser to **Fluids** and use **Use selected**. Amounts go
  from 250 to 16,000 mB.

A **Crafter** holds nine patterns of either kind. Place it on the network facing the
machine its processing patterns use (it faces the block you click). Then use patterns
on it or feed them with a hopper. Crafting patterns run inside it. Sneak-use with an
empty hand takes the last pattern back.
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

Use it with an empty hand to see its charge. To upgrade, break it, hold it in your
offhand and use an Expansion (then Advanced) module; its energy is kept.

## Program Controller

A Program Controller runs rules for its network. Power it with FE; each rule run
costs about 4 FE. Use it to open its editor.

```text
every 10 ticks { move item("iron_ingot") from machines("smelter_out") to storage }
```

- **Editing:** type a program, then **Save draft** and **Check** (errors show as
  line:column). **Deploy** runs it with your permissions. **Pause/Resume** and
  **Stop** control it. **Template** cycles built-in examples.
- **Rights:** saving needs Edit and deploying needs Deploy.
- **Helpers:** **Insert / complete** (Ctrl+Space) finds items, fluids and machine
  labels. **Preview next rule** shows what a rule would do without moving anything.
- **Rust programs:** build one with the SDK (`sdk/rust/README.md`), put the `.wasm`
  in `factorycore/programs` in your game folder and press **Upload Wasm**.
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

| Hardware | Base | Throughput | Overdrive |
| --- | --- | --- | --- |
| Storage/Machine items per tick | 128 (default 64) | 256 | 1,024 |
| Storage/Machine mB per tick | 1,000 | 4,000 | 16,000 |
| Storage/Machine FE per tick | 1,000 | 8,000 | 32,000 |
| Buffer items per tick | 64 | 256 | 576 |

- **Machine presets:** your own upgrade keeps a preset running; an upgrade by
  someone else pauses it.
- **Links:** linked gateways take Overdrive directly. Both ends need it to raise the
  pair's limit to 1,024 items, 16,000 mB and 32,000 FE per tick.
- **Busy servers:** a busy server can still slow transfers below these numbers.

## Sharing a network

Networks start private. Use the controller, or **Access…** on a gateway screen.
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
grid with ghosts. Dragging an item or fluid onto the program editor types its
expression. Everything also works without JEI.

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
