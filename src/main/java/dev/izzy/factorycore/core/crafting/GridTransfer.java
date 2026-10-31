package dev.izzy.factorycore.core.crafting;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Chooses one registry item ID per grid cell for a recipe-viewer transfer: the option the player
 * has most of (own inventory and terminal rows; Q112), the first listed on a tie, otherwise the
 * first option. Ghost cells move nothing, so the choice only steers which stock a later craft will
 * request.
 */
public final class GridTransfer {
  private GridTransfer() {}

  /** As {@link #choose(List, java.util.Map)}, counting each available option once. */
  public static List<String> choose(List<List<String>> options, Set<String> available) {
    var counts = new java.util.HashMap<String, Long>();
    for (var option : available) counts.put(option, 1L);
    return choose(options, counts);
  }

  /**
   * {@code options} has one (possibly empty) list per cell; empty lists yield "". {@code counts} is
   * how many of each ID the player has; missing IDs count 0.
   */
  public static List<String> choose(
      List<List<String>> options, java.util.Map<String, Long> counts) {
    if (options.size() > GridRequirement.CELLS)
      throw new IllegalArgumentException("Too many cells");
    var chosen = new ArrayList<String>(GridRequirement.CELLS);
    for (var cell : options) {
      String pick = cell.isEmpty() ? "" : cell.getFirst();
      long most = 0;
      for (var option : cell) {
        long count = counts.getOrDefault(option, 0L);
        if (count > most) {
          most = count;
          pick = option;
        }
      }
      chosen.add(pick);
    }
    while (chosen.size() < GridRequirement.CELLS) chosen.add("");
    return chosen;
  }
}
