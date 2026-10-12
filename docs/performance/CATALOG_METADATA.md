# Catalog metadata paging measurement

Measured 2026-10-12 on MacBookPro17,1, 8 physical CPUs, 8 GiB RAM,
macOS 15.3.1 arm64, Homebrew OpenJDK 21.0.12. This is a standalone metadata
comparison, not Minecraft tick, network, guest or full release performance evidence.

## Workload and method

Construct 1,024 canonical digital devices, each containing 64 exact item keys
(65,536 total resource entries, the current world entry ceiling). Compare the
existing full-save `DeviceRegistry.snapshot()` followed by reading first metadata
against the new eight-handle metadata page. Both consume a generation into a
volatile sink. The baseline is the existing snapshot API, not a claim that an
implemented former terminal used it. Warm up 50 full snapshots and 10,000 pages;
measure 100 snapshot calls and 10,000 page calls using nanoTime plus HotSpot
per-thread allocation counters. Report one process run; GC/JIT/system noise and
unequal sample counts limit direct timing inference. Array measurement overhead
is subtracted from allocated bytes. No timing assertion is added to CI.

Reproduce from the repository root with Java 21:

```text
./gradlew testClasses
java -Xms256m -Xmx1g -cp build/classes/java/test:build/classes/java/main dev.izzy.factorycore.core.storage.MetadataPageBenchmark
```

The class is compiled by the normal JVM/static gates; run it explicitly for
measurements. It needs only the portable core, not Minecraft bootstrap or a world.

## Recorded result

| Approach | Samples | Median µs/call | Approximate p95 µs/call | Allocated bytes/call |
| --- | ---: | ---: | ---: | ---: |
| Full-save snapshot | 100 | 1654.417 | 2562.917 | 2233677.4 |
| Eight current handles | 10000 | 0.417 | 0.500 | 512.0 |

The observed allocation difference supports keeping ledger snapshots out of
catalog enumeration. Metadata maps now use a UUID-ordered tree; seek costs
O(log devices), with at most eight inspected records per world catalog step.
Tree updates cost O(log devices) instead of expected hash-map O(1); portable
lifecycle/restore tests still pass. Full snapshots remain the persistence API.
The measured page does not include authorization, physical lookups, foreign
provider marker validation or fresh inventory reads. Those can dominate real
catalog work and still require representative world measurements. World pages
may inspect eight providers with up to 64 item/tank positions each, on shared
16-credit scheduling; these provisional bounds promise no throughput target.
