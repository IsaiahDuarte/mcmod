package dev.izzy.factorycore.platform.crafting;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/** Encoded crafting-table pattern. The item itself is an ordinary stack; it owns no resources. */
public final class PatternItem extends Item {
  public PatternItem() {
    super(new Item.Properties().stacksTo(1));
  }

  /** Q4: sneak-use in the air wipes the pattern back to a blank. */
  @Override
  public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
    return PatternItem.wipe(level, player, hand);
  }

  /** Sneak-use in the air replaces an encoded pattern (either kind) with one Blank Pattern. */
  static InteractionResultHolder<ItemStack> wipe(Level level, Player player, InteractionHand hand) {
    var held = player.getItemInHand(hand);
    if (!player.isShiftKeyDown()) return InteractionResultHolder.pass(held);
    if (level.isClientSide()) return InteractionResultHolder.success(held);
    return InteractionResultHolder.consume(new ItemStack(CraftingContent.BLANK_PATTERN.get()));
  }

  @Override
  public void appendHoverText(
      ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
    var contents = stack.get(CraftingContent.PATTERN.get());
    if (contents == null) {
      lines.add(
          Component.translatable("tooltip.factorycore.pattern_invalid")
              .withStyle(ChatFormatting.RED));
      return;
    }
    var output = contents.output();
    lines.add(
        Component.translatable(
                "tooltip.factorycore.pattern_output", output.getCount(), output.getHoverName())
            .withStyle(ChatFormatting.GRAY));
    var counts = new LinkedHashMap<String, Integer>();
    var names = new HashMap<String, Component>();
    for (var cell : contents.cells()) {
      if (cell.isEmpty()) continue;
      var id = BuiltInRegistries.ITEM.getKey(cell.getItem()).toString();
      counts.merge(id, 1, Integer::sum);
      names.putIfAbsent(id, cell.getHoverName());
    }
    for (var entry : counts.entrySet())
      lines.add(
          Component.translatable(
                  "tooltip.factorycore.pattern_input", entry.getValue(), names.get(entry.getKey()))
              .withStyle(ChatFormatting.DARK_GRAY));
    if (flag.isAdvanced())
      lines.add(
          Component.translatable(
                  "tooltip.factorycore.pattern_id", contents.id().toString(), contents.version())
              .withStyle(ChatFormatting.DARK_GRAY));
  }
}
