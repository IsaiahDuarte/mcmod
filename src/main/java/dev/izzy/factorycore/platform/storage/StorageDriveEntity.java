package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.storage.DeviceRegistry;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Four physical lease items; stock and reservations are never copied into chunk NBT. */
public final class StorageDriveEntity extends BlockEntity {
  public static final String RECOVERY = "FactoryCoreDriveRecovery";
  private static final String REJECTED = "RejectedCellData";
  private final ItemStack[] cells = {
    ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY
  };
  private CompoundTag rejected;
  private String diagnostic = "";

  public StorageDriveEntity(BlockPos position, BlockState state) {
    super(StorageContent.DRIVE_ENTITY.get(), position, state);
  }

  public String diagnostic() {
    return diagnostic;
  }

  public boolean needsDataRecovery() {
    return rejected != null;
  }

  public ItemStack cell(int slot) {
    checkSlot(slot);
    return cells[slot].copy();
  }

  // ServerLevel is borrowed from Minecraft; this device must never close the world.
  @SuppressWarnings("PMD.CloseResource")
  public DeviceRegistry.Location location(int slot) {
    checkSlot(slot);
    if (!(level instanceof ServerLevel server))
      throw new IllegalStateException("Drive mutation requires server level");
    return new DeviceRegistry.Location(
        server.dimension().location().toString(), worldPosition.asLong(), slot);
  }

  public int installCell(ItemStack input) {
    requireUsable();
    for (int slot = 0; slot < cells.length; slot++) {
      if (cells[slot].isEmpty()) {
        cells[slot] = CellOwnership.install(input, registry(), location(slot));
        setChanged();
        return slot;
      }
    }
    throw new IllegalStateException("All four drive slots are occupied");
  }

  public ItemStack removeCell(int slot) {
    requireUsable();
    checkSlot(slot);
    if (cells[slot].isEmpty()) return ItemStack.EMPTY;
    var handle = CellHandleData.read(cells[slot]);
    if (handle == null) throw new IllegalStateException("Placed cell has no lease");
    var detached = registry().detach(handle, location(slot));
    if (detached.failure() != DeviceRegistry.Failure.OK)
      throw new IllegalStateException("Cell removal: " + detached.failure());
    ItemStack portable = cells[slot].copy();
    CellHandleData.write(portable, detached.handle());
    cells[slot] = ItemStack.EMPTY;
    setChanged();
    return portable;
  }

  public List<ItemStack> ejectForBreak() {
    if (!(level instanceof ServerLevel)) return List.of();
    var dropped = new ArrayList<ItemStack>(4);
    if (rejected != null) {
      dropped.add(recoveryStack(rejected));
      return dropped;
    }
    for (int slot = 0; slot < cells.length; slot++) {
      if (!cells[slot].isEmpty()) {
        try {
          dropped.add(removeCell(slot));
        } catch (IllegalArgumentException | IllegalStateException failure) {
          // Keep the exact reference as an inert item; canonical stock remains unavailable in the
          // registry.
          deactivate(slot);
          ItemStack retained = cells[slot].copy();
          retained.set(
              DataComponents.CUSTOM_NAME, Component.literal("Cell needs ownership recovery"));
          dropped.add(retained);
          cells[slot] = ItemStack.EMPTY;
          diagnostic = failure.getMessage();
        }
      }
    }
    return List.copyOf(dropped);
  }

  private ItemStack recoveryStack(CompoundTag original) {
    ItemStack stack = new ItemStack(StorageContent.DRIVE_ITEM.get());
    stack.set(DataComponents.CUSTOM_NAME, Component.literal("Storage Drive (data needs recovery)"));
    CustomData.update(
        DataComponents.CUSTOM_DATA, stack, data -> data.put(RECOVERY, original.copy()));
    return stack;
  }

  public void restoreRecovery(ItemStack stack, HolderLookup.Provider registries) {
    var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    if (data.contains(RECOVERY)) {
      if (data.contains(RECOVERY, Tag.TAG_COMPOUND)) {
        loadAdditional(data.getCompound(RECOVERY), registries);
      } else {
        rejected = data.copy();
        diagnostic = "Malformed drive recovery envelope";
      }
      onLoad();
      setChanged();
    }
  }

  @Override
  public void onLoad() {
    super.onLoad();
    if (!(level instanceof ServerLevel) || rejected != null) return;
    for (int slot = 0; slot < cells.length; slot++) {
      if (!cells[slot].isEmpty()) {
        try {
          CellOwnership.rebind(cells[slot], registry(), location(slot));
        } catch (IllegalArgumentException | IllegalStateException failure) {
          diagnostic = failure.getMessage();
        }
      }
    }
  }

  @Override
  public void setRemoved() {
    if (level instanceof ServerLevel && rejected == null) {
      for (int slot = 0; slot < cells.length; slot++) {
        if (!cells[slot].isEmpty()) {
          deactivate(slot);
        }
      }
    }
    super.setRemoved();
  }

  private void deactivate(int slot) {
    try {
      var handle = CellHandleData.read(cells[slot]);
      if (handle != null) registry().availability(handle, location(slot), false);
    } catch (IllegalArgumentException | IllegalStateException failure) {
      diagnostic = failure.getMessage();
    }
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    if (rejected != null) {
      tag.put(REJECTED, rejected.copy());
      return;
    }
    tag.putInt("CellSchema", 1);
    var records = new ListTag();
    for (int slot = 0; slot < cells.length; slot++) {
      if (!cells[slot].isEmpty()) {
        var entry = new CompoundTag();
        entry.putInt("Slot", slot);
        entry.put("Stack", cells[slot].save(registries));
        records.add(entry);
      }
    }
    tag.put("CellSlots", records);
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.loadAdditional(tag, registries);
    rejected = null;
    diagnostic = "";
    java.util.Arrays.fill(cells, ItemStack.EMPTY);
    if (tag.contains(REJECTED)) {
      rejected =
          tag.contains(REJECTED, Tag.TAG_COMPOUND) ? tag.getCompound(REJECTED).copy() : tag.copy();
      diagnostic = "Preserved drive data needs recovery";
      return;
    }
    if (!tag.contains("CellSchema") && !tag.contains("CellSlots")) return;
    try {
      if (!tag.contains("CellSchema", Tag.TAG_INT)
          || tag.getInt("CellSchema") != 1
          || !tag.contains("CellSlots", Tag.TAG_LIST)) {
        throw new IllegalArgumentException("Unsupported drive cell schema");
      }
      var records = tag.getList("CellSlots", Tag.TAG_COMPOUND);
      if (records.size() != ((ListTag) tag.get("CellSlots")).size() || records.size() > 4)
        throw new IllegalArgumentException("Invalid drive slots");
      for (int i = 0; i < records.size(); i++) {
        var entry = records.getCompound(i);
        if (!entry.contains("Slot", Tag.TAG_INT) || !entry.contains("Stack", Tag.TAG_COMPOUND))
          throw new IllegalArgumentException("Malformed cell slot");
        int slot = entry.getInt("Slot");
        checkSlot(slot);
        if (!cells[slot].isEmpty()) throw new IllegalArgumentException("Duplicate cell slot");
        ItemStack cell =
            ItemStack.parse(registries, entry.getCompound("Stack"))
                .orElseThrow(() -> new IllegalArgumentException("Invalid saved cell stack"));
        if (!(cell.getItem() instanceof CellItem)
            || cell.getCount() != 1
            || CellHandleData.read(cell) == null)
          throw new IllegalArgumentException("Invalid placed cell");
        cells[slot] = cell;
      }
    } catch (IllegalArgumentException | IllegalStateException failure) {
      java.util.Arrays.fill(cells, ItemStack.EMPTY);
      rejected = tag.copy();
      diagnostic = failure.getMessage();
    }
  }

  @SuppressWarnings("PMD.CloseResource")
  private DeviceRegistry registry() {
    if (!(level instanceof ServerLevel server))
      throw new IllegalStateException("Drive requires server level");
    return DeviceSavedData.get(server.getServer()).registry();
  }

  private void requireUsable() {
    if (rejected != null || !diagnostic.isEmpty())
      throw new IllegalStateException("Drive unavailable: " + diagnostic);
    if (!(level instanceof ServerLevel))
      throw new IllegalStateException("Drive requires server level");
  }

  private static void checkSlot(int slot) {
    if (slot < 0 || slot >= 4) throw new IllegalArgumentException("Drive slot must be 0..3");
  }
}
