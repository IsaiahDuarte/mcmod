# Q36 — Cross-dimension links (2026-11-01)

QD10 resolved: cross-dimension links yes. ADR 0040 amended; SPEC and the player guide updated.

## Change

- **`LinkModule`:**
  - Saves the partner's dimension (`PartnerDimension`). A link saved without one names its own
    dimension; an invalid one is kept as unknown data.
  - New methods: `partnerPosition()`, `partnerLevel()`, `crossDimension()`, `tickCost()` (32, or
    `CROSS_DIMENSION_COST` 128), `partnerOf()` and `partnerUnloaded()`.
  - The range check applies only within one dimension. `pair()`, `pairScale()` and `refresh()`
    use the partner's own level.
- **`LinkPairing`:** the selection remembers its dimension key. Pairing accepts a second gateway
  in another dimension within 60 seconds, timed by the Overworld clock. A new overload,
  `pair(player, levelA, a, levelB, b)`, is added.
- **`WorldWireReader`:** the link's port names the partner's dimension.
- **`WorldNetworks`:** `bridged` compares full positions. Metering skips a bridged link whose
  partner is unloaded instead of invalidating the region. Before, that invalidation looped
  forever (REBUILDING), and it could hit same-dimension pairs too.
- **`WirelessBudget.Link`:** accepts ends in different dimensions, ordered by dimension and then
  block.

## Tests

- **`CrossDimensionLinkGameTests.anOverworldNetherPairJoinsTheNetherBranchAndWaitsWhileItIsUnloaded`:**
  - An Overworld controller and gateway link, and a Nether gateway plus drive 4000 blocks east,
    sit in a force-loaded chunk.
  - Unpaired, the Nether drive has NO_ROOT. Paired across dimensions: both ends report the cost
    and the partner dimension, the Nether drive joins the Overworld network as VALID, and the pair
    is metered.
  - **Unloading the Nether chunk:**
    - The chunk unloads, and the Overworld root stays VALID.
    - The Nether branch is not VALID, and `pair()` is null.
    - For 20 more ticks the chunk stays unloaded, so waiting never loads it.
  - Force-loading it again makes the branch rejoin.
- **`WirelessBudgetTest.aCrossDimensionPairHasOneIdentityWhicheverEndNamesIt`**.
- **Full GameTest run:** 521 of 521 pass.
