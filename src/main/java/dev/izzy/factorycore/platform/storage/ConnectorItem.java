package dev.izzy.factorycore.platform.storage;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;

public final class ConnectorItem extends BlockItem {
  public ConnectorItem(Block block) {
    super(block, new Properties().stacksTo(1));
  }

  @Override
  public void appendHoverText(
      ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
    super.appendHoverText(stack, context, tooltip, flag);
    tooltip.add(Component.translatable("tooltip.factorycore.connector"));
    tooltip.add(Component.translatable("tooltip.factorycore.connector_controls"));
    tooltip.add(Component.translatable("tooltip.factorycore.connector_access"));
    tooltip.add(Component.translatable("tooltip.factorycore.connector_priority"));
    tooltip.add(Component.translatable("tooltip.factorycore.connector_unique"));
    throughputTooltip(stack, tooltip);
  }

  /** Shows a portable port's installed upgrade; unreadable data adds nothing. */
  static void throughputTooltip(ItemStack stack, List<Component> tooltip) {
    var custom =
        stack
            .getOrDefault(
                net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY)
            .copyTag();
    if (!custom.contains(ConnectorData.FIELD)) return;
    try {
      var tier = ConnectorData.read(custom.get(ConnectorData.FIELD)).tier();
      if (tier != dev.izzy.factorycore.core.resource.ThroughputTier.BASE)
        tooltip.add(
            Component.translatable(
                "tooltip.factorycore.throughput_installed",
                dev.izzy.factorycore.platform.upgrade.ThroughputContent.name(tier)));
    } catch (IllegalArgumentException unreadable) {
      // Recovery data is inspected in-world; a tooltip never guesses an upgrade.
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
