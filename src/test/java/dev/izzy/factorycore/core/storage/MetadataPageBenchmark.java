package dev.izzy.factorycore.core.storage;

import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Standalone comparison of metadata enumeration against the existing full-save snapshot API. */
public final class MetadataPageBenchmark {
  private static volatile long consumed;

  private MetadataPageBenchmark() {}

  public static void main(String[] args) {
    var ids = new AtomicLong();
    var registry =
        new DeviceRegistry(new UUID(0, 1), () -> {}, () -> new UUID(1, ids.incrementAndGet()));
    for (int index = 0; index < 1024; index++) {
      var handle = registry.create(ResourceKind.ITEM, 1000, false, 4096, 8192).handle();
      var location = new DeviceRegistry.Location("minecraft:overworld", index, 0);
      handle = registry.attach(handle, location).handle();
      registry.availability(handle, location, true);
      for (int key = 0; key < 64; key++) {
        var identity =
            new ResourceKey(ResourceKind.ITEM, "factorycore:benchmark_" + key, new byte[0]);
        if (registry.insert(handle, identity, 1).amount() != 1)
          throw new IllegalStateException("Benchmark setup quota");
      }
    }
    Runnable snapshot = () -> consumed += registry.snapshot().devices().getFirst().generation();
    Runnable page = () -> consumed += registry.handles(null, 8).handles().getFirst().generation();
    for (int warmup = 0; warmup < 50; warmup++) snapshot.run();
    for (int warmup = 0; warmup < 10000; warmup++) page.run();
    measure("full_snapshot", snapshot, 100);
    measure("eight_handles", page, 10000);
    System.out.println("consumed=" + consumed);
  }

  private static void measure(String name, Runnable operation, int repetitions) {
    var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    if (!bean.isThreadAllocatedMemorySupported())
      throw new IllegalStateException("Allocation measurement unavailable");
    bean.setThreadAllocatedMemoryEnabled(true);
    long thread = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(thread);
    long[] times = new long[repetitions];
    for (int sample = 0; sample < repetitions; sample++) {
      long start = System.nanoTime();
      operation.run();
      times[sample] = System.nanoTime() - start;
    }
    long bytes = bean.getThreadAllocatedBytes(thread) - before - 8L * repetitions - 16;
    Arrays.sort(times);
    System.out.printf(
        java.util.Locale.ROOT,
        "%s samples=%d median_us=%.3f p95_us=%.3f bytes_per_call=%.1f%n",
        name,
        repetitions,
        times[repetitions / 2] / 1000.0,
        times[repetitions * 95 / 100] / 1000.0,
        bytes / (double) repetitions);
  }
}
