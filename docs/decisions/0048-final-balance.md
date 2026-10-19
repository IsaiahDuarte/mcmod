# ADR 0048 — D06 balance version two: recipes, tiers and power curve

Status: selected for the first release; automated evidence only. The human
starter-to-endgame survival playtest that SPEC A17 and the D06 row ask for was
**not run**. The checks listed under Evidence replace it for
recipe reachability, tier ordering and the numeric table. They do not show that the
progression is fun, well paced or understandable, so D06 remains partially validated.

## Context

[ADR 0006](0006-initial-balance.md) chose the initial capacities and the first
recipes. Later ADRs added recipes one at a time (connector, machine interface, fuel
generator, patterns, crafting units, program controller, wireless, links, guide). No
single table listed every survival item, which stage of
R11 in the [spec](../../SPEC.md) it belongs to,
or whether every item can be reached from vanilla materials without a circular unlock.
The Expansion Module needed a diamond, so the Expanded stage required diamonds while
every other Expanded item needed only gold, quartz or ender pearls.

## Decision

### Material bands

A recipe's stage is the latest band among its inputs. The bands follow normal
vanilla survival order:

| Stage | Vanilla inputs |
| --- | --- |
| Starter | iron ingot, copper ingot, redstone dust, glass, chest, hopper, furnace, bucket, paper, book, crafting table, redstone repeater |
| Expanded | gold ingot, Nether quartz, ender pearl |
| Advanced | diamond, eye of ender, netherite ingot |
| Endgame | Nether star |

The only recipe change from earlier ADRs: the **Expansion Module** is now
` G `/`GQG`/`RGR` (four gold, one Nether quartz, two redstone) instead of one diamond.
Diamonds now first appear at Advanced, and each module belongs to a later band than
the previous one. The module still costs a Nether trip, the same as the Gateway, and
its eightfold capacity step is unchanged.

### Recipes (25, including ADR 0054 speed upgrades)

| Stage | Item | Pattern | Cost |
| --- | --- | --- | --- |
| Starter | Factory Guide | shapeless | book, redstone |
| Starter | Network Controller | IRI/RGR/IRI | 4 iron, 4 redstone, 1 glass |
| Starter | Network Cable ×8 | III/RRR/III | 6 iron, 3 redstone |
| Starter | Storage Drive | IRI/RHR/IRI | 4 iron, 4 redstone, 1 hopper |
| Starter | Starter Item Cell | IRI/RCR/IRI | 4 iron, 4 redstone, 1 chest |
| Starter | Starter Fluid Cell | GGG/GBG/GRG | 7 glass, 1 bucket, 1 redstone |
| Starter | Storage Terminal | IGI/RGR/IGI | 4 iron, 3 glass, 2 redstone |
| Starter | Buffered Interface | IGI/RHR/IGI | 4 iron, 2 glass, 2 redstone, 1 hopper |
| Starter | Storage Connector | CRC/RHR/CRC | 4 copper, 4 redstone, 1 hopper |
| Starter | Machine Interface | ICI/CRC/ICI | 4 iron, 4 copper, 1 redstone |
| Starter | Fuel Generator | ICI/CFC/IRI | 4 iron, 3 copper, 1 furnace, 1 redstone |
| Starter | Starter Energy Bank | IRI/RCR/IRI | 4 iron, 4 redstone, 1 copper |
| Starter | Blank Pattern ×4 | PRP/RIR/PRP | 4 paper, 4 redstone, 1 iron |
| Expanded | Expansion Module | ` G `/GQG/RGR | 4 gold, 1 quartz, 2 redstone |
| Expanded | Crafting Unit | IGI/RTR/IGI | 4 iron, 2 gold, 2 redstone, 1 crafting table |
| Expanded | Crafting Interface | IGI/RHR/IGI | 4 iron, 2 gold, 2 redstone, 1 hopper |
| Expanded | Program Controller | IGI/RPR/IRI | 4 iron, 1 gold, 3 redstone, 1 repeater |
| Expanded | Gateway | IQI/RER/IQI | 4 iron, 2 quartz, 2 redstone, 1 ender pearl |
| Expanded | Wireless Access Point | IEI/RGR/III | 5 iron, 1 gold, 2 redstone, 1 ender pearl |
| Expanded | Wireless Terminal | CTC/RER/CCC | Storage Terminal, 5 copper, 2 redstone, 1 ender pearl |
| Advanced | Advanced Module | ` D `/DND/RDR | 4 diamonds, 1 netherite ingot, 2 redstone |
| Advanced | Wireless Link Endpoint | DED/GAG/III | Access Point, 2 diamonds, 1 eye of ender, 2 gold, 3 iron |
| Endgame | Infinite Module | NNN/NSN/NNN | 8 netherite ingots, 1 Nether star |
| Advanced | Throughput Upgrade | IDI/RHR/IDI | 4 iron, 2 diamonds, 2 redstone, 1 hopper |
| Endgame | Overdrive Upgrade ×4 | NDN/DSD/NDN | 4 netherite, 4 diamonds, 1 Nether star |

Nine registered items have no recipe on purpose. Expanded/Advanced/Infinite item
cells, Expanded/Advanced fluid cells and Expanded/Advanced energy banks come from
applying modules in the offhand (ADR 0006). This keeps populated contents out of
vanilla crafting. Crafting and Processing Patterns are encoded from Blank Patterns in
the terminal (ADR 0030/0032). No recipe consumes a cell or a bank. Infinite fluid and
energy tiers do not exist. Each recipe has a recipe-book unlock advancement.

Totals, as the GameTests craft them:

- **Working starter base** (controller, eight cables, drive, item and fluid cells,
  terminal, buffered interface, connector, machine interface, generator, bank, guide,
  four patterns): 39 iron, 35 redstone, 12 copper, 13 glass, 3 hoppers, a chest, a
  furnace, a bucket, a book and 4 paper.
- **Later tiers before speed upgrades**: one module of each kind, Crafting Unit and Interface, Program
  Controller, Gateway, three access points, a Wireless Terminal and a link pair. These
  add 45 iron, 16 gold, 27 redstone, 3 quartz, 5 ender pearls, 8 diamonds, 9 netherite
  ingots, 2 eyes of ender and a Nether star, plus small vanilla blocks.
- **Speed-upgrade recipe kit**: four iron, six diamonds, two redstone, one hopper,
  four netherite and one Nether star yield one Throughput Upgrade and four Overdrives.

### Numeric table (unchanged from ADR 0006 and later ADRs; now pinned by a test)

| Device | Value |
| --- | --- |
| Item cells Starter / Expanded / Advanced / Infinite | 8,192 / 65,536 / 1,048,576 item units / no gameplay cap (signed-long technical ceiling) |
| Fluid cells Starter / Expanded / Advanced | 16,000 / 256,000 / 4,096,000 mB |
| Storage Drive | 4 cell slots |
| Energy Bank Starter / Expanded / Advanced | 100,000 / 1,000,000 / 10,000,000 FE; 1,000 / 4,000 / 16,000 FE/tick per direction |
| Buffered Interface | 9 slots; default 64 items/tick, installed tier caps 64 / 256 / 576 |
| Storage Connector / Machine Interface | default 64 items or 1,000 mB/FE per tick; tier maxima 128/1,000/1,000, 256/4,000/8,000, 1,024/16,000/32,000 (items/mB/FE) |
| Fuel Generator | 20 FE per burn tick (coal = 32,000 FE); 80 FE/tick output; 100,000 FE buffer; container remainders kept |
| Crafting Unit / Crafting Interface | 9 patterns each |
| Program Controller | 10,000 FE buffer, 1,000 FE/tick input; 4 FE per rule run plus 1 FE per begun 256 steps |
| Gateway nesting | Per-world `network.gatewayDepth`, default 8, configurable 1–32; world reopen/server restart required |
| Wireless Access Point | 64-block range in its own dimension; 20,000 FE buffer, 1,000 FE/tick input; 16 FE per open-session tick |
| Wireless Terminal | 20,000 FE, 1,000 FE/tick charge; 64 FE per action (about 312 actions per charge) |
| Wireless Link Endpoint | 256-block range; 20,000 FE buffer, 1,000 FE/tick input; 32 FE per connected tick on each end |
| Wireless pair resource caps | 256 items / 4,000 mB / 8,000 FE per tick bidirectionally; 4× only with Overdrive on both ends |

### Power curve

A Fuel Generator that burns continuously makes 20 FE/tick. That is enough for one
open access point (16 FE/tick) but not for one link end (32 FE/tick), which needs two
generators or banked FE. One coal runs a link end for 1,000 ticks (50 s). One coal
block runs it for about 8 minutes. A full Starter Energy Bank keeps one access point
open for 6,250 ticks. Cables, drives, cells, terminals and interfaces have no upkeep,
so a wired network costs less to build and to run than a wireless one, as R11
requires. Links share the same fair scheduler as cables and additionally meter
each pair's resource caps (ADR 0050/0054). Equivalent-tier wired port ceilings
are at least the pair caps, and wired paths have no additional wireless-pair
counter. Controlled achieved-throughput comparison remains separate evidence.

## Alternatives

- Keep the diamond in the Expansion Module. This was rejected: it puts a diamond
  requirement into the Expanded stage and blurs the step from Expansion to Advanced.
- Move netherite out of the Advanced Module, replacing it with eyes of ender. This
  was rejected: Advanced capacity (1M items, 4M mB, 10M FE) should feel late, and the
  Infinite Module still needs eight times as much netherite plus a boss drop.
- Lower link upkeep. This was not changed without playtest evidence. It is the first
  value to revisit if players find a link pair (two generators per end) too
  expensive.

## Consequences

Players who had planned around a diamond for their first upgrade now need Nether
quartz. Existing worlds keep their items, because only the crafting cost changes.

## Evidence

- `RecipeProgressionTest` (JVM) parses every shipped recipe JSON. It checks:
  - every input is a classified vanilla item or a mod item, and every result is a
    known item;
  - one source per item (a recipe, or a documented module upgrade or encoding);
  - least-fixpoint reachability from vanilla inputs, so circular unlocks cannot pass;
  - the computed stage of every item equals the table above;
  - module stages strictly increase, and the cell and bank chains consume their
    predecessors;
  - no two recipes share a grid;
  - the numeric table above and the power-curve claims match the code constants.
- `RecipeGameTests` (native GameTest) checks every loaded recipe through the server
  `RecipeManager`. The canonical grid must resolve to that recipe and not a vanilla
  or sibling one. It must yield a registered, unbound Factory Core item and have a
  recipe-book unlock. Every registered Factory Core item has exactly one survival
  source. Two tests craft the full starter base and the later tiers through a vanilla
  `CraftingMenu` from a mock player's vanilla inventory. They assert that every input
  is consumed exactly. The crafted modules then take a crafted Starter Item Cell to
  Infinite.
- Record: [D06 balance evidence](../evidence/d06-balance-v2-2026-10-18.md).

## Open

- Human survival playtest (A17): pacing, fun and onboarding clarity. Not run.
- Gateway depth is now configurable (ADR 0006); integrated default/nondefault server validation is tracked in the implementation plan.
- Per-resource link caps are implemented in ADR 0050. Paid speed upgrades and
  Advanced/Endgame device ceilings are selected/implemented in
  [ADR 0054](0054-throughput-upgrades.md); integrated results belong in the plan.
  Controlled achieved-throughput, full factory power demand and human pacing
  validation remain open.
