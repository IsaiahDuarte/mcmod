package dev.izzy.factorycore.core.wasm;

import dev.izzy.factorycore.core.program.ProgramEvaluator;
import dev.izzy.factorycore.core.resource.ResourceFilter;
import java.io.BufferedWriter;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Manual measurements of the production Wasm runtime running the SDK smelter example: cold
 * instantiation and 100 rule invocations per sample, with thread allocation. Desktop timing is
 * evidence, not a CI latency assertion. Run with {@code ./gradlew wasmBenchmark}.
 */
public final class WasmBenchmark {
  private WasmBenchmark() {}

  public static void main(String[] arguments) throws Exception {
    byte[] guest = Files.readAllBytes(Path.of(System.getProperty("factorycore.test.smelter")));
    var labels =
        Map.of(
            "smelter_in", List.of(new UUID(0, 1), new UUID(0, 2)),
            "smelter_out", List.of(new UUID(0, 3)));
    ProgramEvaluator.Host host =
        new ProgramEvaluator.Host() {
          @Override
          public List<UUID> machines(String label) {
            return labels.getOrDefault(label, List.of());
          }

          @Override
          public long stock(ResourceFilter filter) {
            return 0;
          }
        };
    var allocations = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    if (!allocations.isThreadAllocatedMemorySupported())
      throw new IllegalStateException("allocation measurement unavailable");
    allocations.setThreadAllocatedMemoryEnabled(true);
    long thread = Thread.currentThread().threadId();
    Path output = Path.of("build/benchmarks/wasm-v2.csv");
    Files.createDirectories(output.getParent());
    try (BufferedWriter writer = Files.newBufferedWriter(output)) {
      writer.write("pass,sample,startup_ns,startup_bytes,rules100_ns,rules100_bytes,pages\n");
      for (int pass = -1; pass < 5; pass++) {
        for (int sample = 0; sample < (pass < 0 ? 20 : 50); sample++) {
          long before = allocations.getThreadAllocatedBytes(thread);
          long started = System.nanoTime();
          var program = new WasmProgram(guest, System::nanoTime);
          long startup = System.nanoTime() - started;
          long startupBytes = allocations.getThreadAllocatedBytes(thread) - before;
          before = allocations.getThreadAllocatedBytes(thread);
          started = System.nanoTime();
          for (int call = 0; call < 100; call++)
            if (program.invoke(call % program.rules().size(), host).instructions() <= 0)
              throw new IllegalStateException("rule executed no instructions");
          long rules = System.nanoTime() - started;
          long ruleBytes = allocations.getThreadAllocatedBytes(thread) - before;
          if (pass >= 0)
            writer.write(
                String.format(
                    Locale.ROOT,
                    "%d,%d,%d,%d,%d,%d,%d%n",
                    pass,
                    sample,
                    startup,
                    startupBytes,
                    rules,
                    ruleBytes,
                    program.memoryPages()));
        }
      }
    }
    System.out.println("Wasm raw samples: " + output);
  }
}
