package dev.izzy.factorycore.core.crafting;

import dev.izzy.factorycore.core.resource.ResourceKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Q65: a {@link CraftingPlanner.Plan} as a tree for the terminal's Plan view. The root is the
 * request; a crafted resource's children are its step's pattern inputs, scaled by that step's
 * crafts. Nodes come in depth-first order with their depth, at most {@code limit} of them and at
 * most {@link #MAX_DEPTH} deep; a resource already open on the path is shown once more as a leaf (a
 * loop), never expanded again.
 */
public final class PlanTree {
  public static final int MAX_DEPTH = 12;

  public enum Kind {
    /** A plan step makes it. */
    CRAFTED,
    /** Taken from storage. */
    STORED,
    /** Neither stored nor craftable: the plan is short of it. */
    MISSING,
    /** Already open higher on this path (a recipe loop). */
    LOOP
  }

  /** One node: {@code amount} units of {@code key} at {@code depth} (0 is the request). */
  public record Node(ResourceKey key, int depth, long amount, Kind kind) {}

  public record Tree(List<Node> nodes, boolean truncated) {
    public Tree {
      nodes = List.copyOf(nodes);
    }
  }

  private PlanTree() {}

  /**
   * Builds the tree. {@code inputs} gives a pattern's inputs per craft (null for a pattern that
   * changed since planning, which is then drawn as a leaf).
   */
  public static Tree of(
      CraftingPlanner.Plan plan, Function<UUID, Map<ResourceKey, Long>> inputs, int limit) {
    if (limit < 1) throw new IllegalArgumentException("Tree limit");
    var steps = new HashMap<ResourceKey, CraftingPlanner.Step>();
    for (var step : plan.steps()) steps.putIfAbsent(step.output(), step);
    var nodes = new ArrayList<Node>();
    boolean truncated =
        !walk(plan, steps, inputs, plan.request(), plan.amount(), 0, new HashSet<>(), nodes, limit);
    return new Tree(nodes, truncated);
  }

  /** Adds {@code key}'s subtree; false when the limit cut it short. */
  private static boolean walk(
      CraftingPlanner.Plan plan,
      Map<ResourceKey, CraftingPlanner.Step> steps,
      Function<UUID, Map<ResourceKey, Long>> inputs,
      ResourceKey key,
      long amount,
      int depth,
      Set<ResourceKey> path,
      List<Node> nodes,
      int limit) {
    if (nodes.size() >= limit) return false;
    var step = steps.get(key);
    Kind kind =
        path.contains(key)
            ? Kind.LOOP
            : step != null
                ? Kind.CRAFTED
                : plan.missing().containsKey(key) ? Kind.MISSING : Kind.STORED;
    nodes.add(new Node(key, depth, amount, kind));
    if (kind != Kind.CRAFTED || depth >= MAX_DEPTH) return true;
    var perCraft = inputs.apply(step.pattern());
    if (perCraft == null) return true;
    path.add(key);
    try {
      for (var input : perCraft.entrySet()) {
        long needed = saturatedTimes(input.getValue(), step.crafts());
        if (!walk(plan, steps, inputs, input.getKey(), needed, depth + 1, path, nodes, limit))
          return false;
      }
    } finally {
      path.remove(key);
    }
    return true;
  }

  private static long saturatedTimes(long a, long b) {
    long high = Math.multiplyHigh(a, b);
    long low = a * b;
    return high != 0 || low < 0 ? Long.MAX_VALUE : low;
  }
}
