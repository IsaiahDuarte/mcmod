package dev.izzy.factorycore.core.wasm;

import dev.izzy.factorycore.core.program.ProgramEvaluator;
import dev.izzy.factorycore.core.resource.ResourceFilter;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Ergonomics plan Q59: what share of a full-budget rule the per-instruction deadline check costs. A
 * runaway guest loops until {@link WasmProgram#RULE_INSTRUCTIONS} are spent, once with the real
 * clock and once with a constant clock that is never read as late. The difference is the clock's
 * cost. Desktop timing is evidence, not a CI assertion. Run with {@code ./gradlew
 * wasmDeadlineBenchmark}.
 */
public final class WasmDeadlineBenchmark {
  private static final int WARMUP = 300, SAMPLES = 300, PASSES = 5;

  private WasmDeadlineBenchmark() {}

  private static final ProgramEvaluator.Host HOST =
      new ProgramEvaluator.Host() {
        @Override
        public List<UUID> machines(String label) {
          return List.of();
        }

        @Override
        public long stock(ResourceFilter filter) {
          return 0;
        }
      };

  /** Nanoseconds for one full-budget rule run; the instance is new, so only the run is timed. */
  private static long run(byte[] guest, LongSupplier clock) {
    var program = new WasmProgram(guest, clock);
    long started = System.nanoTime();
    try {
      program.invoke(0, HOST);
      throw new IllegalStateException("The runaway guest returned");
    } catch (ProgramEvaluator.Trap expected) {
      if (!expected.getMessage().contains("instruction budget"))
        throw new IllegalStateException("Unexpected trap: " + expected.getMessage());
    }
    return System.nanoTime() - started;
  }

  private static long median(long[] values) {
    var sorted = values.clone();
    Arrays.sort(sorted);
    return sorted[sorted.length / 2];
  }

  public static void main(String[] arguments) throws Exception {
    // loop { br 0 }: spends the whole instruction budget.
    byte[] guest =
        WasmProgramTest.module("register_rule", new byte[] {0x03, 0x40, 0x0c, 0, 0x0b, 0x0b});
    LongSupplier real = System::nanoTime;
    LongSupplier free = () -> 0L;
    for (int i = 0; i < WARMUP; i++) {
      run(guest, real);
      run(guest, free);
    }
    long blackhole = 0;
    long clockStarted = System.nanoTime();
    for (int i = 0; i < 10_000_000; i++) blackhole += System.nanoTime();
    double clockNanos = (System.nanoTime() - clockStarted) / 10_000_000.0;
    Path output = Path.of("build/benchmarks/wasm-deadline.csv");
    Files.createDirectories(output.getParent());
    try (BufferedWriter writer = Files.newBufferedWriter(output)) {
      writer.write("pass,real_median_ns,free_median_ns,clock_share_percent\n");
      for (int pass = 0; pass < PASSES; pass++) {
        var withClock = new long[SAMPLES];
        var withoutClock = new long[SAMPLES];
        for (int sample = 0; sample < SAMPLES; sample++) {
          withClock[sample] = run(guest, real);
          withoutClock[sample] = run(guest, free);
        }
        long realMedian = median(withClock), freeMedian = median(withoutClock);
        double share = 100.0 * (realMedian - freeMedian) / realMedian;
        writer.write(
            String.format(Locale.ROOT, "%d,%d,%d,%.1f%n", pass, realMedian, freeMedian, share));
        System.out.printf(
            Locale.ROOT,
            "pass %d: real clock %.3f ms, free clock %.3f ms, clock share %.1f%%%n",
            pass,
            realMedian / 1e6,
            freeMedian / 1e6,
            share);
      }
    }
    System.out.printf(
        Locale.ROOT,
        "System.nanoTime: %.1f ns per call (%d)%nRaw: %s%n",
        clockNanos,
        blackhole & 1,
        output);
  }
}
