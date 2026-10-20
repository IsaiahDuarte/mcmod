package dev.izzy.factorycore.core.resource;

import dev.izzy.factorycore.core.network.NodeRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Shared server-thread, bidirectional per-pair throughput, distinct from endpoint operating FE. */
public final class WirelessBudget {
  public static final int MAX_LINKS = 32768, MAX_PATH = 64;
  public static final long ITEMS = 256, FLUID_MB = 4000, ENERGY_FE = 8000;

  /** Physical pair identity survives reload/re-pair to the same coordinates within a tick. */
  public record Link(NodeRegistry.Position first, NodeRegistry.Position second) {
    public Link {
      Objects.requireNonNull(first);
      Objects.requireNonNull(second);
      if (!first.dimension().equals(second.dimension()) || first.equals(second))
        throw new IllegalArgumentException("A wireless pair needs distinct same-dimension ends");
      if (Long.compareUnsigned(first.block(), second.block()) > 0) {
        var swap = first;
        first = second;
        second = swap;
      }
    }
  }

  /**
   * One crossed pair and its throughput multiplier (ADR 0054): 1 normally, or the Overdrive scale
   * when both endpoints carry it. Usage stays keyed by the physical pair, so an upgrade or
   * re-publication inside a tick never resets units already spent.
   */
  public record Hop(Link link, int scale) {
    public Hop {
      Objects.requireNonNull(link);
      if (scale < 1 || scale > 64) throw new IllegalArgumentException("Invalid link scale");
    }
  }

  private static final class Usage {
    long tick = Long.MIN_VALUE;
    final long[] used = new long[ResourceKind.values().length];
  }

  /** Reservation made before a handler call. An uncertain call conservatively keeps its offer. */
  public final class Permit {
    private final List<Usage> uses;
    private final long tick, amount;
    private final ResourceKind kind;
    private final OperationResult.Reason reason;
    private boolean finished;

    private Permit(
        List<Usage> uses,
        long tick,
        ResourceKind kind,
        long amount,
        OperationResult.Reason reason) {
      this.uses = List.copyOf(uses);
      this.tick = tick;
      this.kind = kind;
      this.amount = amount;
      this.reason = reason;
    }

    public long amount() {
      return amount;
    }

    public OperationResult.Reason reason() {
      return reason;
    }

    /** Release only known unaccepted units; calling twice or fabricating an amount is an error. */
    public void finish(long actual) {
      checkThread();
      if (finished || actual < 0 || actual > amount)
        throw new IllegalArgumentException("Invalid wireless permit completion");
      finished = true;
      for (var use : uses) if (use.tick == tick) use.used[kind.ordinal()] -= amount - actual;
    }
  }

  private final Thread thread = Thread.currentThread();
  private final LinkedHashMap<Link, Usage> usage = new LinkedHashMap<>(16, 0.75f, true);

  public static long limit(ResourceKind kind) {
    return switch (kind) {
      case ITEM -> ITEMS;
      case FLUID -> FLUID_MB;
      case ENERGY -> ENERGY_FE;
    };
  }

  public Permit denied(OperationResult.Reason reason) {
    checkThread();
    return new Permit(List.of(), 0, ResourceKind.ITEM, 0, reason);
  }

  private static List<Hop> base(List<Link> path) {
    if (path.size() > MAX_PATH) throw new IllegalArgumentException("Wireless path exceeds depth");
    var hops = new ArrayList<Hop>(path.size());
    for (var link : path) hops.add(new Hop(link, 1));
    return hops;
  }

  /** Deduplicated by physical pair; a pair published twice keeps its smaller scale. */
  private static java.util.Map<Link, Integer> hops(List<Hop> path) {
    if (path.size() > MAX_PATH) throw new IllegalArgumentException("Wireless path exceeds depth");
    var hops = new LinkedHashMap<Link, Integer>();
    for (var hop : path) hops.merge(hop.link(), hop.scale(), Math::min);
    return hops;
  }

  /** Advisory capacity; does not debit throughput or create cache entries. */
  public long available(List<Link> path, long tick, ResourceKind kind, long requested) {
    return availableHops(base(path), tick, kind, requested);
  }

  /** Advisory capacity with per-pair upgrade scales; never debits or creates cache entries. */
  public long availableHops(List<Hop> path, long tick, ResourceKind kind, long requested) {
    checkThread();
    OperationResult.requireQuantity(requested);
    long result = requested;
    for (var hop : hops(path).entrySet()) {
      var use = usage.get(hop.getKey());
      long spent = use != null && use.tick == tick ? use.used[kind.ordinal()] : 0;
      result = Math.min(result, Math.max(0, limit(kind) * hop.getValue() - spent));
    }
    return result;
  }

  /**
   * Atomically reserve the same units on every hop before mutation, bounded by the tightest hop.
   */
  public Permit claim(List<Link> path, long tick, ResourceKind kind, long requested) {
    return claimHops(base(path), tick, kind, requested);
  }

  /** {@link #claim} with per-pair upgrade scales. */
  public Permit claimHops(List<Hop> path, long tick, ResourceKind kind, long requested) {
    checkThread();
    var scaled = hops(path);
    var links = List.copyOf(scaled.keySet());
    long amount = availableHops(path, tick, kind, requested);
    var uses = new ArrayList<Usage>();
    for (var link : links) {
      var use = usage.get(link);
      if (use == null) {
        if (usage.size() == MAX_LINKS) {
          var oldest = usage.firstEntry();
          if (oldest.getValue().tick == tick) return denied(OperationResult.Reason.TECHNICAL_LIMIT);
          usage.remove(oldest.getKey());
        }
        use = new Usage();
        usage.put(link, use);
      }
      if (use.tick != tick) {
        java.util.Arrays.fill(use.used, 0);
        use.tick = tick;
      }
      uses.add(use);
    }
    for (var use : uses) use.used[kind.ordinal()] += amount;
    return new Permit(
        uses,
        tick,
        kind,
        amount,
        amount < requested ? OperationResult.Reason.RATE_LIMIT : OperationResult.Reason.OK);
  }

  private void checkThread() {
    if (Thread.currentThread() != thread)
      throw new IllegalStateException("Wireless accounting requires owner thread");
  }
}
