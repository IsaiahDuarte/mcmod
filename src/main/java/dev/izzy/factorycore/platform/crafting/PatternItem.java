package dev.izzy.factorycore.platform.crafting;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** Encoded crafting-table pattern. The item itself is an ordinary stack; it owns no resources. */
public final class PatternItem extends Item {
  public PatternItem() {
    super(new Item.Properties().stacksTo(1));
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
    var counts = new java.util.LinkedHashMap<String, Integer>();
    var names = new java.util.HashMap<String, Component>();
    for (var cell : contents.cells()) {
      if (cell.isEmpty()) continue;
      var id =
          net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(cell.getItem()).toString();
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
