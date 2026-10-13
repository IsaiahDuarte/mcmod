package dev.izzy.factorycore.core.storage;

import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.Objects;

/**
 * Canonical item selection and shared per-backing network I/O limit, independently per direction.
 */
public record BufferPolicy(ResourceFilter filter, int rate) {
  public static final BufferPolicy DEFAULT =
      new BufferPolicy(ResourceFilter.all(ResourceKind.ITEM), 64);

  public BufferPolicy {
    Objects.requireNonNull(filter);
    if (filter.kind() != ResourceKind.ITEM || rate < 1 || rate > 64)
      throw new IllegalArgumentException("Buffer policy requires items and 1–64 items/tick");
  }

  /**
   * Legacy default policy fits the existing owner charge; extensions share the same global budget.
   */
  public long bytes() {
    return equals(DEFAULT) ? 0 : 128 + filter.bytes();
  }
}
