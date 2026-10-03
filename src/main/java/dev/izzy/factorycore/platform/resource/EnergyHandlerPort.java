package dev.izzy.factorycore.platform.resource;

import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourcePort;
import java.util.Objects;
import java.util.UUID;
import net.neoforged.neoforge.energy.IEnergyStorage;

/** Whole FE units. Delivered FE is accounted independently of later wireless operating power. */
public final class EnergyHandlerPort implements ResourcePort {
  private final UUID backingId;
  private final IEnergyStorage handler;

  public EnergyHandlerPort(UUID backingId, IEnergyStorage handler) {
    this.backingId = Objects.requireNonNull(backingId);
    this.handler = Objects.requireNonNull(handler);
  }

  @Override
  public UUID backingId() {
    return backingId;
  }

  @Override
  public long simulateInsert(ResourceKey key, long offered) {
    return receive(key, offered, true);
  }

  @Override
  public long insert(ResourceKey key, long offered) {
    return receive(key, offered, false);
  }

  private long receive(ResourceKey key, long offered, boolean simulate) {
    OperationResult.requireQuantity(offered);
    if (!key.equals(ResourceKey.ENERGY) || offered == 0) return 0;
    int bounded = (int) Math.min(offered, Integer.MAX_VALUE);
    return checked(handler.receiveEnergy(bounded, simulate), bounded);
  }

  @Override
  public long extract(ResourceKey key, long requested) {
    OperationResult.requireQuantity(requested);
    if (!key.equals(ResourceKey.ENERGY) || requested == 0) return 0;
    int bounded = (int) Math.min(requested, Integer.MAX_VALUE);
    return checked(handler.extractEnergy(bounded, false), bounded);
  }

  private static int checked(int actual, int offered) {
    if (actual < 0 || actual > offered)
      throw new IllegalStateException("Invalid FE handler amount");
    return actual;
  }
}
