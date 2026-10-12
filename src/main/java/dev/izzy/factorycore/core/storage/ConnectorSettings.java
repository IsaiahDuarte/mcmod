package dev.izzy.factorycore.core.storage;

import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.Objects;

/** Immutable foreign-provider policy; counts and rates use the selected resource's units. */
public record ConnectorSettings(
    ResourceKind kind, Access access, int priority, int firstSlot, int slotCount, int rate) {
  public enum Access {
    READ_WRITE,
    READ_ONLY,
    WRITE_ONLY
  }

  public ConnectorSettings {
    Objects.requireNonNull(kind);
    Objects.requireNonNull(access);
    int maximum = kind == ResourceKind.ITEM ? 128 : 1000;
    if (priority < -1000
        || priority > 1000
        || firstSlot < 0
        || slotCount < 1
        || slotCount > 64
        || rate < 1
        || rate > maximum)
      throw new IllegalArgumentException("Connector settings exceed bounded limits");
    if (kind != ResourceKind.ITEM && (firstSlot != 0 || slotCount != 64))
      throw new IllegalArgumentException("Slot windows are supported only for items");
  }

  public static ConnectorSettings defaults(ResourceKind kind) {
    return new ConnectorSettings(
        kind, Access.READ_WRITE, 0, 0, 64, kind == ResourceKind.ITEM ? 64 : 1000);
  }
}
