package dev.izzy.factorycore.platform;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Q77: a device's anvil name. Placing a named item leaves vanilla {@code CUSTOM_NAME} among the
 * block entity's kept components, which save with it; screens show it as their title. Blocks with a
 * loot table carry it back through {@link CarriedComponents}; leased devices, which build their own
 * drop, carry it with {@link #carry}. A Crafter turns its name into its label (ADR 0060) instead.
 */
public final class DeviceNames {
  private DeviceNames() {}

  /** The device's name, or null when it has none. */
  public static Component name(BlockEntity entity) {
    if (entity == null) return null;
    if (entity instanceof dev.izzy.factorycore.platform.crafting.PatternHolderEntity holder)
      return holder.label().isEmpty() ? null : Component.literal(holder.label());
    return entity.components().get(DataComponents.CUSTOM_NAME);
  }

  /** The device's name, or {@code fallback}. */
  public static Component title(BlockEntity entity, Component fallback) {
    var name = name(entity);
    return name == null ? fallback : name;
  }

  /** {@code drops} with the device's name on its own unnamed item (recovery items keep theirs). */
  public static List<ItemStack> carry(BlockEntity entity, List<ItemStack> drops) {
    var name = entity.components().get(DataComponents.CUSTOM_NAME);
    if (name == null) return drops;
    var item = entity.getBlockState().getBlock().asItem();
    var named = new ArrayList<ItemStack>(drops.size());
    for (var drop : drops) {
      if (drop.is(item) && !drop.has(DataComponents.CUSTOM_NAME)) {
        drop = drop.copy();
        drop.set(DataComponents.CUSTOM_NAME, name);
      }
      named.add(drop);
    }
    return named;
  }
}
