# ADR 0027 — Standalone vanilla Fuel Generator

Status: selected and locally verified for P2/P6 powered prerequisites. Survival
balance, client visuals and combined powered progression remain D06/P6 validation.

## Context

R02/R11 require a baseline FE source that needs no other technology mod and
preserves fuel-container remainders. ADR 0006 fixed 20 FE per burn tick, an
80 FE/tick maximum output and a 100,000 FE buffer.

## Decision

The Fuel Generator is a standalone block entity, not a network node: it needs no
controller, lease or grants, and generated FE is not network-owned until another
device accepts it. Portable `FuelGenerator` owns the exact FE/burn accounting:

- Fuel uses the platform smelting burn duration (`ItemStack.getBurnTime(SMELTING)`).
  One item is consumed only when a whole burn tick (20 FE) fits and its crafting
  remainder fits the remainder slot.
- Burning pauses when 20 FE no longer fits, retaining remaining burn ticks, so no
  generated FE is discarded. A full buffer keeps queued fuel.
- All output (adjacent push plus capability extraction) shares one 80 FE budget per
  game tick. Simulation never spends it. The generator never accepts FE.
- Each tick it pushes to adjacent `IEnergyStorage` receivers in fixed direction
  order, using cached capability lookups. Only the reported accepted amount,
  clamped to the offer, is deducted; a receiver reporting more than offered cannot
  have received more than was offered.

Slot 0 accepts fuel from every face except down; slot 1 receives remainders and
is extractable only from below, matching furnace hopper conventions. Players insert
held fuel, inspect status with an empty hand, and sneak-use to take remainders
then fuel.

Block-entity schema1 stores `Energy`, `Burn`, `BurnTotal` and the two-slot
`Items`. Breaking copies exact FE and remaining burn into the
`factorycore:generator_state` drop component and drops both slots, so placement
restores state without loss. A different schema or invalid values are retained
unchanged, the device stays inert (no FE, fuel or push), and breaking carries the
exact retained tag in `factorycore:generator_retained` for re-placement. Nothing
resets silently.

Recipe ICI/CFC/IRI (four iron, three copper, one furnace, one redstone) unlocks
with a furnace. It needs no diamonds, Nether materials or optional mods.

## Alternatives

A network-owned generator ledger would add ownership, lease and recovery work for
a device whose energy is freely produced; standalone storage plus normal
capability transfer keeps accounting exact. Burning while full (vanilla style)
would waste fuel energy. Dropping the generator without its buffer would lose
up to 100,000 FE. Using vanilla block-entity data for retained future data merges
into fresh state and cannot preserve exact bytes.

## Consequences and evidence

Generators charge banks directly or through connectors; network routing of FE is
unchanged. No GUI exists yet; status and interaction are chat/hand based. The
model uses vanilla blast-furnace/copper textures with lit/facing states; finished
visual verification is P6. FuelGeneratorTest covers burn/full/budget/snapshot
rules; eight FuelGeneratorGameTests cover real hopper fuel, lava remainders, full
remainder slot, full buffer and shared capability cap, conserved push into a
networked bank, break/place preservation, future-schema retention and the recipe.
