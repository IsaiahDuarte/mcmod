package dev.izzy.factorycore.core.program;

import java.util.Map;
import java.util.Set;

/**
 * The names a sentence program may use for items, fluids and tags (ADR 0068). The game builds one
 * from its registries and English names; tests and the offline helper load a fixed one. {@link
 * #NONE} knows nothing, so it takes every phrase literally as a {@code minecraft:} ID path.
 *
 * @param items every item registry ID, such as {@code minecraft:iron_ingot}
 * @param fluids every fluid registry ID
 * @param itemTags every item tag ID, such as {@code c:ingots}
 * @param names lower-case English item names that differ from their ID paths, to their IDs
 */
public record Vocabulary(
    Set<String> items, Set<String> fluids, Set<String> itemTags, Map<String, String> names) {
  public static final Vocabulary NONE = new Vocabulary(Set.of(), Set.of(), Set.of(), Map.of());

  public Vocabulary {
    items = Set.copyOf(items);
    fluids = Set.copyOf(fluids);
    itemTags = Set.copyOf(itemTags);
    names = Map.copyOf(names);
  }

  boolean empty() {
    return items.isEmpty() && fluids.isEmpty();
  }
}
