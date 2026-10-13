# Storage-search component evidence — 2026-10-12

Server-side P3 prerequisite evidence, not a terminal/client/release acceptance pass.
The selected design is [ADR 0015](../decisions/0015-bounded-storage-search.md).

## Behavioral checks

Final `python3 scripts/verify.py` passed: 24 tooling tests, 177 JVM tests with zero
failures/errors/skips, and 125 required Minecraft GameTests. Gradle build completed
in 34 seconds; 10 tasks executed and 12 were up-to-date, including Rust guest
verification. Six new CatalogPageTest cases and eleven StorageSearchGameTests cover
exact metadata/claims, maximal/deleted cursors, generated restore/mutation (seed
0xCA7A106), signed-long-exceeding aggregates, offline/stale leases, real multi-slot
providers, per-backing held-once counts, private no-probe behavior, queued grants,
retained-view invalidation, typed faults, lifecycle/limits, mB/FE/capacity, inactive-ledger exclusion and shared
heavy-metadata reclamation. Existing migration/ownership/transfer tests also passed.

The heavy world fixture owns 100 exact keys with 60,000-byte custom data. Three
concurrent broad queries exceed the shared sixteen-MiB budget: successful queries
retain bounded results, rejected queries return TECHNICAL_LIMIT without partial
rows, and closing them allows one replacement heavy query to finish. No resources
are mutated by these queries. This does not measure server latency or network cost.

## Standalone comparison

Hardware: Apple M1, 8 GiB RAM; macOS 15.3.1 arm64; Homebrew OpenJDK 21.0.12.
Two separate JVMs use 256-MiB initial/512-MiB maximum heap. The baseline is the
actual ResourceLedger source from d36268b compiled into a temporary classpath;
current source SHA-256 is in the [raw report](catalog-pages-v1-2026-10-12.txt).
No second Gradle/JVM build ran during the final comparison. Shared-machine/JIT/
GC noise remains; this is a component sample, not a controlled P7 server study.

Commands:

```text
./gradlew spotlessApply compileTestJava
python3 scripts/benchmark_ledger_catalog.py --output docs/evidence/catalog-pages-v1-2026-10-12.txt
```

The benchmark uses 10,000 registry-only keys and 2,048 same-ID keys sharing a
4-KiB component prefix. It warms 50 full copy/sort operations and 10,000 operations
per mutation/type-churn case; current bounded pages receive 10,000 warmups.
Samples: 20,000 mutation pairs, 20,000 type-churn pairs, 200 copy/sort queries and
10,000 bounded pages. Stock remains one unit/key after each pair. Copy/sort and
bounded-page workloads both discover the first sixteen exact stock rows.

| Dataset / operation | Prior HashMap median µs | Current indexed median µs |
| --- | --- | --- |
| Registry-only existing-key insert/extract | 0.167 | 0.125 |
| Registry-only whole-type removal/recreation | 0.166 | 0.375 |
| Shared 4-KiB prefix existing-key insert/extract | 1.709 | 1.792 |
| Shared 4-KiB prefix whole-type removal/recreation | 3.791 | 6.917 |
| Registry-only copy/sort first sixteen | 1539.625 | 1526.875 |
| Shared 4-KiB prefix copy/sort first sixteen | 191913.417 | 175810.500 |
| Registry-only bounded first sixteen | Unavailable | 0.625 |
| Shared 4-KiB prefix bounded first sixteen | Unavailable | 2.334 |

Registry-only copied queries allocate 373,440 bytes/call, versus 1,528 for indexed
pages. Shared-prefix copied baseline queries allocate about 73,853 bytes/call,
versus 1,472 for indexed pages. Raw p95/allocation samples are linked above.
The prefix case intentionally stresses metadata comparisons/hash collisions;
its full-copy cost is not representative of every Minecraft catalog.

The initial sorted-contents variant made existing-key updates slower: its shared
prefix median was 10.500 µs versus baseline 1.667. Its
[experimental raw report](catalog-pages-tree-map-experiment-v1-2026-10-12.txt)
is retained to explain rejection; that early run overlapped development build
activity and is diagnostic rather than a final controlled comparison. The final
implementation retains HashMap accounting and adds a sorted occupied-key index.
Existing-key costs remain close at this scale; whole-type creation/removal pays
extra tree work. It adds one bounded key reference/tree entry per occupied type
(up to the existing 65,536 world entry ceiling), without another quantity owner
or persisted table. Heap impact and full server/terminal workload remain P7 work.

Do not extrapolate these first-page timings to complete all-key scans, native
provider CPU time, client payloads, large multiplayer workloads or release tick
latency. Full query duration depends on sources, metadata, filtering, quotas and
shared scheduling. Actual client/terminal behavior, complete server restart,
independent-file interruption and platform compatibility remain unverified.
