package dev.izzy.factorycore.core.crafting;

import dev.izzy.factorycore.core.resource.ResourceKey;
import java.util.Collections;
import java.util.LinkedHashMap;
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
    Map<ResourceKey, Long> byproducts) {
  public static final int MAX_INPUT_KEYS = 9;
  public static final int MAX_BYPRODUCT_KEYS = 9;
  public static final int MIN_PRIORITY = -1_000, MAX_PRIORITY = 1_000;

  public enum Type {
    CRAFTING,
    PROCESSING
  }

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
