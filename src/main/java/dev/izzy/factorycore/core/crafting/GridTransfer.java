package dev.izzy.factorycore.core.crafting;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Chooses one registry item ID per grid cell for a recipe-viewer transfer: the first option the
 * player can currently see (own inventory or terminal rows), otherwise the first option. Ghost
 * cells move nothing, so the choice only steers which stock a later craft will request.
 */
public final class GridTransfer {
  private GridTransfer() {}

  /** {@code options} has one (possibly empty) list per cell; empty lists yield "". */
  public static List<String> choose(List<List<String>> options, Set<String> available) {
    if (options.size() > GridRequirement.CELLS)
      throw new IllegalArgumentException("Too many cells");
    var chosen = new ArrayList<String>(GridRequirement.CELLS);
    for (var cell : options) {
      String pick = cell.isEmpty() ? "" : cell.getFirst();
      for (var option : cell)
        if (available.contains(option)) {
          pick = option;
          break;
        }
      chosen.add(pick);
    }
    while (chosen.size() < GridRequirement.CELLS) chosen.add("");
    return chosen;
  }
}
