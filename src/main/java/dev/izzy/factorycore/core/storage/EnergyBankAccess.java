package dev.izzy.factorycore.core.storage;

import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Shared world access; budgets follow canonical backing identities across faces and moves. */
public final class EnergyBankAccess {
  private static final class Budget {
    long input;
    long output;
    final long tick;

    Budget(long tick) {
      this.tick = tick;
    }
  }

  private final Thread thread = Thread.currentThread();
  private final DeviceRegistry registry;
  private final LongSupplier clock;
  private final Map<UUID, Budget> budgets = new HashMap<>();

  /** Q20: each backing's budget for the tick before its current one, while that was the last. */
  private final Map<UUID, Budget> previous = new HashMap<>();

  /** FE that went in and out of one bank during a tick. */
  public record Flow(long input, long output) {
    public static final Flow IDLE = new Flow(0, 0);
  }

  private long lastTick = -1;

  public EnergyBankAccess(DeviceRegistry registry, LongSupplier clock) {
    this.registry = Objects.requireNonNull(registry);
    this.clock = Objects.requireNonNull(clock);
    registry.world();
  }

  public OperationResult receive(
      DeviceRegistry.Handle handle, EnergyBankTier tier, long amount, boolean simulate) {
    return access(handle, tier, amount, simulate, true);
  }

  public OperationResult extract(
      DeviceRegistry.Handle handle, EnergyBankTier tier, long amount, boolean simulate) {
    return access(handle, tier, amount, simulate, false);
  }

  private OperationResult access(
      DeviceRegistry.Handle handle,
      EnergyBankTier tier,
      long requested,
      boolean simulate,
      boolean input) {
    if (thread != Thread.currentThread())
      throw new IllegalStateException("Energy access requires owner thread");
    OperationResult.requireQuantity(requested);
    Objects.requireNonNull(tier);
    long tick = clock.getAsLong();
    if (tick < 0 || tick < lastTick)
      throw new IllegalStateException("Energy tick clock moved backwards");
    lastTick = tick;
    if (registry.validate(handle) != DeviceRegistry.Failure.OK)
      return result(0, OperationResult.Reason.DENIED);
    var definition = registry.definition(handle);
    if (definition.kind() != ResourceKind.ENERGY)
      return result(0, OperationResult.Reason.WRONG_RESOURCE);
    if (definition.infinite() || definition.capacity() != tier.capacity())
      throw new IllegalArgumentException("Energy-bank tier does not match canonical capacity");
    var stock = registry.stock(handle, ResourceKey.ENERGY);
    if (!stock.loaded()) return result(0, OperationResult.Reason.OFFLINE);
    if (requested == 0) return result(0, OperationResult.Reason.OK);
    var budget = budgets.get(handle.backing());
    if (budget == null || budget.tick != tick) budget = new Budget(tick);
    long rate = Math.max(0, tier.rate() - (input ? budget.input : budget.output));
    long resources = input ? tier.capacity() - stock.total() : stock.available();
    long offered = Math.min(requested, Math.min(rate, resources));
    var remainder =
        requested <= offered
            ? OperationResult.Reason.OK
            : resources <= rate
                ? input
                    ? OperationResult.Reason.FULL
                    : stock.reserved() > 0
                        ? OperationResult.Reason.RESERVED
                        : OperationResult.Reason.SHORTAGE
                : OperationResult.Reason.RATE_LIMIT;
    if (simulate || offered == 0) return result(offered, remainder);
    var actual =
        input
            ? registry.insert(handle, ResourceKey.ENERGY, offered)
            : registry.extract(handle, ResourceKey.ENERGY, offered, null);
    if (actual.amount() > 0) {
      if (input) budget.input = Math.addExact(budget.input, actual.amount());
      else budget.output = Math.addExact(budget.output, actual.amount());
      var replaced = budgets.put(handle.backing(), budget);
      if (replaced != null && replaced != budget) {
        if (replaced.tick == tick - 1) previous.put(handle.backing(), replaced);
        else previous.remove(handle.backing());
      }
    }
    return result(actual.amount(), actual.amount() == offered ? remainder : actual.reason());
  }

  /**
   * Q20: the FE that went into and out of the bank with this backing during the last complete tick
   * (the one before the clock's current tick); idle when nothing moved then.
   */
  public Flow lastTick(UUID backing) {
    long last = clock.getAsLong() - 1;
    var current = budgets.get(backing);
    var budget = current == null ? null : current.tick == last ? current : previous.get(backing);
    return budget == null || budget.tick != last
        ? Flow.IDLE
        : new Flow(budget.input, budget.output);
  }

  private static OperationResult result(long amount, OperationResult.Reason reason) {
    return new OperationResult(amount, reason);
  }
}
