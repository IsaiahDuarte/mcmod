package dev.izzy.factorycore.core.storage;

import dev.izzy.factorycore.core.resource.ResourceKind;

/** Balance version one. Capacity counts resource units; metadata quotas remain independent. */
public enum CellTier {
  STARTER_ITEM(ResourceKind.ITEM, 0, 8192, false),
  EXPANDED_ITEM(ResourceKind.ITEM, 1, 65536, false),
  ADVANCED_ITEM(ResourceKind.ITEM, 2, 1048576, false),
  INFINITE_ITEM(ResourceKind.ITEM, 3, Long.MAX_VALUE, true),
  STARTER_FLUID(ResourceKind.FLUID, 0, 16000, false),
  EXPANDED_FLUID(ResourceKind.FLUID, 1, 256000, false),
  ADVANCED_FLUID(ResourceKind.FLUID, 2, 4096000, false);

  private final ResourceKind kind;
  private final int level;
  private final long capacity;
  private final boolean infinite;

  CellTier(ResourceKind kind, int level, long capacity, boolean infinite) {
    this.kind = kind;
    this.level = level;
    this.capacity = capacity;
    this.infinite = infinite;
  }

  public ResourceKind kind() {
    return kind;
  }

  public int level() {
    return level;
  }

  public long capacity() {
    return capacity;
  }

  public boolean infinite() {
    return infinite;
  }

  public CellTier upgrade(int targetLevel) {
    if (targetLevel != level + 1) throw new IllegalArgumentException("Apply upgrades sequentially");
    for (var tier : values()) {
      if (tier.kind == kind && tier.level == targetLevel) return tier;
    }
    throw new IllegalArgumentException("Unsupported cell tier");
  }
}
