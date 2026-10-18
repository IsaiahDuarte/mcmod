package dev.izzy.factorycore.core.storage;

/**
 * Portable Fuel Generator accounting in FE and burn ticks; one owner thread, no platform types.
 *
 * <p>Fuel is consumed only through {@link #ignite}. Burning pauses, retaining the remaining burn
 * ticks, whenever one burn tick's output would not fit, so no generated FE is discarded. Output is
 * limited to {@link #MAX_OUTPUT} FE per game tick across every caller.
 */
public final class FuelGenerator {
  public static final long CAPACITY = 100_000;
  public static final long PER_BURN_TICK = 20;
  public static final long MAX_OUTPUT = 80;

  /** Why the generator is not currently producing FE. */
  public enum Status {
    BURNING,
    NO_FUEL,
    FULL
  }

  /** Immutable persisted snapshot; all values are validated on restore. */
  public record Snapshot(long energy, long burnRemaining, long burnTotal) {
    public Snapshot {
      if (energy < 0
          || energy > CAPACITY
          || burnRemaining < 0
          || burnTotal < burnRemaining
          || burnTotal > Integer.MAX_VALUE)
        throw new IllegalArgumentException("Invalid fuel generator state");
    }
  }

  private long energy;
  private long burnRemaining;
  private long burnTotal;
  private long budgetTick = Long.MIN_VALUE;
  private long spent;

  public FuelGenerator() {}

  public FuelGenerator(Snapshot saved) {
    energy = saved.energy();
    burnRemaining = saved.burnRemaining();
    burnTotal = saved.burnTotal();
  }

  public Snapshot snapshot() {
    return new Snapshot(energy, burnRemaining, burnTotal);
  }

  public long energy() {
    return energy;
  }

  public long burnRemaining() {
    return burnRemaining;
  }

  public long burnTotal() {
    return burnTotal;
  }

  public Status status() {
    if (burnRemaining == 0) return Status.NO_FUEL;
    return CAPACITY - energy < PER_BURN_TICK ? Status.FULL : Status.BURNING;
  }

  /** True when a new fuel item may be consumed now. */
  public boolean needsFuel() {
    return burnRemaining == 0 && CAPACITY - energy >= PER_BURN_TICK;
  }

  /** Starts burning one fuel item of the given vanilla burn duration; caller consumes it after. */
  public void ignite(int burnTicks) {
    if (burnTicks <= 0) throw new IllegalArgumentException("Burn duration must be positive");
    if (!needsFuel()) throw new IllegalStateException("Generator cannot accept fuel now");
    burnRemaining = burnTicks;
    burnTotal = burnTicks;
  }

  /** Advances one burn tick when the full output fits. Returns FE generated (0 or 20). */
  public long burn() {
    if (status() != Status.BURNING) return 0;
    burnRemaining--;
    energy += PER_BURN_TICK;
    if (burnRemaining == 0) burnTotal = 0;
    return PER_BURN_TICK;
  }

  /**
   * Removes up to {@code requested} FE, bounded by stored energy and the remaining per-tick output
   * budget for {@code tick}. Simulation reports the same amount without changing state.
   */
  public long extract(long requested, boolean simulate, long tick) {
    if (requested < 0) throw new IllegalArgumentException("Negative FE request");
    if (tick != budgetTick) {
      if (tick < budgetTick) throw new IllegalStateException("Generator tick moved backwards");
      if (!simulate) {
        budgetTick = tick;
        spent = 0;
      }
    }
    long used = tick == budgetTick ? spent : 0;
    long amount = Math.min(requested, Math.min(energy, MAX_OUTPUT - used));
    if (!simulate && amount > 0) {
      energy -= amount;
      spent += amount;
    }
    return amount;
  }
}
