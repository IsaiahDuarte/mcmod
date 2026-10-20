package dev.izzy.factorycore.platform;

import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.platform.upgrade.ThroughputContent;
import java.util.List;
import java.util.function.Function;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;

/**
 * Item form of a device that holds a canonical lease. It stacks to one, and placing it always
 * consumes it, even in creative, so a lease can never be duplicated. Its tooltip is data: a list of
 * lines, optionally followed by the installed throughput tier read from the item's saved data.
 */
public final class LeasedBlockItem extends BlockItem {
  /** Appends tooltip lines for one stack. */
  @FunctionalInterface
  public interface Tooltip {
    void append(ItemStack stack, List<Component> lines);

    default Tooltip then(Tooltip next) {
      return (stack, lines) -> {
        append(stack, lines);
        next.append(stack, lines);
      };
    }

    /** Fixed translated lines. */
    static Tooltip lines(String... keys) {
      return (stack, lines) -> {
        for (var key : keys) lines.add(Component.translatable(key));
      };
    }

    /**
     * The installed throughput tier stored under {@code field} in the item's custom data, or an
     * "unreadable" line when that data cannot be decoded. Base tier adds nothing.
     */
    static Tooltip throughput(String field, Function<Tag, ThroughputTier> reader) {
      return (stack, lines) -> {
        var custom = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!custom.contains(field)) return;
        try {
          var tier = reader.apply(custom.get(field));
          if (tier != ThroughputTier.BASE)
            lines.add(
                Component.translatable(
                    "tooltip.factorycore.throughput_installed", ThroughputContent.name(tier)));
        } catch (IllegalArgumentException unreadable) {
          lines.add(Component.translatable("tooltip.factorycore.throughput_unreadable"));
        }
      };
    }
  }

  private final Tooltip tooltip;

  public LeasedBlockItem(Block block, Tooltip tooltip) {
    super(block, new Properties().stacksTo(1));
    this.tooltip = tooltip;
  }

  @Override
  public void appendHoverText(
      ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
    super.appendHoverText(stack, context, lines, flag);
    tooltip.append(stack, lines);
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
