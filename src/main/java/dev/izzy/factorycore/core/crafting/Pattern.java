package dev.izzy.factorycore.core.crafting;

import dev.izzy.factorycore.core.resource.ResourceKey;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable versioned recipe contract in exact resource units. Crafting-table patterns run on a
 * Crafting Unit; processing patterns run on bound machine ports. Inputs are exact keys; the primary
 * output and deterministic byproducts (container remainders, returns) are produced per craft.
 */
public record Pattern(
    UUID id,
    long version,
    Type type,
    int priority,
    Map<ResourceKey, Long> inputs,
    ResourceKey output,
    long outputAmount,
    Map<ResourceKey, Long> byproducts,
    Map<ResourceKey, List<ResourceKey>> substitutes) {
  public static final int MAX_INPUT_KEYS = 9;

  /** Alternatives one substituting input may list (E1, PD4): a full vanilla tag fits. */
  public static final int MAX_ALTERNATIVES = 16;

  public static final int MAX_BYPRODUCT_KEYS = 9;
  public static final int MIN_PRIORITY = -1_000, MAX_PRIORITY = 1_000;

  public enum Type {
    CRAFTING,
    PROCESSING
  }

  /** An exact pattern: every input is exactly its key (no substitution). */
  public Pattern(
      UUID id,
      long version,
      Type type,
      int priority,
      Map<ResourceKey, Long> inputs,
      ResourceKey output,
      long outputAmount,
      Map<ResourceKey, Long> byproducts) {
    this(id, version, type, priority, inputs, output, outputAmount, byproducts, Map.of());
  }

  /**
   * {@code substitutes} (E1, PD4) maps an input key to other keys that may stand in for it, for a
   * crafting-table pattern only. Alternatives never include the output, any input key or another
   * input's alternative, so every concrete key belongs to one input.
   */
  public Pattern {
    Objects.requireNonNull(id);
    Objects.requireNonNull(type);
    Objects.requireNonNull(output);
    if (version < 1) throw new IllegalArgumentException("Pattern version must be positive");
    if (priority < MIN_PRIORITY || priority > MAX_PRIORITY)
      throw new IllegalArgumentException("Pattern priority out of range");
    if (outputAmount < 1) throw new IllegalArgumentException("Pattern must produce output");
    inputs = checked(inputs, MAX_INPUT_KEYS, "input");
    byproducts = checked(byproducts, MAX_BYPRODUCT_KEYS, "byproduct");
    if (inputs.isEmpty()) throw new IllegalArgumentException("Pattern needs inputs");
    if (inputs.containsKey(output))
      throw new IllegalArgumentException("Pattern output cannot also be its input");
    var copy = new LinkedHashMap<ResourceKey, List<ResourceKey>>();
    var seen = new HashSet<ResourceKey>(inputs.keySet());
    seen.add(output);
    for (var entry : substitutes.entrySet()) {
      if (type != Type.CRAFTING || !inputs.containsKey(entry.getKey()))
        throw new IllegalArgumentException("Only crafting-pattern inputs take substitutes");
      var alternatives = List.copyOf(entry.getValue());
      if (alternatives.isEmpty() || alternatives.size() > MAX_ALTERNATIVES)
        throw new IllegalArgumentException("A substitute list holds 1–16 alternatives");
      for (var alternative : alternatives)
        if (!seen.add(alternative))
          throw new IllegalArgumentException("Substitutes must be distinct from every other key");
      copy.put(entry.getKey(), alternatives);
    }
    substitutes = Collections.unmodifiableMap(copy);
  }

  private static Map<ResourceKey, Long> checked(
      Map<ResourceKey, Long> values, int max, String what) {
    if (values.size() > max) throw new IllegalArgumentException("Too many pattern " + what + "s");
    var copy = new LinkedHashMap<ResourceKey, Long>();
    for (var entry : values.entrySet()) {
      if (entry.getValue() == null || entry.getValue() < 1)
        throw new IllegalArgumentException("Pattern " + what + " amounts must be positive");
      copy.put(Objects.requireNonNull(entry.getKey()), entry.getValue());
    }
    return Collections.unmodifiableMap(copy);
  }
}
