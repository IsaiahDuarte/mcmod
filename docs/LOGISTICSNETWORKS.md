# LogisticsNetworks comparison

## Evidence scope

Reviewed on 2026-10-03: Almana-mc/LogisticsNetworks branch 26.1.2 at revision 81d11ba237ce25a980648b74f8c5f169e2d621b7. Read public documentation, source-tree paths, and selected implementation files. No mod runtime or performance benchmark was run. Features may differ across releases.

## Overlap

| Area | Observed LogisticsNetworks behavior | Relationship to our spec |
| --- | --- | --- |
| Transport | Wireless sender/receiver nodes with resource channels, sides, filters, batch/delay, and distribution settings. | Strong overlap with basic item/fluid/energy routing. |
| Filters and convenience | Tags, stock thresholds, data/slot rules, JEI ghost dragging, and configuration copying. | Strong overlap; these are expected usability features, not unique differentiators. |
| Labels and monitoring | Node labels/grouping and a Computer dashboard with channel throughput monitoring. Graph-editor classes also exist. | Overlaps labels, diagnostics, and visual network inspection. |
| Progression | Node throughput upgrades plus an upgrade for cross-dimensional transfers. | Our separate capacity/throughput/connection progression needs its own playtesting. |
| Storage/crafting bridges | Selected AE2/Refined Storage integration classes initiate crafting through those mods and track returned resources. | Do not describe LogisticsNetworks as having no crafting integration. Our planned owned storage and independent planner have different responsibilities. |
| Programmability | No Wasm runtime, Rust guest SDK, or user automation language was identified in the reviewed documentation/tree. | Our planned programming interface is a potential distinction; absence was not proven across every branch/artifact. |
| Branch isolation | No equivalent of our physical gateway boundaries with private machine discovery and shared-storage views was established by this review. | Treat as a planned distinction requiring implementation, not an audited claim that LogisticsNetworks cannot isolate networks. |

The [channel guide](https://github.com/Almana-mc/LogisticsNetworks/blob/81d11ba237ce25a980648b74f8c5f169e2d621b7/src/main/resources/assets/logisticsnetworks/guides/logisticsnetworks/guide/nodes/channel-settings.md) describes sender/receiver matching and resource settings. The [filter guide](https://github.com/Almana-mc/LogisticsNetworks/blob/81d11ba237ce25a980648b74f8c5f169e2d621b7/src/main/resources/assets/logisticsnetworks/guides/logisticsnetworks/guide/filters/index.md) documents stock rules and JEI dragging.

The [Computer guide](https://github.com/Almana-mc/LogisticsNetworks/blob/81d11ba237ce25a980648b74f8c5f169e2d621b7/src/main/resources/assets/logisticsnetworks/guides/logisticsnetworks/guide/computer/index.md) describes network management, and the [I/O monitor guide](https://github.com/Almana-mc/LogisticsNetworks/blob/81d11ba237ce25a980648b74f8c5f169e2d621b7/src/main/resources/assets/logisticsnetworks/guides/logisticsnetworks/guide/computer/io-monitor.md) describes live channel telemetry. This does not by itself establish an item-storage terminal.

The [AE2 crafting batch](https://github.com/Almana-mc/LogisticsNetworks/blob/81d11ba237ce25a980648b74f8c5f169e2d621b7/src/main/java/me/almana/logisticsnetworks/integration/ae2/AE2StorageCraftingBatch.java) and [Refined Storage crafting batch](https://github.com/Almana-mc/LogisticsNetworks/blob/81d11ba237ce25a980648b74f8c5f169e2d621b7/src/main/java/me/almana/logisticsnetworks/integration/refinedstorage/RefinedStorageCraftingBatch.java) contain calls into their respective crafting services. This is source-level evidence, not end-to-end compatibility testing.

## Version-specific caution

The repository README advertises Mekanism chemical transport, but this revision's [special-upgrade guide](https://github.com/Almana-mc/LogisticsNetworks/blob/81d11ba237ce25a980648b74f8c5f169e2d621b7/src/main/resources/assets/logisticsnetworks/guides/logisticsnetworks/guide/nodes/upgrades-special.md) calls Chemical/Source upgrades inactive. [MekanismCompat](https://github.com/Almana-mc/LogisticsNetworks/blob/81d11ba237ce25a980648b74f8c5f169e2d621b7/src/main/java/me/almana/logisticsnetworks/integration/mekanism/MekanismCompat.java) returns false from its loaded check. Do not infer present chemical support from the README alone, or generalize this finding to older branches.

## Consequences for this design

- Prioritize a cohesive owned storage terminal, independent autocrafting, programmable branches, and clear authority/ownership contracts.
- Retain good common conveniences: JEI dragging, labels, copying configurations, stock thresholds, and diagnostics.
- Keep wired infrastructure useful across progression; unlock bounded wireless convenience and explicit branch links later in survival.
- Compare performance only on matched workloads and environments. Their advertised throughput is not a benchmark of our future implementation.
- Study designs without importing source into this project. Reusing code would require a separate licensing/dependency review and an explicit implementation decision.

The comparison is research context. [SPEC.md](../SPEC.md) remains authoritative.
