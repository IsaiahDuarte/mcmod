package dev.izzy.factorycore.platform.session;

/** Admits at most one event per {@code period} server ticks. */
public final class TickGate {
  private final int period;
  private int last = Integer.MIN_VALUE;

  public TickGate(int period) {
    if (period <= 0) throw new IllegalArgumentException("Invalid tick period");
    this.period = period;
  }

  /** True, and records {@code tick}, when at least {@code period} ticks passed since the last. */
  public boolean admit(int tick) {
    if (last != Integer.MIN_VALUE && tick - last < period) return false;
    last = tick;
    return true;
  }

  /** Forgets the last admitted tick, so the next event is admitted immediately. */
  public void reset() {
    last = Integer.MIN_VALUE;
  }
}
