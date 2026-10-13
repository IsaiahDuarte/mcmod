package dev.izzy.factorycore.platform.storage;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Block;

public final class TerminalItem extends BlockItem {
  public TerminalItem(Block block, Properties properties) {
    super(block, properties);
  }

  @Override
  public void appendHoverText(
      ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
    super.appendHoverText(stack, context, tooltip, flag);
    tooltip.add(Component.translatable("tooltip.factorycore.storage_terminal"));
  }
}
