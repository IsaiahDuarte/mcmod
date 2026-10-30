package dev.izzy.factorycore.platform.crafting;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/** Encoded processing pattern; an ordinary stack that owns no resources. */
public final class ProcessingPatternItem extends Item {
  public ProcessingPatternItem() {
    super(new Item.Properties().stacksTo(1));
  }

  /** Q4: sneak-use in the air wipes the pattern back to a blank. */
  @Override
  public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
    return PatternItem.wipe(level, player, hand);
  }

  @Override
  public void appendHoverText(
      ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
    var contents = stack.get(CraftingContent.PROCESSING.get());
    if (contents == null) {
      lines.add(
          Component.translatable("tooltip.factorycore.pattern_invalid")
              .withStyle(ChatFormatting.RED));
      return;
    }
    for (var output : contents.outputs())
      lines.add(
          Component.translatable(
                  "tooltip.factorycore.pattern_output", output.getCount(), output.getHoverName())
              .withStyle(ChatFormatting.GRAY));
    for (var output : contents.fluidOutputs())
      lines.add(
          Component.translatable(
                  "tooltip.factorycore.pattern_fluid_output",
                  output.getAmount(),
                  output.getHoverName())
              .withStyle(ChatFormatting.GRAY));
    for (var input : contents.inputs())
      lines.add(
          Component.translatable(
                  "tooltip.factorycore.pattern_input", input.getCount(), input.getHoverName())
              .withStyle(ChatFormatting.DARK_GRAY));
    for (var input : contents.fluidInputs())
      lines.add(
          Component.translatable(
                  "tooltip.factorycore.pattern_fluid_input",
                  input.getAmount(),
                  input.getHoverName())
              .withStyle(ChatFormatting.DARK_GRAY));
    lines.add(
        Component.translatable("tooltip.factorycore.processing_exclusive")
            .withStyle(ChatFormatting.YELLOW));
    if (flag.isAdvanced())
      lines.add(
          Component.translatable(
                  "tooltip.factorycore.pattern_id", contents.id().toString(), contents.version())
              .withStyle(ChatFormatting.DARK_GRAY));
  }
}
