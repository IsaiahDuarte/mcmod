package dev.izzy.factorycore.platform.storage;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;

public final class BufferedInterfaceItem extends BlockItem {
  public BufferedInterfaceItem(Block block) {
    super(block, new Properties().stacksTo(1));
  }

  @Override
  public void appendHoverText(
      ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
    super.appendHoverText(stack, context, tooltip, flag);
    tooltip.add(Component.translatable("tooltip.factorycore.buffer_slots"));
    tooltip.add(Component.translatable("tooltip.factorycore.buffer_move"));
    tooltip.add(Component.translatable("tooltip.factorycore.buffer_import"));
    tooltip.add(Component.translatable("tooltip.factorycore.buffer_cleanup"));
    tooltip.add(Component.translatable("tooltip.factorycore.buffer_filter"));
    tooltip.add(Component.translatable("tooltip.factorycore.buffer_rate"));
    tooltip.add(Component.translatable("tooltip.factorycore.buffer_export_stock"));
    var custom =
        stack
            .getOrDefault(
                net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY)
            .copyTag();
    if (custom.contains(BufferData.FIELD))
      try {
        var tier = BufferData.read(custom.get(BufferData.FIELD)).tier();
        if (tier != dev.izzy.factorycore.core.resource.ThroughputTier.BASE)
          tooltip.add(
              Component.translatable(
                  "tooltip.factorycore.throughput_installed",
                  dev.izzy.factorycore.platform.upgrade.ThroughputContent.name(tier)));
      } catch (IllegalArgumentException unreadable) {
        tooltip.add(Component.translatable("tooltip.factorycore.throughput_unreadable"));
      }
  }

  @Override
  public InteractionResult place(BlockPlaceContext context) {
    var result = super.place(context);
    if (result.consumesAction()
        && !context.getLevel().isClientSide()
        && context.getPlayer() != null
        && context.getPlayer().getAbilities().instabuild) context.getItemInHand().shrink(1);
    return result;
  }
}
