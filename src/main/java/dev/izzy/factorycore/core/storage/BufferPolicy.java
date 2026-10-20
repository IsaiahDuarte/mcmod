package dev.izzy.factorycore.core.storage;

import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import java.util.Objects;

/**
 * Canonical item selection and shared per-backing network I/O limit, independently per direction.
 */
public record BufferPolicy(ResourceFilter filter, int rate, int target) {
  public static final BufferPolicy DEFAULT =
      new BufferPolicy(ResourceFilter.all(ResourceKind.ITEM), 64);

  /** Largest stock goal: the whole nine-slot tray of 64-item stacks. */
  public static final int MAX_TARGET = 576;

  public BufferPolicy(ResourceFilter filter, int rate) {
    this(filter, rate, 64);
  }

  public BufferPolicy {
    Objects.requireNonNull(filter);
    if (filter.kind() != ResourceKind.ITEM
        || rate < 1
        || rate > ThroughputTier.ENDGAME.bufferRate()
        || target < 0
        || target > MAX_TARGET)
      throw new IllegalArgumentException("Buffer policy requires items, rate 1–576 and goal 0–576");
  }

  /** The physical buffer's installed tier bounds the rate further (ADR 0054). */
  public boolean fits(ThroughputTier tier) {
    return rate <= tier.bufferRate();
  }

  /**
   * Legacy default policy fits the existing owner charge; extensions share the same global budget.
   */
  public long bytes() {
    return equals(DEFAULT) ? 0 : 128 + filter.bytes();
  }
}
