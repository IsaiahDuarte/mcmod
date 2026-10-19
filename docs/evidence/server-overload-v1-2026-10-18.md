# Integrated server overload and idle measurement — 2026-10-18

Partial A15/D07 evidence, extending the earlier three-phase measurement. Command:
`./gradlew runBenchmarkServer` — PASS in 5m51s. One measured run above main
`c7cc16c` with the integrated worktree changes. Apple M1, 8 CPUs, 8 GiB RAM, macOS
15.3.1 arm64, OpenJDK 21.0.12, Minecraft 1.21.1, NeoForge 21.1.252, dedicated
`--nogui` server, 2 GiB max heap, flat world, view/simulation distance 2. Loaded
Minecraft, NeoForge, Factory Core and the excluded verification fixture only.

No concurrent build, client or agent test ran during this measurement. OS processes,
GC and CPU scheduling were not controlled, so all outliers are retained and this
is one local sample, not final controlled/repeated release evidence.

## Workload and observed results

Each phase has 200 warmup and 1,000 measured ticks. The initial 64 factories each
have a controller, cell holding 4,096 cobblestone, Program Controller, four labeled
Machine Interfaces and vanilla chests. The program keeps each chest at 64 and
moves 16 back every 10 ticks. Small activates 4 programs, Large 64. Overload adds
64 factories (128 total) with eight staggered competing rules each (1,024 rules),
restocking chests and moving up to 64 back. Programs receive fixture FE outside
the measured mod span. Final idle-wide stops every program and adds 896 controllers
to reach 1,024 networks; it is not the full D07 65,536-configured-program scenario.

| Phase | Mod median ms | Mod p99 ms | Mod max ms | Ticks >2 / >5 / >10 ms | Queue max | Whole-server p99 ms |
| --- | --- | --- | --- | --- | --- | --- |
| Idle (64 networks) | 0.0337 | 0.0753 | 0.1825 | 0 / 0 / 0 | 0 | 0.4941 |
| Small | 0.5453 | 1.6980 | 7.1073 | 9 / 4 / 0 | 8 | 2.6619 |
| Large | 1.6515 | 3.6215 | 11.7166 | 174 / 4 / 1 | 122 | 5.5204 |
| Overload | 1.7075 | 2.8573 | 15.2029 | 313 / 8 / 2 | 1,118 | 6.2280 |
| Idle-wide (1,024 networks) | 0.0421 | 0.0786 | 0.1185 | 0 / 0 / 0 | 0 | 0.3721 |

The mod span covers Factory Core's Post tick listeners; block-entity ticks are
outside it. Whole-server ticks include Minecraft work. The idle-wide whole-server
maximum was 53.1791 ms despite a mod-span maximum of 0.1185 ms. The cause was not
measured. End-of-run total JVM used heap was 458,195,248 bytes; mod-owned memory
was not isolated and this is not a mod-memory-budget proof.

Conservation passed exactly: 524,288 expected = 524,288 actual. Large/overload
contained 15,616 / 17,392 items in machine chests, proving actual churn. Overload
queue p99/max was 1,092 / 1,118, below the fixed 8,192 bound. Every one of the
1,000 idle-wide measured ticks had backlog zero. Large/overload program diagnostics
reported admission `technical_limit`; refusals are retained, not counted as work.

P99 spans fit the 2 ms small / 5 ms large-overload readings used in the earlier
record, but per-tick targets were **not universally met**: small had 9 ticks above
2 ms, large had 4 above 5 ms, overload had 8 above 5 ms; large/overload also exceeded
10 ms once/twice. Targets are unchanged. Queue latency, exact units/tick, allocations,
metadata-heavy/giant-count server workloads, crafting/Wasm, packet/client budgets,
remote multiplayer, repeated controlled runs and cross-OS evidence remain open.

## Raw evidence

[Complete summary](server-overload-v1-2026-10-18-summary.txt).
[5,000-row gzip CSV](server-overload-v1-2026-10-18.csv.gz), deterministic gzip mtime 0.
SHA-256: `ac8b0055c40e58bca194ade50e0018767be65ca1884d96784d6db6f5e1947b97`.
