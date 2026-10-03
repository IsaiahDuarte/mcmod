package dev.izzy.factorycore.platform.resource;

import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ResourcePort;
import java.util.Objects;
import java.util.UUID;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/** Exact millibuckets through one resource-sensitive fill/drain call. */
public final class FluidHandlerPort implements ResourcePort {
  private final UUID backingId;
  private final IFluidHandler handler;
  private final PlatformResourceCodec codec;

  public FluidHandlerPort(UUID backingId, IFluidHandler handler, PlatformResourceCodec codec) {
    this.backingId = Objects.requireNonNull(backingId);
    this.handler = Objects.requireNonNull(handler);
    this.codec = Objects.requireNonNull(codec);
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
    int actual = handler.fill(codec.fluidStack(key, amount), action);
    if (actual < 0 || actual > amount) throw new IllegalStateException("Invalid fluid acceptance");
    return actual;
  }

  @Override
  public long extract(ResourceKey key, long requested) {
    OperationResult.requireQuantity(requested);
    if (requested == 0 || key.kind() != ResourceKind.FLUID) return 0;
    int amount = (int) Math.min(requested, Integer.MAX_VALUE);
    var extracted = handler.drain(codec.fluidStack(key, amount), FluidAction.EXECUTE);
    if (!extracted.isEmpty()
        && (!codec.fluidKey(extracted).equals(key) || extracted.getAmount() > amount)) {
      throw new IllegalStateException("Invalid fluid extraction identity or amount");
    }
    return extracted.getAmount();
  }
}
