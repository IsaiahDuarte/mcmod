package dev.izzy.factorycore.core.resource;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.UUID;

/** Standalone first-page and mutation comparison; baseline mode uses the prior compiled ledger. */
public final class LedgerCatalogBenchmark {
  private static volatile long consumed;

  private LedgerCatalogBenchmark() {}

  public static void main(String[] args) {
    boolean baseline = args.length > 0 && args[0].equals("baseline");
    for (int metadata : new int[] {0, 4096}) {
      int size = metadata == 0 ? 10000 : 2048;
      var keys = new ResourceKey[size];
      var ledger =
          new ResourceLedger(new UUID(0, 1), ResourceKind.ITEM, Long.MAX_VALUE, true, size, size);
      for (int index = 0; index < size; index++) {
        var bytes = new byte[metadata];
        if (metadata > 0) {
          bytes[metadata - 2] = (byte) (index >>> 8);
          bytes[metadata - 1] = (byte) index;
        }
        keys[index] =
            new ResourceKey(
                ResourceKind.ITEM,
                metadata == 0 ? "factorycore:key_" + index : "factorycore:metadata",
                bytes);
        ledger.insert(keys[index], 1);
      }
      Runnable full =
          () ->
              consumed +=
                  ledger.snapshot().keySet().stream()
                      .sorted()
                      .limit(16)
                      .mapToLong(key -> ledger.stock(key).total())
                      .sum();
      int[] cursor = {0};
      Runnable mutate =
          () -> {
            var key = keys[cursor[0]++ % size];
            consumed += ledger.insert(key, 1).amount();
            consumed += ledger.extract(key, 1, null).amount();
          };
      Runnable churn =
          () -> {
            var key = keys[cursor[0]++ % size];
            consumed += ledger.extract(key, 1, null).amount();
            consumed += ledger.insert(key, 1).amount();
          };
      for (int warmup = 0; warmup < 50; warmup++) full.run();
      for (int warmup = 0; warmup < 10000; warmup++) mutate.run();
      measure(
          (baseline ? "baseline" : "current") + "_metadata" + metadata + "_mutation_pair",
          mutate,
          20000);
      for (int warmup = 0; warmup < 10000; warmup++) churn.run();
      measure(
          (baseline ? "baseline" : "current") + "_metadata" + metadata + "_type_churn",
          churn,
          20000);
      measure(
          (baseline ? "baseline" : "current") + "_metadata" + metadata + "_copy_sort_first16",
          full,
          200);
      if (!baseline) {
        Runnable page = () -> consumed += ledger.catalog(null, 16).entries().size();
        for (int warmup = 0; warmup < 10000; warmup++) page.run();
        measure("current_metadata" + metadata + "_catalog_first16", page, 10000);
      }
    }
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
    for (int index = 0; index < repetitions; index++) {
      long start = System.nanoTime();
      operation.run();
      times[index] = System.nanoTime() - start;
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
