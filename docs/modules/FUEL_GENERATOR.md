# Fuel Generator

Implemented P2/P6 prerequisite for R02/R11 powered progression; see
[ADR 0027](../decisions/0027-fuel-generator.md). Balance follows
[ADR 0006](../decisions/0006-initial-balance.md).

## Core contract

`core.storage.FuelGenerator` is single-thread portable state in FE and burn ticks.
`CAPACITY` 100,000 FE, `PER_BURN_TICK` 20 FE, `MAX_OUTPUT` 80 FE per game tick.

- `needsFuel()` is true only when no burn is in progress and 20 FE fits.
- `ignite(burnTicks)` requires `needsFuel()` and a positive duration; the caller
  consumes the item afterwards. Otherwise it throws and changes nothing.
- `burn()` adds exactly 20 FE and decrements remaining ticks while BURNING, or does
  nothing when NO_FUEL or FULL. Remaining ticks persist through FULL pauses.
- `extract(requested, simulate, tick)` returns at most the stored FE and the unspent
  per-tick budget. Simulation never changes state; a backwards tick throws.
- `Snapshot(energy, burnRemaining, burnTotal)` rejects negative, over-capacity or
  inconsistent values instead of clamping them.

## Platform contract

`FuelGeneratorEntity` runs on the server thread through a block-entity ticker:
ignite (when the fuel and remainder fit), burn, then push to neighbors. Capabilities:
`EnergyStorage.BLOCK` on every face (extract-only, shared budget) and
`ItemHandler.BLOCK` with one visible slot per face (fuel insert on non-down faces,
remainder extract from down). Unsupported data disables both. `blockedReason()`
returns `no_fuel`, `full`, `remainder_full`, `unsupported` or empty while burning;
the LIT block state mirrors BURNING.

Persistence, drops and retained-data behavior are described in ADR 0027. Neighbor
capability caches are per-face, created lazily and cleared on removal.
