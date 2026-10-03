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

The current slice implements cells/drives and direct cell upgrades only. Other
rows freeze initial choices for subsequent modules; they are not implemented
features. Wireless/local fairness and detailed power billing remain owning-stage
work and must be measured before release.

## Recipes and upgrades

Starter item cell: four iron ingots, four redstone dust, one chest. Starter fluid
cell: seven glass, one empty bucket, one redstone dust. Drive: four iron ingots,
four redstone dust, one hopper. Later basic controller/cable/terminal recipes use
iron, redstone, wood/crafting table and glass without diamonds or Nether resources.

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
