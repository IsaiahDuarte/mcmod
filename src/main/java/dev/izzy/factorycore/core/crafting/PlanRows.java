package dev.izzy.factorycore.core.crafting;

import dev.izzy.factorycore.core.resource.ResourceKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.ToLongFunction;

/**
 * A bounded per-resource summary of a {@link CraftingPlanner.Plan} for the terminal preview (A2):
 * how much of each resource comes from stock, how much the plan crafts and how much is missing.
 * Missing rows come first, then crafted rows in step order, then stock-only rows. At most {@code
 * limit} rows are kept; {@link Summary#more} counts the rest so the screen can say "and N more".
 */
public final class PlanRows {
  /** One resource of the plan, in units (items or mB). */
  public record Row(ResourceKey key, long stored, long crafted, long missing) {
    public Row {
      if (stored < 0 || crafted < 0 || missing < 0)
        throw new IllegalArgumentException("Negative plan row");
    }
  }

  public record Summary(List<Row> rows, int more) {
    public Summary {
      rows = List.copyOf(rows);
      if (more < 0) throw new IllegalArgumentException("Negative remainder");
    }
  }

  private PlanRows() {}

  /**
   * Summarizes {@code plan}. {@code unitsPerCraft} gives each step pattern's output per craft; an
   * unknown pattern (it changed since planning) counts one unit per craft rather than guessing.
   */
  public static Summary of(
      CraftingPlanner.Plan plan, ToLongFunction<UUID> unitsPerCraft, int limit) {
    if (limit < 0) throw new IllegalArgumentException("Negative row limit");
    var crafted = new LinkedHashMap<ResourceKey, Long>();
    for (var step : plan.steps()) {
      long units = Math.max(1, unitsPerCraft.applyAsLong(step.pattern()));
      crafted.merge(step.output(), Math.multiplyExact(units, step.crafts()), Math::addExact);
    }
    var keys = new LinkedHashMap<ResourceKey, Integer>();
    for (var key : plan.missing().keySet()) keys.putIfAbsent(key, 0);
    for (var key : crafted.keySet()) keys.putIfAbsent(key, 1);
    for (var key : plan.consumed().keySet()) keys.putIfAbsent(key, 2);
    var rows = new ArrayList<Row>(keys.size());
    for (var key : keys.keySet())
      rows.add(
          new Row(
              key,
              plan.consumed().getOrDefault(key, 0L),
              crafted.getOrDefault(key, 0L),
              plan.missing().getOrDefault(key, 0L)));
    // Stable: groups keep their insertion (missing, step, consumption) order.
    rows.sort(Comparator.comparingInt(row -> keys.get(row.key())));
    int kept = Math.min(limit, rows.size());
    return new Summary(rows.subList(0, kept), rows.size() - kept);
  }

  /** Cycle explanations: each cycle's keys in path order, at most {@code limit} cycles. */
  public static List<List<ResourceKey>> cycles(CraftingPlanner.Plan plan, int limit) {
    return plan.cycles().subList(0, Math.min(limit, plan.cycles().size()));
  }
}
