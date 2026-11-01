# Q1 — Networks stay online when chunks load nearby (2026-10-31)

PD7 resolved yes 2026-10-31. ADR 0011 amended.

## Change

- **`core/network/ChunkEdgeIndex`:** keeps per-chunk and per-chunk-edge region counts.
  `affected(chunk)` returns the chunk's own regions and, in each horizontal neighbour, only the
  regions with a position on the edge facing it.
- **`WorldNetworks.chunkChanged`:** uses it for loads and unloads. Before, every region in the
  four neighbour chunks was invalidated, so walking near a base briefly took its networks
  offline.

## Tests

- **`ChunkEdgeIndexTest`:**
  - own chunk, east-edge neighbour, untouched west and south neighbours, diagonals, other
    dimensions;
  - negative coordinates, a corner position on two edges, merging and removing with exact
    counts.
- **`WorldNetworksGameTests.neighbourChunkLoadAwayFromTheNetworkKeepsItOnline`:** a load and
  unload event for a neighbouring chunk whose shared edge holds none of the network leaves it
  VALID with stock extractable at once. The fixture's blocks share one z, so either the north or
  the south neighbour is untouched by construction.
- **`ownChunkLoadInvalidatesItsNetworkAndRevalidates`:** replaces
  `firstNeighborChunkLoadInvalidatesExistingBoundaryPublication`. The network's own chunk still
  invalidates at once and revalidates with stock preserved.
  - **Not covered by a GameTest:** the "edge holds a cable" case across a real chunk border. The
    6×6 test template cannot be placed on a chunk edge deterministically, so
    `ChunkEdgeIndexTest` covers that case with exact coordinates.
- **Stability:** in a clean worktree holding only this change, `python3 scripts/verify.py`
  passed (rc 0). Then 10 consecutive full GameTest runs passed, 495 of 495 each.

## Measurement

[Chunk-edge invalidation](../performance/chunk-edge-invalidation-2026-10-31.md): over the first
1,000 chunk events of a full GameTest run, 508 region invalidations instead of 1,894; the lookup
took 9.3 µs on average.
