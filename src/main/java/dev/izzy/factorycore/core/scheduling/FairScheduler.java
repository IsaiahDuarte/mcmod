package dev.izzy.factorycore.core.scheduling;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Active-only bounded round-robin queue; each admitted task is one bounded host operation. */
public final class FairScheduler {
  public record Limits(
      int networks,
      int programsPerNetwork,
      int queuedPerProgram,
      int queuedTotal,
      int visitsPerTick,
      int tasksPerTick,
      int globalCredits,
      int networkCredits,
      int programCredits,
      long nanosPerTick) {
    public Limits {
      if (networks <= 0
          || programsPerNetwork <= 0
          || queuedPerProgram <= 0
          || queuedTotal <= 0
          || visitsPerTick <= 0
          || tasksPerTick <= 0
          || programCredits <= 0
          || networkCredits < programCredits
          || globalCredits < networkCredits
          || nanosPerTick <= 0) {
        throw new IllegalArgumentException("Invalid scheduling bounds");
      }
    }

    public static Limits defaults() {
      return new Limits(1024, 64, 256, 8192, 256, 64, 8192, 2048, 512, 5_000_000);
    }
  }

  public enum Admission {
    ACCEPTED,
    REPLACED,
    QUEUE_FULL,
    NETWORK_LIMIT,
    PROGRAM_LIMIT,
    COST_LIMIT
  }

  public record TickReport(
      int visits, int executed, int failed, int credits, int backlog, boolean timeLimited) {}

  public record Failure(UUID network, UUID program, String key, String diagnostic) {}

  private record Task(String key, int cost, Runnable action) {}

  private static final class Program {
    final UUID id;
    final LinkedHashMap<String, Task> tasks = new LinkedHashMap<>();
    long epoch = -1;
    int spent;
    boolean active;

    Program(UUID id) {
      this.id = id;
    }
  }

  private static final class Network {
    final UUID id;
    final Map<UUID, Program> programs = new HashMap<>();
    final ArrayDeque<Program> activePrograms = new ArrayDeque<>();
    long epoch = -1;
    int spent;
    boolean active;

    Network(UUID id) {
      this.id = id;
    }
  }

  private final Thread thread = Thread.currentThread();
  private final Limits limits;
  private final LongSupplier nanoClock;
  private final Consumer<Failure> failures;
  private final Map<UUID, Network> networks = new HashMap<>();
  private final ArrayDeque<Network> activeNetworks = new ArrayDeque<>();
  private long epoch;
  private int queued;
  private boolean ticking;

  public FairScheduler(Limits limits, LongSupplier nanoClock, Consumer<Failure> failures) {
    this.limits = Objects.requireNonNull(limits);
    this.nanoClock = Objects.requireNonNull(nanoClock);
    this.failures = Objects.requireNonNull(failures);
  }

  public Admission submit(UUID networkId, UUID programId, String key, int cost, Runnable action) {
    checkThread();
    Objects.requireNonNull(networkId);
    Objects.requireNonNull(programId);
    Objects.requireNonNull(key);
    Objects.requireNonNull(action);
    if (key.isEmpty() || key.length() > 128)
      throw new IllegalArgumentException("Invalid coalescing key");
    if (cost <= 0 || cost > limits.programCredits()) return Admission.COST_LIMIT;
    var network = networks.get(networkId);
    if (network == null && networks.size() >= limits.networks()) return Admission.NETWORK_LIMIT;
    var program = network == null ? null : network.programs.get(programId);
    if (program == null
        && network != null
        && network.programs.size() >= limits.programsPerNetwork()) {
      return Admission.PROGRAM_LIMIT;
    }
    boolean replacing = program != null && program.tasks.containsKey(key);
    if (!replacing
        && (queued >= limits.queuedTotal()
            || (program != null && program.tasks.size() >= limits.queuedPerProgram())))
      return Admission.QUEUE_FULL;
    if (network == null) {
      network = new Network(networkId);
      networks.put(networkId, network);
    }
    if (program == null) {
      program = new Program(programId);
      network.programs.put(programId, program);
    }
    program.tasks.put(key, new Task(key, cost, action));
    if (!replacing) queued++;
    if (!program.active) {
      program.active = true;
      network.activePrograms.addLast(program);
    }
    if (!network.active) {
      network.active = true;
      activeNetworks.addLast(network);
    }
    return replacing ? Admission.REPLACED : Admission.ACCEPTED;
  }

  public TickReport tick() {
    checkThread();
    if (ticking) throw new IllegalStateException("Scheduler cannot reenter a tick");
    ticking = true;
    epoch = Math.addExact(epoch, 1);
    long start = nanoClock.getAsLong();
    int visits = 0;
    int executed = 0;
    int failed = 0;
    int credits = 0;
    boolean timeLimited = false;
    try {
      while (!activeNetworks.isEmpty()
          && visits < limits.visitsPerTick()
          && executed < limits.tasksPerTick()
          && credits < limits.globalCredits()) {
        if (nanoClock.getAsLong() - start >= limits.nanosPerTick()) {
          timeLimited = true;
          break;
        }
        var network = activeNetworks.removeFirst();
        network.active = false;
        var program = network.activePrograms.removeFirst();
        program.active = false;
        if (network.epoch != epoch) {
          network.epoch = epoch;
          network.spent = 0;
        }
        if (program.epoch != epoch) {
          program.epoch = epoch;
          program.spent = 0;
        }
        visits++;
        var iterator = program.tasks.entrySet().iterator();
        var task = iterator.next().getValue();
        try {
          if (task.cost() <= limits.programCredits() - program.spent
              && task.cost() <= limits.networkCredits() - network.spent
              && task.cost() <= limits.globalCredits() - credits) {
            iterator.remove();
            queued--;
            executed++;
            credits += task.cost();
            program.spent += task.cost();
            network.spent += task.cost();
            try {
              task.action().run();
            } catch (RuntimeException error) {
              failed++;
              failures.accept(
                  new Failure(
                      network.id,
                      program.id,
                      task.key(),
                      error.getClass().getSimpleName() + ": " + error.getMessage()));
            }
          }
        } finally {
          if (!program.tasks.isEmpty() && !program.active) {
            program.active = true;
            network.activePrograms.addLast(program);
          }
          if (!network.activePrograms.isEmpty() && !network.active) {
            network.active = true;
            activeNetworks.addLast(network);
          }
        }
      }
    } finally {
      ticking = false;
    }
    return new TickReport(visits, executed, failed, credits, queued, timeLimited);
  }

  /** Removes undispatched work on revocation, pause, replacement or device unload. */
  public int cancelProgram(UUID networkId, UUID programId) {
    checkThread();
    if (ticking)
      throw new IllegalStateException("Cancel at the tick boundary; active tasks own cleanup");
    var network = networks.get(networkId);
    if (network == null) return 0;
    var program = network.programs.remove(programId);
    if (program == null) return 0;
    int removed = program.tasks.size();
    queued -= removed;
    network.activePrograms.remove(program);
    if (network.activePrograms.isEmpty()) {
      activeNetworks.remove(network);
      network.active = false;
    }
    if (network.programs.isEmpty()) networks.remove(networkId);
    return removed;
  }

  public int backlog() {
    checkThread();
    return queued;
  }

  private void checkThread() {
    if (Thread.currentThread() != thread)
      throw new IllegalStateException("Scheduler requires its owning thread");
  }
}
