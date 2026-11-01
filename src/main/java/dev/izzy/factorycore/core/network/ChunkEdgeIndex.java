package dev.izzy.factorycore.core.network;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which regions a chunk load or unload can affect (ADR 0011, narrowed by PD7). A region is affected
 * by a chunk event when it has a position in that chunk, or a position on the edge of a horizontal
 * neighbour that faces that chunk: only those positions can connect across the shared border.
 * Counts are kept per chunk and per chunk edge, so a lookup visits at most five small maps and
 * never a region's positions.
 */
public final class ChunkEdgeIndex<R> {
  private static final int WHOLE = 0, WEST = 1, EAST = 2, NORTH = 3, SOUTH = 4;

  private record Key(String dimension, int x, int z, int part) {}

  private final Map<Key, Map<R, Integer>> counts = new HashMap<>();

  /** Moves one position from {@code previous} to {@code next}; either may be null. */
  public void move(String dimension, int blockX, int blockZ, R previous, R next) {
    if (previous == next) return;
    int x = blockX >> 4, z = blockZ >> 4, localX = blockX & 15, localZ = blockZ & 15;
    update(new Key(dimension, x, z, WHOLE), previous, next);
    if (localX == 0) update(new Key(dimension, x, z, WEST), previous, next);
    if (localX == 15) update(new Key(dimension, x, z, EAST), previous, next);
    if (localZ == 0) update(new Key(dimension, x, z, NORTH), previous, next);
    if (localZ == 15) update(new Key(dimension, x, z, SOUTH), previous, next);
  }

  private void update(Key key, R previous, R next) {
    var index = counts.computeIfAbsent(key, k -> new HashMap<>());
    if (previous != null) {
      int count = index.getOrDefault(previous, 0) - 1;
      if (count <= 0) index.remove(previous);
      else index.put(previous, count);
    }
    if (next != null) index.merge(next, 1, Integer::sum);
    if (index.isEmpty()) counts.remove(key);
  }

  /** The regions a load or unload of chunk ({@code chunkX}, {@code chunkZ}) can affect. */
  public Set<R> affected(String dimension, int chunkX, int chunkZ) {
    var found = new LinkedHashSet<R>();
    add(found, new Key(dimension, chunkX, chunkZ, WHOLE));
    add(found, new Key(dimension, chunkX - 1, chunkZ, EAST));
    add(found, new Key(dimension, chunkX + 1, chunkZ, WEST));
    add(found, new Key(dimension, chunkX, chunkZ - 1, SOUTH));
    add(found, new Key(dimension, chunkX, chunkZ + 1, NORTH));
    return found;
  }

  private void add(Set<R> found, Key key) {
    var index = counts.get(key);
    if (index != null) found.addAll(index.keySet());
  }
}
