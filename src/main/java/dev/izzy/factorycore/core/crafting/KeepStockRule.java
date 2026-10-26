package dev.izzy.factorycore.core.crafting;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKey;
import java.util.Objects;
import java.util.UUID;

/**
 * Persisted keep-stock rule: when available stock of {@code key} in the requester's CRAFT view is
 * below {@code low} and no job for the same principal, network, segment and key is in flight,
 * request {@code high - stock} units. A rule acts under its principal's current grants; one job per
 * target bounds outstanding work.
 */
public record KeepStockRule(
    UUID id,
    UUID principal,
    UUID network,
    UUID segment,
    NodeRegistry.Position origin,
    ResourceKey key,
    long low,
    long high) {
  public static final long MAX_TARGET = 1L << 20;

  public KeepStockRule {
    Objects.requireNonNull(id);
    Objects.requireNonNull(principal);
    Objects.requireNonNull(network);
    Objects.requireNonNull(segment);
    Objects.requireNonNull(origin);
    Objects.requireNonNull(key);
    if (low < 1 || high < low || high > MAX_TARGET)
      throw new IllegalArgumentException("Keep-stock thresholds need 1 <= low <= high <= 2^20");
  }

  public KeepStockRule withId(UUID replacement) {
    return new KeepStockRule(replacement, principal, network, segment, origin, key, low, high);
  }

  /**
   * True when {@code job} could fill this rule's target: same principal, network, segment and
   * resource. Such a job's output is already on its way, whichever rule or request started it.
   */
  public boolean targets(CraftingJob job) {
    return job.principal().equals(principal)
        && job.network().equals(network)
        && job.segment().equals(segment)
        && job.request().equals(key);
  }

  /** Units to request for {@code available} stock, or 0 when at or above {@code low}. */
  public long deficit(long available) {
    if (available < 0) throw new IllegalArgumentException("Negative stock");
    return available >= low ? 0 : high - available;
  }
}
