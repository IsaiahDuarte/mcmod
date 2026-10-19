package dev.izzy.factorycore.platform.storage;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;

public final class MachineInterfaceItem extends BlockItem {
  public MachineInterfaceItem(Block block) {
    super(block, new Properties().stacksTo(1));
  }

  @Override
  public void appendHoverText(
      ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
    super.appendHoverText(stack, context, tooltip, flag);
    tooltip.add(Component.translatable("tooltip.factorycore.machine"));
    tooltip.add(Component.translatable("tooltip.factorycore.machine_controls"));
    tooltip.add(Component.translatable("tooltip.factorycore.machine_access"));
    tooltip.add(Component.translatable("tooltip.factorycore.machine_priority"));
    tooltip.add(Component.translatable("tooltip.factorycore.machine_unique"));
    ConnectorItem.throughputTooltip(stack, tooltip);
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
