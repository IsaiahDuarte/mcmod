package dev.izzy.factorycore.core.storage;

import java.util.Objects;

/**
 * C1: what a Signal-mode Interface emits. {@link Condition#STOCK} compares the network's stored
 * amount of one resource with {@code threshold}; {@link Condition#JOB} is true while a crafting job
 * for that resource is running. {@link Output#FULL} emits 15 when the condition holds; {@link
 * Output#PROPORTIONAL} grows like a comparator: 0 at the "off" end, 15 at the threshold.
 */
public record SignalRule(Condition condition, Compare compare, long threshold, Output output) {
  public enum Condition {
    STOCK,
    JOB
  }

  public enum Compare {
    /** On when at least {@code threshold} is stored. */
    AT_LEAST,
    /** On when less than {@code threshold} is stored. */
    BELOW
  }

  public enum Output {
    FULL,
    PROPORTIONAL
  }

  public static final int MAX = 15;

  public static final SignalRule DEFAULT =
      new SignalRule(Condition.STOCK, Compare.AT_LEAST, 64, Output.FULL);

  public SignalRule {
    Objects.requireNonNull(condition);
    Objects.requireNonNull(compare);
    Objects.requireNonNull(output);
    if (threshold < 0) throw new IllegalArgumentException("Signal threshold must not be negative");
  }

  /** Strength for a stored {@code amount} (STOCK). */
  public int strength(long amount) {
    if (amount < 0) throw new IllegalArgumentException("Negative stock");
    boolean on = compare == Compare.AT_LEAST ? amount >= threshold : amount < threshold;
    if (output == Output.FULL || threshold == 0) return on ? MAX : 0;
    long capped = Math.min(amount, threshold);
    long toward = compare == Compare.AT_LEAST ? capped : threshold - capped;
    if (toward == 0) return 0;
    // Any progress lights at least 1; only the threshold itself reaches 15.
    long scaled =
        java.math.BigInteger.valueOf(toward)
            .multiply(java.math.BigInteger.valueOf(MAX))
            .divide(java.math.BigInteger.valueOf(threshold))
            .longValue();
    return (int) Math.max(1, Math.min(MAX, scaled));
  }

  /** Strength for whether a job is running (JOB); proportional output has no meaning here. */
  public int strength(boolean running) {
    return running ? MAX : 0;
  }
}
