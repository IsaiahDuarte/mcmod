# 0067 — Storage discovery by network

Status: accepted 2026-10-30 (ergonomics plan Q108, finding M147).

## Context

Every storage read that does not already know where stock is (Buffered Interface export and stock,
machine presets, program storage moves, terminal search) pages through the all-key catalog
([STORAGE_SEARCH](../modules/STORAGE_SEARCH.md)). Each page examined the next eight owners of
the whole world's `DeviceRegistry` (cells and energy banks), then of its `NodeRegistry`
(connectors), skipping those outside the asking network. A page with no owner of the asking
network returned no rows, and each page cost a scheduler slot, about two ticks. So the time to
reach a network's own stock grew with the number of cells and connectors in the whole world. In a
full GameTest run, where hundreds of networks exist at once, one buffer paged past more than 80
empty pages, and a stock test missed its deadline in 7 of 19 runs.

## Decision

Each network region records, while its positions are prepared after a rebuild, the positions
that hold registry devices (a Storage Drive's bays, an Energy Bank's slot 0) and the ids of its
connectors (Storage and Machine Interfaces). `WorldNetworks.storageDevices(origin)` turns the
device positions into the backings placed there through `DeviceRegistry.placedAt`, cached per
region until `DeviceRegistry.placementRevision` changes (any placement, move or removal in the
world). `storageConnectors(origin)` returns the connector ids. The catalog pages through these
sets with `DeviceRegistry.handles(within, after, limit)` and `NodeRegistry.handles(within, after,
limit)`, which keep the same UUID order, page size and cursor as before and return current
handles. Each endpoint is still checked as before; the index only narrows the candidates.

Placing a drive, bank or interface rebuilds the network, so the position lists are complete once
it is prepared. Swapping a cell in a drive changes only the placement revision, which the next
read notices. While a network is not prepared (mid-rebuild) the read falls back to the world
registries, which is correct, only slower.

## Alternatives

- **An index per segment.** Rejected: each endpoint is already checked against the asking
  segment's authority; a per-network index removes the world-sized scan, and segment indexes
  would have to follow gateway policy changes that do not rebuild the network.
- **A larger scan per page.** Rejected: 64 owners per page cuts the pages eightfold but keeps them
  growing with the world, and makes each page costlier.
- **An index maintained in the registries by network.** Rejected: registries know locations, not
  networks; networks change on rebuilds the registries never see.
- **Rebuilding the set on every page.** Rejected: up to bays × drives map lookups per page per
  reader; the revision cache makes it once per placement change.

## Consequences

Discovery visits only the asking network's endpoints, a fixed number of pages for a given
network, whatever else exists in the world. The cache holds one set of UUIDs per network region.
`BufferExportGameTests.discoveryVisitsOnlyItsOwnNetworksCells` pins it: with a second network
beside it and every other test's cells in the world, the buffer's network lists only its own cell
and a catalog read finds its iron on the first page.
