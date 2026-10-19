package dev.izzy.factorycore.core.program;

/**
 * Host-side work allowance for one rule invocation. Guest metering (evaluation steps or Wasm
 * instructions) does not bound what a single host call does, so the server charges every host query
 * in work units: one unit per storage catalog page read and one unit per started 16-row label page.
 * Exceeding the allowance is a {@link ProgramEvaluator.Trap}; the invocation stages nothing.
 * Instances are single-threaded and belong to exactly one invocation.
 */
public final class HostBudget {
  /** Work units one invocation may spend across all of its host calls. */
  public static final int MAX_WORK = 256;

  private final int limit;
  private int used;

  public HostBudget() {
    this(MAX_WORK);
  }

  public HostBudget(int limit) {
    if (limit < 1) throw new IllegalArgumentException("Host budget must be positive");
    this.limit = limit;
  }

  /** Charges {@code units} for {@code what}; throws a trap naming the query when exhausted. */
  public void charge(int units, String what) {
    if (units < 0) throw new IllegalArgumentException("Negative host work");
    if (units > limit - used) {
      used = limit;
      throw new ProgramEvaluator.Trap(
          "host work budget of " + limit + " units per invocation exceeded by " + what);
    }
    used += units;
  }

  public int used() {
    return used;
  }

  public int limit() {
    return limit;
  }
}
