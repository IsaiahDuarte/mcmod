package dev.izzy.factorycore.platform.session;

/**
 * Admits on average one event per {@code period} server ticks, with up to {@code burst} admitted
 * back to back after a quiet spell (a generic cell rate: credit returns at one per period). With a
 * burst of one, events closer than {@code period} ticks are refused.
 */
public final class TickGate {
  private final int period, burst;
  private long due = Long.MIN_VALUE;

  public TickGate(int period) {
    this(period, 1);
  }

  public TickGate(int period, int burst) {
    if (period <= 0 || burst <= 0) throw new IllegalArgumentException("Invalid tick gate");
    this.period = period;
    this.burst = burst;
  }

  /** True, and spends one credit, when the event at {@code tick} is within the allowance. */
  public boolean admit(int tick) {
    long next = Math.max(due, tick);
    if (next - tick > (long) (burst - 1) * period) return false;
    due = next + period;
    return true;
  }

  /** Restores the full allowance, so the next events are admitted immediately. */
  public void reset() {
    due = Long.MIN_VALUE;
  }
}
