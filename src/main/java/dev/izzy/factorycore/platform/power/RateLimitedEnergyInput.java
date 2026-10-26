package dev.izzy.factorycore.platform.power;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * A consume-only FE buffer that accepts at most {@code rate} FE per game tick, used by devices that
 * pay their own running cost (Access Point, Program Controller, Wireless Link). It never gives
 * energy out; the owner spends it with {@link #spend}. Server thread only.
 *
 * <p>{@code serverTick} returns the level's game time on the logical server and a negative value
 * otherwise (client or no level), when nothing is accepted. {@code accepting} lets the owner refuse
 * input, for example while its saved data is unsupported. {@code dirty} runs after each change.
 */
public final class RateLimitedEnergyInput implements IEnergyStorage {
  private final long capacity, rate;
  private final LongSupplier serverTick;
  private final BooleanSupplier accepting;
  private final Runnable dirty;
  private long energy;
  private long inputTick = Long.MIN_VALUE, inputThisTick;

  public RateLimitedEnergyInput(
      long capacity,
      long rate,
      LongSupplier serverTick,
      BooleanSupplier accepting,
      Runnable dirty) {
    if (capacity < 1 || capacity > Integer.MAX_VALUE || rate < 1)
      throw new IllegalArgumentException(
          "Energy input needs 1 <= capacity <= 2^31-1 and rate >= 1");
    this.capacity = capacity;
    this.rate = rate;
    this.serverTick = Objects.requireNonNull(serverTick);
    this.accepting = Objects.requireNonNull(accepting);
    this.dirty = Objects.requireNonNull(dirty);
  }

  public long energy() {
    return energy;
  }

  public long capacity() {
    return capacity;
  }

  /** Spends {@code amount} FE if available; spends nothing and returns false otherwise. */
  public boolean spend(long amount) {
    if (amount < 0 || amount > energy) return false;
    energy -= amount;
    dirty.run();
    return true;
  }

  /** Restores saved energy, clamped to {@code [0, capacity]}; does not mark the owner dirty. */
  public void restore(long saved) {
    energy = Math.max(0, Math.min(capacity, saved));
  }

  @Override
  public int receiveEnergy(int amount, boolean simulate) {
    if (amount <= 0 || !accepting.getAsBoolean()) return 0;
    long tick = serverTick.getAsLong();
    if (tick < 0) return 0;
    long used = tick == inputTick ? inputThisTick : 0;
    long accepted = Math.max(0, Math.min(amount, Math.min(capacity - energy, rate - used)));
    if (!simulate && accepted > 0) {
      if (tick != inputTick) {
        inputTick = tick;
        inputThisTick = 0;
      }
      inputThisTick += accepted;
      energy += accepted;
      dirty.run();
    }
    return (int) accepted;
  }

  @Override
  public int extractEnergy(int amount, boolean simulate) {
    return 0;
  }

  @Override
  public int getEnergyStored() {
    return (int) energy;
  }

  @Override
  public int getMaxEnergyStored() {
    return (int) capacity;
  }

  @Override
  public boolean canExtract() {
    return false;
  }

  @Override
  public boolean canReceive() {
    return accepting.getAsBoolean();
  }
}
