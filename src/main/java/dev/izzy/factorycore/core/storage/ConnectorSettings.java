package dev.izzy.factorycore.core.storage;

import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import java.util.Objects;

/**
 * Immutable foreign-provider policy; counts and rates use the selected resource's units. {@code
 * insert} (B2, Storage mode only) limits what priority routes insert into the container; null
 * accepts everything. It never hides or blocks what the container already holds.
 */
public record ConnectorSettings(
    ResourceKind kind,
    Access access,
    int priority,
    int firstSlot,
    int slotCount,
    int rate,
    ResourceFilter insert) {
  public ConnectorSettings(
      ResourceKind kind, Access access, int priority, int firstSlot, int slotCount, int rate) {
    this(kind, access, priority, firstSlot, slotCount, rate, null);
  }

  public enum Access {
    READ_WRITE,
    READ_ONLY,
    WRITE_ONLY
  }

  public ConnectorSettings {
    Objects.requireNonNull(kind);
    Objects.requireNonNull(access);
    // Absolute ceiling; the installed ThroughputTier further bounds each physical port (ADR 0054).
    long maximum = ThroughputTier.maxQuantum(kind);
    if (priority < -1000
        || priority > 1000
        || firstSlot < 0
        || slotCount < 1
        || slotCount > 64
        || rate < 1
        || rate > maximum)
      throw new IllegalArgumentException("Connector settings exceed bounded limits");
    if (kind == ResourceKind.ENERGY && (firstSlot != 0 || slotCount != 64))
      throw new IllegalArgumentException("Energy has no indexed window");
    if (insert != null && (insert.kind() != kind || insert.mode() == ResourceFilter.Mode.ALL))
      throw new IllegalArgumentException("Insert filter must match the kind and select something");
  }

  /** The same settings with {@code filter} as the insert filter (null or ALL clears it). */
  public ConnectorSettings withInsert(ResourceFilter filter) {
    return new ConnectorSettings(
        kind,
        access,
        priority,
        firstSlot,
        slotCount,
        rate,
        filter == null || filter.mode() == ResourceFilter.Mode.ALL ? null : filter);
  }

  /** Rejects a rate above what the physical port's installed tier allows. */
  public boolean fits(ThroughputTier tier) {
    return rate <= tier.portRate(kind);
  }

  /** Legacy fluid defaults mean the whole capability, including paginated large catalogs. */
  public boolean wholeFluidHandler() {
    return kind == ResourceKind.FLUID && firstSlot == 0 && slotCount == 64;
  }

  public static ConnectorSettings defaults(ResourceKind kind) {
    return new ConnectorSettings(
        kind, Access.READ_WRITE, 0, 0, 64, kind == ResourceKind.ITEM ? 64 : 1000);
  }
}
