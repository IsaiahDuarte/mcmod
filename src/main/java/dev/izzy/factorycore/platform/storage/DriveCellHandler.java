package dev.izzy.factorycore.platform.storage;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Q101 (M119): a drive's four bays for hoppers and pipes. A cell goes into an empty bay and whole
 * cells come out, through the same {@link StorageDriveEntity#installCell} and {@link
 * StorageDriveEntity#removeCell} the screen uses, so ownership, leases and summaries are the same.
 * Anything the drive refuses (a stale or duplicate cell, a drive needing recovery) stays where it
 * was.
 */
final class DriveCellHandler implements IItemHandler {
  private final StorageDriveEntity drive;

  DriveCellHandler(StorageDriveEntity drive) {
    this.drive = drive;
  }

  @Override
  public int getSlots() {
    return drive.bays();
  }

  @Override
  public ItemStack getStackInSlot(int slot) {
    return drive.cell(slot);
  }

  @Override
  public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
    if (stack.isEmpty() || !isItemValid(slot, stack) || !drive.cell(slot).isEmpty()) return stack;
    if (!simulate)
      try {
        drive.installCell(slot, stack.copyWithCount(1));
      } catch (IllegalArgumentException | IllegalStateException refused) {
        return stack;
      }
    return stack.copyWithCount(stack.getCount() - 1);
  }

  @Override
  public ItemStack extractItem(int slot, int amount, boolean simulate) {
    if (amount < 1 || drive.cell(slot).isEmpty()) return ItemStack.EMPTY;
    if (simulate) return drive.cell(slot);
    try {
      return drive.removeCell(slot);
    } catch (IllegalArgumentException | IllegalStateException refused) {
      return ItemStack.EMPTY;
    }
  }

  @Override
  public int getSlotLimit(int slot) {
    return 1;
  }

  @Override
  public boolean isItemValid(int slot, ItemStack stack) {
    return stack.getItem() instanceof CellItem;
  }
}
