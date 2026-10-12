# ADR 0006 — Initial progression and balance defaults

Status: initial D06 selection before storage block defaults. Balance version one;
survival playtesting, powered devices and wireless comparison remain P6 validation.

## Selected defaults

Use vanilla resources only. Wired starter storage is unpowered so a hopper/import
preset path needs no other technology mod. Program execution and wireless access
use finite FE and the vanilla-fuel generator in later slices. Limits below are
gameplay targets, distinct from the technical metadata/ownership quotas.

| Device | Version-one capacity/rate |
| --- | --- |
| Item cells Starter / Expanded / Advanced / Infinite | 8,192 / 65,536 / 1,048,576 item units / infinite gameplay capacity, signed-long technical ceiling. |
| Fluid cells Starter / Expanded / Advanced | 16,000 / 256,000 / 4,096,000 mB; no infinite fluid tier. |
| Storage Drive | Four cell slots; no implicit resource buffer or energy storage. |
| Energy Bank Starter / Expanded / Advanced | 100,000 / 1,000,000 / 10,000,000 FE; 1,000 / 4,000 / 16,000 FE/tick input/output. |
| Buffered Interface | Nine real item slots; default 64 item units/tick, maximum 256 per interface. |
| Fluid/energy interfaces | Default 1,000 mB / 1,000 FE per tick; maxima 4,000 mB / 16,000 FE. |
| Fuel Generator | Vanilla furnace burn duration at 20 FE/fuel tick; 80 FE/tick maximum output; 100,000 FE buffer. Preserve container remainders. |
| Gateway nesting | Default eight, configurable finite server limit, hard maximum 32. |
| Program Controller power | Four FE/active tick plus one FE per begun 256 guest instructions; idle native rules do not execute guests continuously. |
| Wireless terminal access | Same dimension, 64-block range; 16 FE/active tick and 64 FE/action, bounded to 128 item units/action. |
| Paired branch links | Same dimension, 256-block endpoint range; 32 FE/connected tick plus bounded traffic costs; 256 item / 4,000 mB / 8,000 FE units per tick, under shared quotas. |

The current implementation includes cells/drives, physical finite banks and portable upgrades. Other
rows freeze initial choices for subsequent modules; they are not implemented
features. Wireless/local fairness and detailed power billing remain owning-stage
work and must be measured before release.

## Recipes and upgrades

Starter item cell: four iron ingots, four redstone dust, one chest. Starter fluid
cell: seven glass, one empty bucket, one redstone dust. Drive: four iron ingots,
four redstone dust, one hopper. [ADR 0009](0009-structural-leases.md) adds the
implemented controller (four iron, four redstone, one glass), eight cables (six
iron, three redstone) and gateway (four iron, two quartz, two redstone, one ender
pearl). Basic controller/cable/terminal progression uses iron, redstone,
wood/crafting table and glass without diamonds or Nether resources; the gateway
is a later segmentation tier. Terminal recipes remain pending.

The P2 bank extension selects four iron, four redstone and one copper ingot for
a Starter Energy Bank, unlocked by copper in the recipe book. Expanded/Advanced
banks use the same sequential Expansion/Advanced modules as portable cells; no
populated-bank crafting recipe exists. This gives finite starter power storage
without diamonds, Nether materials or an optional mod. A distinct bank-upgrade
material family would add unlock complexity without a new capacity axis. These
defaults remain subject to the complete generator/wireless survival playtest.

Expansion module: four gold, two redstone, one diamond. Advanced module: four
diamonds, two redstone, one netherite ingot. Infinite module: eight netherite ingots
and one Nether star. Apply modules sequentially to a cell held in the offhand by
using the module from the main hand. Replace only the cell's tier item, preserving
its single backing identity and claims. An infinite module accepts only Advanced
item cells. No vanilla recipe may consume a populated cell and emit a new empty
cell. Downgrades are not a crafting recipe; administrative capacity changes must
pass the ledger's safety check.

New cells are unbound until first inserted. Upgrading an unbound cell changes its
capacity prototype without manufacturing contents; upgrading a bound portable
cell validates its lease and resizes the existing ledger before consuming the
module. Stale, conflicted or placed handles reject without consuming ingredients.
Creative use does not consume modules but still follows owner/generation checks.

## Consequences and alternatives

An automatic populated-cell crafting recipe complicates server preview/take
atomicity. Direct module application gives one explicit server-thread transition
and keeps vanilla recipes from losing stored resources. Four drive slots bound
physical work without a gameplay type-count tier. Larger capacities deliberately
separate survival cost from metadata quotas. Infinite is earned through vanilla
endgame materials, not an optional mod or a freely duplicable contents item.

Require exact capacity/upgrade tests, recipe dependency/resource checks, actual
placement/removal/reload tests, and a starter-to-endgame playtest. The latter and
full powered/wireless validation are still pending; do not call D06 finalized.


## Bounded digital transfer quantum — 2026-10-11

Scheduled cell/bank commands now admit at most 128 items or 1,000 mB/FE per
request for 16 scheduler credits. A service permits 256 queued tickets and an
estimated 2 MiB payload budget in addition to shared scheduler and durable-record
quotas. These are provisional host-work bounds; they do not establish connector
throughput or final powered-machine balance. Splitting larger requests retains
shared fairness and accounting. Alternative unlimited single moves would make
credit cost unrelated to maximum mutation size. Representative performance and
combined powered progression evidence remain pending.


## Connector defaults — 2026-10-11

The Storage Connector now costs four copper ingots, four redstone and one hopper;
its layout differs from the Drive's iron recipe. It registers one selected
provider kind and has no automatic import/export rule or power upkeep. Default
rates are 64 item units or 1,000 mB/FE per tick in each direction, with both
provider and connector accounting. Configured maxima are 128 item units or
1,000 mB/FE under existing shared command budgets. Priority is -1,000..1,000;
item windows visit up to 64 slots starting at any valid platform index. These
are provisional admission/balance choices, not completed automatic selection or
measured throughput. Recipes/models load in headless checks; survival/client,
combined power demand and complete progression remain pending.


## Physical Buffered Interface starter recipe — 2026-10-12

Select four iron, two glass, two redstone and one hopper, arranged IGI/RHR/IGI.
This avoids the existing Storage Drive recipe and adds no diamonds/Nether/optional
mod prerequisite. Obtaining a hopper unlocks it. Nine physical slots cap stacks
at 64 while respecting smaller native maxima. Real hopper full-buffer backpressure
and recipe loading have headless evidence. Automatic network presets, measured
throughput and complete survival progression remain pending D06/P3/P6 validation.
