package dev.izzy.factorycore.platform.resource;

import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ResourcePort;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/** Explicit bounded slot window on a caller-selected side's item capability. */
public final class ItemHandlerPort implements ResourcePort {
  public static final int MAX_SLOTS_PER_CALL = 64;
  private final UUID backingId;
  private final IItemHandler handler;
  private final PlatformResourceCodec codec;
  private final int firstSlot;
  private final int slotCount;

  public ItemHandlerPort(
      UUID backingId,
      IItemHandler handler,
      PlatformResourceCodec codec,
      int firstSlot,
      int slotCount) {
    this.backingId = Objects.requireNonNull(backingId);
    this.handler = Objects.requireNonNull(handler);
    this.codec = Objects.requireNonNull(codec);
    if (firstSlot < 0
        || slotCount <= 0
        || slotCount > MAX_SLOTS_PER_CALL
        || firstSlot > handler.getSlots() - slotCount) {
      throw new IllegalArgumentException("Invalid or unbounded item slot window");
    }
    this.firstSlot = firstSlot;
    this.slotCount = slotCount;
  }

  @Override
  public UUID backingId() {
    return backingId;
  }

  @Override
  public long simulateInsert(ResourceKey key, long offered) {
    return insert(key, offered, true);
  }

  @Override
  public long insert(ResourceKey key, long offered) {
    return insert(key, offered, false);
  }

  private long insert(ResourceKey key, long offered, boolean simulate) {
    OperationResult.requireQuantity(offered);
    if (offered == 0 || key.kind() != ResourceKind.ITEM) return 0;
    int amount = (int) Math.min(offered, Integer.MAX_VALUE);
    for (int slot = firstSlot; slot < firstSlot + slotCount; slot++) {
      var offeredStack = codec.itemStack(key, amount);
      var simulated = handler.insertItem(slot, offeredStack, true);
      int accepted = accepted(key, amount, simulated);
      if (accepted == 0) continue;
      if (simulate) return accepted;
      var actual = handler.insertItem(slot, codec.itemStack(key, amount), false);
      return accepted(key, amount, actual);
    }
    return 0;
  }

  private int accepted(ResourceKey key, int amount, ItemStack remainder) {
    if (!remainder.isEmpty()
        && (!codec.itemKey(remainder).equals(key) || remainder.getCount() > amount)) {
      throw new IllegalStateException("Item handler returned invalid insertion remainder");
    }
    return amount - remainder.getCount();
  }

  @Override
  public long extract(ResourceKey key, long requested) {
    OperationResult.requireQuantity(requested);
    if (requested == 0 || key.kind() != ResourceKind.ITEM) return 0;
    int amount = (int) Math.min(requested, Integer.MAX_VALUE);
    for (int slot = firstSlot; slot < firstSlot + slotCount; slot++) {
      var observed = handler.getStackInSlot(slot);
      if (observed.isEmpty() || !codec.itemKey(observed).equals(key)) continue;
      var extracted = handler.extractItem(slot, amount, false);
      if (!extracted.isEmpty()
          && (!codec.itemKey(extracted).equals(key) || extracted.getCount() > amount)) {
        throw new IllegalStateException(
            "Item handler extracted an unexpected identity or quantity");
      }
      return extracted.getCount();
    }
    return 0;
  }
}
