package dev.izzy.factorycore.platform.network;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/** Q29: the cable item; a coloured one is named for its colour ("Red Network Cable"). */
public final class CableItem extends BlockItem {
  public CableItem(Block block, Properties properties) {
    super(block, properties);
  }

  @Override
  public Component getName(ItemStack stack) {
    var color = NetworkCableBlock.color(stack);
    return color == CableColor.NONE
        ? super.getName(stack)
        : Component.translatable("item.factorycore.network_cable." + color.getSerializedName());
  }
}
