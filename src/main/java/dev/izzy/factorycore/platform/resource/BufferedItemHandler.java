package dev.izzy.factorycore.platform.resource;

import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.storage.ItemBuffer;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/** Physical automation view over the same owned slots used by scheduled transfers. */
public final class BufferedItemHandler implements IItemHandler {
  private final Thread thread = Thread.currentThread();
  private final ItemBuffer buffer;
  private final PlatformResourceCodec codec;
  private final BooleanSupplier usable;

  /** Usable must revalidate the current loaded physical lease on the server thread. */
  public BufferedItemHandler(
      ItemBuffer buffer, PlatformResourceCodec codec, BooleanSupplier usable) {
    this.buffer = Objects.requireNonNull(buffer);
    this.codec = Objects.requireNonNull(codec);
    this.usable = Objects.requireNonNull(usable);
  }

  @Override
  public int getSlots() {
    checkThread();
    return ItemBuffer.SLOTS;
  }

  @Override
  public ItemStack getStackInSlot(int slot) {
    checkSlot(slot);
    if (!usable.getAsBoolean()) return ItemStack.EMPTY;
    var stored = buffer.slot(slot);
    return stored.key() == null ? ItemStack.EMPTY : codec.itemStack(stored.key(), stored.count());
  }

  @Override
  public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
    checkSlot(slot);
    Objects.requireNonNull(stack);
    if (stack.isEmpty()) return ItemStack.EMPTY;
    if (!usable.getAsBoolean()) return stack.copy();
    ResourceKey key;
    try {
      key = codec.itemKey(stack);
    } catch (IllegalArgumentException | java.io.UncheckedIOException unsupported) {
      return stack.copy();
    }
    long accepted = buffer.insertSlot(slot, key, stack.getCount(), simulate);
    return accepted == stack.getCount()
        ? ItemStack.EMPTY
        : stack.copyWithCount(stack.getCount() - (int) accepted);
  }

  @Override
  public ItemStack extractItem(int slot, int amount, boolean simulate) {
    checkSlot(slot);
    if (amount < 0) throw new IllegalArgumentException("Negative buffer extraction");
    if (!usable.getAsBoolean()) return ItemStack.EMPTY;
    var extracted = buffer.extractSlot(slot, amount, simulate);
    return extracted.key() == null
        ? ItemStack.EMPTY
        : codec.itemStack(extracted.key(), extracted.count());
  }

  @Override
  public int getSlotLimit(int slot) {
    checkSlot(slot);
    return ItemBuffer.MAX_STACK;
  }

  @Override
  public boolean isItemValid(int slot, ItemStack stack) {
    checkSlot(slot);
    Objects.requireNonNull(stack);
    return !stack.isEmpty() && usable.getAsBoolean();
  }

  private void checkSlot(int slot) {
    checkThread();
    if (slot < 0 || slot >= ItemBuffer.SLOTS)
      throw new IllegalArgumentException("Buffer slot must be 0..8");
  }

  private void checkThread() {
    if (Thread.currentThread() != thread)
      throw new IllegalStateException("Buffer capability requires its server thread");
  }
}
