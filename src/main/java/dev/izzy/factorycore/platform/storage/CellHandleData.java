package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.storage.DeviceRegistry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Only a lease is carried in the item; resource contents stay in the world registry. */
public final class CellHandleData {
  private static final String FIELD = "FactoryCoreCell";

  private CellHandleData() {}

  public static DeviceRegistry.Handle read(ItemStack stack) {
    CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    if (!data.contains(FIELD)) return null;
    if (!data.contains(FIELD, Tag.TAG_COMPOUND))
      throw new IllegalArgumentException("Invalid cell lease data");
    CompoundTag handle = data.getCompound(FIELD);
    if (!handle.contains("Schema", Tag.TAG_INT)
        || handle.getInt("Schema") != 1
        || !handle.hasUUID("World")
        || !handle.hasUUID("Backing")
        || !handle.contains("Generation", Tag.TAG_LONG)) {
      throw new IllegalArgumentException("Unsupported or malformed cell lease");
    }
    return new DeviceRegistry.Handle(
        handle.getUUID("World"), handle.getUUID("Backing"), handle.getLong("Generation"));
  }

  public static void write(ItemStack stack, DeviceRegistry.Handle handle) {
    CustomData.update(
        DataComponents.CUSTOM_DATA,
        stack,
        data -> {
          var tag = new CompoundTag();
          tag.putInt("Schema", 1);
          tag.putUUID("World", handle.world());
          tag.putUUID("Backing", handle.backing());
          tag.putLong("Generation", handle.generation());
          data.put(FIELD, tag);
        });
  }
}
