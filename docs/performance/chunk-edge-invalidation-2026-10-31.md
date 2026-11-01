# Chunk-edge invalidation (PD7, Q1) — 2026-10-31

ADR 0011 asked for the chunk callback's cost before narrowing it. This records the cost and how
many network invalidations the narrowing removes.

## Workload

- **Run:** one full GameTest server run (`./gradlew runGameTestServer`, 495 tests), with a
  temporary probe in `WorldNetworks.chunkChanged`. On each chunk load or unload, the probe
  computed both the new set (`ChunkEdgeIndex.affected`: own chunk plus facing edges) and the
  old set (own chunk plus the four neighbor chunks whole). The probe was removed before commit.
- **Sample:** the first 1,000 chunk events of the run. The GameTest grid places many small
  networks close together, which is denser than a typical base.
- **Machine:** Intel Core i7-12700K (20 threads), JDK 21 (`~/.local/jdk-21`), Linux. The machine
  was not quiet: a Minecraft client was running.

## Results

| | Old rule (four neighbors whole) | New rule (facing edges) |
| --- | --- | --- |
| Region invalidations | 1,894 | 508 (−73%) |
| Events that invalidated anything | 263 | 192 |

- **Lookup cost (new rule only):** 9.3 µs mean, including JIT warm-up; 0.36 ms worst
  (the first calls). A lookup visits five hash maps and never a region's positions, so the
  cost does not grow with network size.
- **Index cost:** the edge counts are updated with the existing chunk counts when a position
  joins or leaves a region, at most four extra map updates for a corner position.

## Trade-offs

- **Accepted:** connections across a chunk border run only between positions on the two
  facing edges, so no connection is missed. Diagonal chunks never connect.
- **Unchanged:** the network's own chunk still invalidates all of its regions on load and
  unload, as before.
