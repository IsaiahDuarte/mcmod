package dev.izzy.factorycore.wasm;

import java.io.BufferedWriter;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Manual feasibility measurements; desktop timing is evidence, not a CI latency assertion. */
public final class WasmBenchmark {
  private WasmBenchmark() {}

  public static void main(String[] arguments) throws Exception {
    byte[] guest = WasmProbeTest.guest();
    var allocations = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    if (!allocations.isThreadAllocatedMemorySupported()) {
      throw new IllegalStateException("allocation measurement unavailable");
    }
    allocations.setThreadAllocatedMemoryEnabled(true);
    long thread = Thread.currentThread().threadId();
    Path output = Path.of("build/benchmarks/wasm-v1.csv");
    Files.createDirectories(output.getParent());
    try (BufferedWriter writer = Files.newBufferedWriter(output)) {
      writer.write(
          "pass,sample,startup_ns,startup_bytes,echo100_ns,echo100_bytes,loop_ns,pages,heap_bytes\n");
      for (int pass = -1; pass < 5; pass++) {
        for (int sample = 0; sample < (pass < 0 ? 20 : 50); sample++) {
          long allocatedBefore = allocations.getThreadAllocatedBytes(thread);
          long started = System.nanoTime();
          var runtime = new ProbeRuntime(guest, System::nanoTime);
          long startup = System.nanoTime() - started;
          long startupBytes = allocations.getThreadAllocatedBytes(thread) - allocatedBefore;
          allocatedBefore = allocations.getThreadAllocatedBytes(thread);
          started = System.nanoTime();
          for (int call = 0; call < 100; call++) {
            long quantity = call % 2 == 0 ? Long.MAX_VALUE : 9_007_199_254_740_993L;
            var result = runtime.invoke("probe_echo", quantity);
            if (result.values()[0] != quantity || result.committed().getFirst() != quantity) {
              throw new IllegalStateException("round-trip changed quantity");
            }
          }
          long echoes = System.nanoTime() - started;
          long echoBytes = allocations.getThreadAllocatedBytes(thread) - allocatedBefore;
          var runaway = new ProbeRuntime(guest, System::nanoTime);
          started = System.nanoTime();
          try {
            runaway.invoke("probe_loop");
            throw new IllegalStateException("runaway returned without interruption");
          } catch (ProbeRuntime.LimitException limit) {
            if (!"instructions".equals(limit.getMessage())) {
              throw limit;
            }
          }
          long loop = System.nanoTime() - started;
          if (pass >= 0 || sample == 0) {
            var memory = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            writer.write(
                String.format(
                    Locale.ROOT,
                    "%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                    pass,
                    sample,
                    startup,
                    startupBytes,
                    echoes,
                    echoBytes,
                    loop,
                    runtime.memoryPages(),
                    memory.getUsed()));
          }
        }
      }
    }
    System.out.println("Wasm raw samples: " + output);
  }
}
