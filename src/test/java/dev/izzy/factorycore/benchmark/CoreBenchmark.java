package dev.izzy.factorycore.benchmark;

import dev.izzy.factorycore.core.resource.LedgerPort;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ResourceLedger;
import dev.izzy.factorycore.core.resource.ResourcePort;
import dev.izzy.factorycore.core.resource.StagedTransfer;
import dev.izzy.factorycore.core.scheduling.FairScheduler;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Version-one portable scheduler/accounting benchmark; not Minecraft server-tick evidence. */
public final class CoreBenchmark {
  private record Scenario(
      String name,
      int networks,
      int programs,
      int admissions,
      int cost,
      int keys,
      boolean partial) {}

  private static final int TICKS = 2000;

  private CoreBenchmark() {}

  public static void main(String[] args) throws IOException {
    Path output = Path.of(args.length == 0 ? "build/benchmarks/core-v1.csv" : args[0]);
    Files.createDirectories(output.toAbsolutePath().getParent());
    var rows = new ArrayList<String>();
    rows.add(
        "scenario,pass,tick,nanos,visits,executed,credits,backlog,rejected,units,heapUsed,allocatedBytes,maxLatencyTicks");
    System.out.println(
        "Runtime="
            + System.getProperty("java.runtime.version")
            + " OS="
            + System.getProperty("os.name")
            + " arch="
            + System.getProperty("os.arch")
            + " CPUs="
            + Runtime.getRuntime().availableProcessors()
            + " maxHeap="
            + Runtime.getRuntime().maxMemory());
    var scenarios =
        List.of(
            new Scenario("small", 4, 8, 16, 32, 64, false),
            new Scenario("large", 64, 1024, 256, 32, 4096, false),
            new Scenario("overload", 128, 4096, 1024, 64, 4096, true),
            new Scenario("idle", 1024, 65_536, 0, 1, 1, false));
    for (var scenario : scenarios) {
      for (int pass = -3; pass < 5; pass++) run(scenario, pass, rows);
    }
    Files.write(output, rows);
    System.out.println("Raw samples=" + output);
  }

  private static void run(Scenario scenario, int pass, List<String> rows) {
    var scheduler =
        new FairScheduler(
            FairScheduler.Limits.defaults(),
            System::nanoTime,
            failure -> {
              throw new IllegalStateException(failure.diagnostic());
            });
    var keys = new ArrayList<ResourceKey>();
    for (int index = 0; index < scenario.keys(); index++) {
      keys.add(
          new ResourceKey(
              ResourceKind.ITEM,
              "minecraft:iron_ingot",
              ByteBuffer.allocate(8).putLong(index).array()));
    }
    var first = new ResourceLedger(new UUID(0, 1), ResourceKind.ITEM, 2_000_000, false, 8192, 64);
    var second = new ResourceLedger(new UUID(0, 2), ResourceKind.ITEM, 2_000_000, false, 8192, 64);
    for (var key : keys) {
      first.insert(key, 128);
      second.insert(key, 128);
    }
    var giant = new ResourceLedger(new UUID(0, 3), ResourceKind.ITEM, 0, true, 8192, 64);
    giant.insert(keys.getFirst(), Long.MAX_VALUE / 2);
    var networks = new UUID[scenario.networks()];
    var programs = new UUID[scenario.programs()];
    for (int index = 0; index < networks.length; index++) networks[index] = new UUID(1, index);
    for (int index = 0; index < programs.length; index++) programs[index] = new UUID(2, index);
    if (scenario.admissions() == 0) {
      for (int index = 0; index < programs.length; index++) {
        scheduler.submit(
            networks[index % networks.length], programs[index], "initial", 1, () -> {});
        if (index % 64 == 63) scheduler.tick();
      }
      while (scheduler.backlog() > 0) scheduler.tick();
    }
    long[] samples = new long[TICKS];
    long[] units = {0};
    int[] currentTick = {0};
    int[] maxLatency = {0};
    var allocationBean = ManagementFactory.getThreadMXBean();
    if (!(allocationBean instanceof com.sun.management.ThreadMXBean allocation)
        || !allocation.isThreadAllocatedMemorySupported()) {
      throw new IllegalStateException("Reference allocation measurement is unavailable");
    }
    allocation.setThreadAllocatedMemoryEnabled(true);
    long threadId = Thread.currentThread().threadId();
    long rejected = 0;
    long executed = 0;
    int maxBacklog = 0;
    for (int tick = 0; tick < TICKS; tick++) {
      currentTick[0] = tick;
      maxLatency[0] = 0;
      long beforeAllocation = allocation.getThreadAllocatedBytes(threadId);
      long start = System.nanoTime();
      long beforeUnits = units[0];
      int tickRejected = 0;
      for (int admission = 0; admission < scenario.admissions(); admission++) {
        int sequence = tick * scenario.admissions() + admission;
        var program = programs[sequence % programs.length];
        var network = networks[sequence % networks.length];
        boolean giantOperation = sequence % 16 == 0;
        var key = keys.get(giantOperation ? 0 : sequence % keys.size());
        int enqueuedTick = tick;
        var result =
            scheduler.submit(
                network,
                program,
                "move-" + (sequence / programs.length % 256),
                scenario.cost(),
                () -> {
                  maxLatency[0] = Math.max(maxLatency[0], currentTick[0] - enqueuedTick);
                  var source =
                      giantOperation
                          ? giant
                          : first.stock(key).available() >= second.stock(key).available()
                              ? first
                              : second;
                  var destination = source == first ? second : first;
                  ResourcePort target = new LedgerPort(destination, null);
                  if (scenario.partial()) target = partial(target);
                  var move =
                      StagedTransfer.start(
                          program, key, new LedgerPort(source, null), target, 64, 64, () -> true);
                  units[0] += move.delivered();
                  if (move.staged() > 0) move.cancel();
                  if (move.staged() != 0 || move.state() == StagedTransfer.State.QUARANTINED)
                    throw new IllegalStateException("Unowned benchmark staging");
                });
        if (result != FairScheduler.Admission.ACCEPTED
            && result != FairScheduler.Admission.REPLACED) tickRejected++;
      }
      var report = scheduler.tick();
      samples[tick] = System.nanoTime() - start;
      long allocated = allocation.getThreadAllocatedBytes(threadId) - beforeAllocation;
      rejected += tickRejected;
      executed += report.executed();
      maxBacklog = Math.max(maxBacklog, report.backlog());
      if (report.visits() > 256
          || report.executed() > 64
          || report.credits() > 8192
          || report.backlog() > 8192) {
        throw new IllegalStateException("Scheduler exceeded selected bounds");
      }
      if (pass >= 0) {
        rows.add(
            String.format(
                Locale.ROOT,
                "%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d",
                scenario.name(),
                pass,
                tick,
                samples[tick],
                report.visits(),
                report.executed(),
                report.credits(),
                report.backlog(),
                tickRejected,
                units[0] - beforeUnits,
                Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
                allocated,
                maxLatency[0]));
      }
    }
    if (pass >= 0) {
      Arrays.sort(samples);
      System.out.printf(
          Locale.ROOT,
          "%s pass=%d median=%.3fms p95=%.3fms p99=%.3fms max=%.3fms executed=%d rejected=%d backlogMax=%d units=%d%n",
          scenario.name(),
          pass,
          samples[TICKS / 2] / 1e6,
          samples[(int) (TICKS * .95)] / 1e6,
          samples[(int) (TICKS * .99)] / 1e6,
          samples[TICKS - 1] / 1e6,
          executed,
          rejected,
          maxBacklog,
          units[0]);
    }
  }

  private static ResourcePort partial(ResourcePort delegate) {
    return new ResourcePort() {
      @Override
      public UUID backingId() {
        return delegate.backingId();
      }

      @Override
      public long simulateInsert(ResourceKey key, long amount) {
        return delegate.simulateInsert(key, amount);
      }

      @Override
      public long extract(ResourceKey key, long amount) {
        return delegate.extract(key, amount);
      }

      @Override
      public long insert(ResourceKey key, long amount) {
        return delegate.insert(key, Math.min(amount, 16));
      }
    };
  }
}
