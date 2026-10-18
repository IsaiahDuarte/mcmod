package dev.izzy.factorycore.core.crafting;

import dev.izzy.factorycore.core.resource.ResourceKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Bounded recursive autocrafting planner over an immutable stock snapshot. It reserves and moves
 * nothing: the returned plan is advisory until a job reserves its {@code consumed} stock.
 *
 * <p>Each need is served first from units this plan already produces (surplus outputs and
 * byproducts), then from available stock, then by the highest-priority pattern producing that key
 * (ties by stable pattern ID). An alternative is used only when every higher-ranked one leaves
 * shortages; if all do, the first is kept to explain its shortages. Only units taken from stock
 * appear in {@code consumed}, which is what a job must reserve. A key already being produced on the
 * current path is a cycle: it is reported and treated as missing. Global optimality is not
 * attempted.
 */
public final class CraftingPlanner {
  public static final int MAX_DEPTH = 32;
  public static final int MAX_NODES = 4_096;
  public static final int MAX_STEPS = 256;

  public enum Status {
    /** Every unit is covered by stock or planned crafts. */
    OK,
    /** Some units cannot be supplied; see {@link Plan#missing}. */
    MISSING,
    /** Planning stopped at the depth/node/step/quantity bound; nothing is complete. */
    LIMIT
  }

  /** One crafting step in dependency order: children always precede their consumers. */
  public record Step(UUID pattern, long version, ResourceKey output, long crafts) {}

  public record Plan(
      Status status,
      ResourceKey request,
      long amount,
      List<Step> steps,
      Map<ResourceKey, Long> consumed,
      Map<ResourceKey, Long> missing,
      List<List<ResourceKey>> cycles,
      int nodes) {
    public Plan {
      steps = List.copyOf(steps);
      consumed = Collections.unmodifiableMap(new LinkedHashMap<>(consumed));
      missing = Collections.unmodifiableMap(new LinkedHashMap<>(missing));
      cycles = cycles.stream().map(List::copyOf).toList();
    }
  }

  private static final class LimitReached extends RuntimeException {
    private static final long serialVersionUID = 1L;

    LimitReached() {
      super(null, null, false, false);
    }
  }

  /** Mutable search state that can be copied for alternative attempts. */
  private static final class State {
    final Map<ResourceKey, Long> available;
    final Map<ResourceKey, Long> produced;
    final Map<ResourceKey, Long> consumed;
    final Map<ResourceKey, Long> missing;
    final List<Step> steps;
    final List<List<ResourceKey>> cycles;

    State(Map<ResourceKey, Long> available) {
      this.available = new HashMap<>(available);
      produced = new HashMap<>();
      consumed = new LinkedHashMap<>();
      missing = new LinkedHashMap<>();
      steps = new ArrayList<>();
      cycles = new ArrayList<>();
    }

    State(State other) {
      available = new HashMap<>(other.available);
      produced = new HashMap<>(other.produced);
      consumed = new LinkedHashMap<>(other.consumed);
      missing = new LinkedHashMap<>(other.missing);
      steps = new ArrayList<>(other.steps);
      cycles = new ArrayList<>(other.cycles);
    }
  }

  private final Function<ResourceKey, Collection<Pattern>> producers;
  private int nodes;

  /** {@code producers} returns patterns whose primary output is the key, in any order. */
  public CraftingPlanner(Function<ResourceKey, Collection<Pattern>> producers) {
    this.producers = Objects.requireNonNull(producers);
  }

  /** Plans {@code amount} units of {@code request} against exact available stock. */
  public Plan plan(ResourceKey request, long amount, Map<ResourceKey, Long> available) {
    Objects.requireNonNull(request);
    if (amount < 1) throw new IllegalArgumentException("Request amount must be positive");
    for (var value : available.values())
      if (value < 0) throw new IllegalArgumentException("Negative available stock");
    nodes = 0;
    var state = new State(available);
    try {
      need(state, request, amount, new ArrayList<>(), true);
    } catch (LimitReached | ArithmeticException limit) {
      return new Plan(
          Status.LIMIT, request, amount, List.of(), Map.of(), Map.of(), List.of(), nodes);
    }
    return new Plan(
        state.missing.isEmpty() ? Status.OK : Status.MISSING,
        request,
        amount,
        state.steps,
        state.consumed,
        state.missing,
        state.cycles,
        nodes);
  }

  private static List<Pattern> ranked(Collection<Pattern> patterns, ResourceKey key) {
    var list = new ArrayList<Pattern>();
    for (var pattern : patterns) if (pattern.output().equals(key)) list.add(pattern);
    list.sort(Comparator.comparingInt(Pattern::priority).reversed().thenComparing(Pattern::id));
    return list;
  }

  /**
   * Serves {@code amount} of {@code key}. The requested top-level item is always crafted, never
   * taken from stock, because a craft request asks for new output.
   */
  private void need(
      State state, ResourceKey key, long amount, List<ResourceKey> path, boolean top) {
    if (++nodes > MAX_NODES || path.size() >= MAX_DEPTH) throw new LimitReached();
    long remaining = amount;
    if (!top) {
      long made = state.produced.getOrDefault(key, 0L);
      long reuse = Math.min(made, remaining);
      if (reuse > 0) {
        state.produced.put(key, made - reuse);
        remaining -= reuse;
      }
      long have = state.available.getOrDefault(key, 0L);
      long take = Math.min(have, remaining);
      if (take > 0) {
        state.available.put(key, have - take);
        state.consumed.merge(key, take, Math::addExact);
        remaining -= take;
      }
    }
    if (remaining == 0) return;
    if (path.contains(key)) {
      var cycle = new ArrayList<>(path.subList(path.indexOf(key), path.size()));
      cycle.add(key);
      state.cycles.add(cycle);
      state.missing.merge(key, remaining, Math::addExact);
      return;
    }
    var candidates = ranked(producers.apply(key), key);
    if (candidates.isEmpty()) {
      state.missing.merge(key, remaining, Math::addExact);
      return;
    }
    State chosen = null;
    for (var pattern : candidates) {
      var attempt = new State(state);
      expand(attempt, pattern, remaining, path);
      if (chosen == null) chosen = attempt;
      if (attempt.missing.size() == state.missing.size() && attempt.missing.equals(state.missing)) {
        chosen = attempt;
        break;
      }
    }
    copy(chosen, state);
  }

  private void expand(State state, Pattern pattern, long amount, List<ResourceKey> path) {
    long crafts =
        Math.addExact(
            amount / pattern.outputAmount(), amount % pattern.outputAmount() == 0 ? 0 : 1);
    path.add(pattern.output());
    try {
      for (var input : pattern.inputs().entrySet())
        need(state, input.getKey(), Math.multiplyExact(input.getValue(), crafts), path, false);
    } finally {
      path.remove(path.size() - 1);
    }
    if (state.steps.size() >= MAX_STEPS) throw new LimitReached();
    state.steps.add(new Step(pattern.id(), pattern.version(), pattern.output(), crafts));
    long surplus = Math.multiplyExact(crafts, pattern.outputAmount()) - amount;
    if (surplus > 0) state.produced.merge(pattern.output(), surplus, Math::addExact);
    for (var byproduct : pattern.byproducts().entrySet())
      state.produced.merge(
          byproduct.getKey(), Math.multiplyExact(byproduct.getValue(), crafts), Math::addExact);
  }

  private static void copy(State from, State to) {
    to.available.clear();
    to.available.putAll(from.available);
    to.produced.clear();
    to.produced.putAll(from.produced);
    to.consumed.clear();
    to.consumed.putAll(from.consumed);
    to.missing.clear();
    to.missing.putAll(from.missing);
    to.steps.clear();
    to.steps.addAll(from.steps);
    to.cycles.clear();
    to.cycles.addAll(from.cycles);
  }
}
