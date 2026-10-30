# Q108 — Storage discovery by network

Scope: [ergonomics plan](../ERGONOMICS_PLAN.md) Q108 (finding M147, found while investigating
M145). Design: [ADR 0067](../decisions/0067-storage-discovery-by-network.md); docs in
[STORAGE_SEARCH](../modules/STORAGE_SEARCH.md).

## Change

Each network region records, as positions are prepared, Storage Drive and Energy Bank positions
and connector ids. `WorldNetworks.storageDevices(origin)` and `storageConnectors(origin)` return
them (devices resolved through `DeviceRegistry.placedAt`, cached until
`placementRevision` changes), or null while the network is not prepared, when the catalog falls
back to the world registries. `DeviceRegistry.handles(within, after, limit)` and
`NodeRegistry.handles(within, after, limit)` page those sets with the old order, page size and
cursor. Every endpoint is still checked as before.

## Checks

- `DeviceRegistryTest.pagesWithinASetVisitOnlyItsIdsAndPlacementsAreFoundByLocation` and
  `NodeRegistryTest.pagesWithinASetVisitOnlyItsIdsWithCurrentGenerations`: filtered pages visit
  only the set, in order, skip ids no longer registered, return current generations;
  `placedAt` and the placement revision follow attach and detach.
- `BufferExportGameTests.discoveryVisitsOnlyItsOwnNetworksCells`: a second network with its own
  cell beside the buffer's, and the world holds every other test's cells. The buffer's network
  lists exactly its own cell, and an all-key catalog read finds the iron on its first page.
  Before Q108 the same read walked the world registry eight owners per page (more than 80 empty
  pages were traced in a full run for M145).
- 20 full GameTest runs in a row, all passing, including
  `stockStopsAndReplenishesWithoutOverWithdrawing`, which failed 7 of 19 runs before. Runs 1–8
  were Q108 alone (444 tests); runs 9–20 also held the Q107 code being written next to it (445
  tests). Logs were kept in the session scratchpad.
- `python3 scripts/verify.py`: see the Q107 evidence; verify ran once on the tree holding both
  slices.

Not measured: catalog cost on a large survival world. The bound is now the asking network's own
cells and interfaces, not the world's.
