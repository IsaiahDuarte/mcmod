package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.network.DeviceLamp;
import net.minecraft.ChatFormatting;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Appends the translated one-line purpose ({@code tooltip.factorycore.<item>.description}) and, for
 * blocks with a {@link DeviceLamp}, the lamp legend to Factory Core item tooltips. Items whose own
 * tooltip already explains them simply have no description key.
 */
@EventBusSubscriber(modid = FactoryCoreMod.MOD_ID, value = Dist.CLIENT)
public final class ItemDescriptions {
  private ItemDescriptions() {}

  /** Translation key of an item's purpose line. */
  public static String descriptionKey(String path) {
    return "tooltip." + FactoryCoreMod.MOD_ID + "." + path + ".description";
  }

  /** Lamp legend key for an item, or null unless it places a block with a {@link DeviceLamp}. */
  public static String lampLegendKey(Item item) {
    return item instanceof BlockItem block ? DeviceLamp.legend(block.getBlock()) : null;
  }

  @SubscribeEvent
  public static void tooltip(ItemTooltipEvent event) {
    var id = BuiltInRegistries.ITEM.getKey(event.getItemStack().getItem());
    if (!id.getNamespace().equals(FactoryCoreMod.MOD_ID)) return;
    var lines = event.getToolTip();
    // Insert after the name line so mod-specific detail lines keep their order below.
    int at = Math.min(1, lines.size());
    String description = descriptionKey(id.getPath());
    if (I18n.exists(description))
      lines.add(at++, Component.translatable(description).withStyle(ChatFormatting.GRAY));
    String legend = lampLegendKey(event.getItemStack().getItem());
    if (legend != null)
      lines.add(at, Component.translatable(legend).withStyle(ChatFormatting.DARK_GRAY));
  }
}
