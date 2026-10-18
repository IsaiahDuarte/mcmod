package dev.izzy.factorycore.core.crafting;

import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Exact per-craft ingredient units of a 3-by-3 ghost grid. Ghost cells are selections, never owned
 * items: computing a requirement reserves or moves nothing.
 */
public final class GridRequirement {
  public static final int CELLS = 9;
  public static final int MAX_CRAFTS = 64;

  /** First missing ingredient in stable cell order, in item units for the requested crafts. */
  public record Deficit(ResourceKey key, long missing) {
    public Deficit {
      Objects.requireNonNull(key);
      if (missing <= 0) throw new IllegalArgumentException("Deficit must be positive");
    }
  }

  private final List<ResourceKey> cells;
  private final Map<ResourceKey, Long> perCraft;

  /** Accepts exactly nine cells; null marks an empty cell. Only item keys are valid. */
  public GridRequirement(List<ResourceKey> cells) {
    if (cells.size() != CELLS) throw new IllegalArgumentException("Grid needs nine cells");
    var copy = new ArrayList<ResourceKey>(cells);
    var counts = new LinkedHashMap<ResourceKey, Long>();
    for (var key : copy) {
      if (key == null) continue;
      if (key.kind() != ResourceKind.ITEM)
        throw new IllegalArgumentException("Grid accepts item keys only");
      counts.merge(key, 1L, Long::sum);
    }
    this.cells = Collections.unmodifiableList(copy);
    perCraft = Collections.unmodifiableMap(counts);
  }

  public List<ResourceKey> cells() {
    return cells;
  }

  public boolean empty() {
    return perCraft.isEmpty();
  }

  /** Distinct keys in first-cell order with units consumed by one craft. */
  public Map<ResourceKey, Long> perCraft() {
    return perCraft;
  }

  /**
   * Returns the first key whose owned units cannot cover {@code crafts}, or null when all are
   * covered. {@code owned} values are exact counts; missing keys count as zero.
   */
  public Deficit deficit(int crafts, Map<ResourceKey, Long> owned) {
    if (crafts < 1 || crafts > MAX_CRAFTS)
      throw new IllegalArgumentException("Crafts must be 1.." + MAX_CRAFTS);
    for (var entry : perCraft.entrySet()) {
      long needed = Math.multiplyExact(entry.getValue(), crafts);
      long have = owned.getOrDefault(entry.getKey(), 0L);
      if (have < 0) throw new IllegalArgumentException("Negative owned count");
      if (have < needed) return new Deficit(entry.getKey(), needed - have);
    }
    return null;
  }
}
