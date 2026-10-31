package dev.izzy.factorycore.core.program;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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

  /**
   * One entry per line, sorted: {@code item ID}, {@code fluid ID}, {@code tag ID} or {@code name
   * english name=ID}. Used for the generated vanilla-plus-mod file and the offline helper.
   */
  public String write() {
    var lines = new ArrayList<String>();
    for (var id : items) lines.add("item " + id);
    for (var id : fluids) lines.add("fluid " + id);
    for (var id : itemTags) lines.add("tag " + id);
    for (var name : names.entrySet()) lines.add("name " + name.getKey() + "=" + name.getValue());
    lines.sort(null);
    return String.join("\n", lines) + "\n";
  }

  /** Reads {@link #write()}'s format; blank lines and {@code #} comments are skipped. */
  public static Vocabulary read(String text) {
    Set<String> items = new HashSet<>(), fluids = new HashSet<>(), tags = new HashSet<>();
    Map<String, String> names = new HashMap<>();
    int number = 0;
    for (var line : List.of(text.split("\n"))) {
      number++;
      line = line.strip();
      if (line.isEmpty() || line.startsWith("#")) continue;
      int space = line.indexOf(' ');
      var value = space < 0 ? "" : line.substring(space + 1);
      switch (space < 0 ? line : line.substring(0, space)) {
        case "item" -> items.add(value);
        case "fluid" -> fluids.add(value);
        case "tag" -> tags.add(value);
        case "name" -> {
          int equals = value.lastIndexOf('=');
          if (equals <= 0) throw new IllegalArgumentException("Vocabulary line " + number);
          names.put(value.substring(0, equals), value.substring(equals + 1));
        }
        default -> throw new IllegalArgumentException("Vocabulary line " + number);
      }
    }
    return new Vocabulary(items, fluids, tags, names);
  }
}
