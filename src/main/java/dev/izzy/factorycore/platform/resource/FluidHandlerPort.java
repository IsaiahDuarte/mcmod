package dev.izzy.factorycore.platform.resource;

import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ResourcePort;
import java.util.Objects;
import java.util.UUID;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/** Exact millibuckets; whole capabilities use one call, selected windows visit at most 64 tanks. */
public final class FluidHandlerPort implements ResourcePort {
  private final UUID backingId;
  private final IFluidHandler handler;
  private final PlatformResourceCodec codec;
  private final int firstTank;
  private final int tankCount;
  private final boolean selected;

  public FluidHandlerPort(UUID backingId, IFluidHandler handler, PlatformResourceCodec codec) {
    this(backingId, handler, codec, 0, 64);
  }

  public FluidHandlerPort(
      UUID backingId,
      IFluidHandler handler,
      PlatformResourceCodec codec,
      int firstTank,
      int tankCount) {
    this.backingId = Objects.requireNonNull(backingId);
    this.handler = Objects.requireNonNull(handler);
    this.codec = Objects.requireNonNull(codec);
    if (firstTank < 0 || tankCount < 1 || tankCount > 64)
      throw new IllegalArgumentException("Invalid selected tank window");
    this.firstTank = firstTank;
    this.tankCount = tankCount;
    selected = firstTank != 0 || tankCount != 64;
    if (selected && (!(handler instanceof IndexedFluidHandler) || firstTank >= handler.getTanks()))
      throw new IllegalArgumentException("Selected tanks require an indexed fluid adapter");
  }

  @Override
  public UUID backingId() {
    return backingId;
  }

  @Override
  public long simulateInsert(ResourceKey key, long offered) {
    return fill(key, offered, FluidAction.SIMULATE);
  }

  @Override
  public long insert(ResourceKey key, long offered) {
    return fill(key, offered, FluidAction.EXECUTE);
  }

  private long fill(ResourceKey key, long offered, FluidAction action) {
    OperationResult.requireQuantity(offered);
    if (offered == 0 || key.kind() != ResourceKind.FLUID) return 0;
    int amount = (int) Math.min(offered, Integer.MAX_VALUE);
    if (!selected) return checkedFill(handler.fill(codec.fluidStack(key, amount), action), amount);
    var indexed = (IndexedFluidHandler) handler;
    int count = selectedCount();
    int remaining = amount;
    for (int index = 0; index < count && remaining > 0; index++)
      remaining -=
          checkedFill(
              indexed.fillTank(firstTank + index, codec.fluidStack(key, remaining), action),
              remaining);
    return amount - remaining;
  }

  @Override
  public long extract(ResourceKey key, long requested) {
    OperationResult.requireQuantity(requested);
    if (requested == 0 || key.kind() != ResourceKind.FLUID) return 0;
    int amount = (int) Math.min(requested, Integer.MAX_VALUE);
    if (!selected)
      return checkedDrain(
          handler.drain(codec.fluidStack(key, amount), FluidAction.EXECUTE), key, amount);
    var indexed = (IndexedFluidHandler) handler;
    int count = selectedCount();
    int remaining = amount;
    for (int index = 0; index < count && remaining > 0; index++)
      remaining -=
          checkedDrain(
              indexed.drainTank(
                  firstTank + index, codec.fluidStack(key, remaining), FluidAction.EXECUTE),
              key,
              remaining);
    return amount - remaining;
  }

  private int selectedCount() {
    int tanks = handler.getTanks();
    if (tanks < 0 || firstTank >= tanks)
      throw new IllegalStateException("Selected tank window is unavailable");
    return Math.min(tankCount, tanks - firstTank);
  }

  private static int checkedFill(int actual, int offered) {
    if (actual < 0 || actual > offered) throw new IllegalStateException("Invalid fluid acceptance");
    return actual;
  }

  private int checkedDrain(FluidStack extracted, ResourceKey key, int amount) {
    if (extracted.getAmount() < 0
        || !extracted.isEmpty()
            && (!codec.fluidKey(extracted).equals(key) || extracted.getAmount() > amount))
      throw new IllegalStateException("Invalid fluid extraction identity or amount");
    return extracted.getAmount();
  }
}
