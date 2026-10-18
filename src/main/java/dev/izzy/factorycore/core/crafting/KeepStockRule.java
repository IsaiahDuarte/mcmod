package dev.izzy.factorycore.core.crafting;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKey;
import java.util.Objects;
import java.util.UUID;

/**
 * Persisted keep-stock rule: when available stock of {@code key} in the requester's CRAFT view is
 * below {@code low} and the rule has no job in flight, request {@code high - stock} units. A rule
 * acts under its principal's current grants; one job per rule bounds outstanding work.
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

  /** Units to request for {@code available} stock, or 0 when at or above {@code low}. */
  public long deficit(long available) {
    if (available < 0) throw new IllegalArgumentException("Negative stock");
    return available >= low ? 0 : high - available;
  }
}
