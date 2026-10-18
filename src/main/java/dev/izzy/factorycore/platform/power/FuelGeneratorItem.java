package dev.izzy.factorycore.platform.power;

import dev.izzy.factorycore.core.storage.FuelGenerator;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

public final class FuelGeneratorItem extends BlockItem {
  public FuelGeneratorItem(Block block) {
    super(block, new Item.Properties());
  }

  @Override
  public void appendHoverText(
      ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
    var state = stack.get(FuelGeneratorContent.STATE.get());
    lines.add(
        Component.translatable(
                "tooltip.factorycore.generator_energy",
                state == null ? 0 : state.energy(),
                FuelGenerator.CAPACITY)
            .withStyle(ChatFormatting.GRAY));
    lines.add(
        Component.translatable(
                "tooltip.factorycore.generator_rate",
                FuelGenerator.PER_BURN_TICK,
                FuelGenerator.MAX_OUTPUT)
            .withStyle(ChatFormatting.GRAY));
  }
}
