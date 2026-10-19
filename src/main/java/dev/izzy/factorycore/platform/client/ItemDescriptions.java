package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.platform.FactoryCoreMod;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Appends the translated one-line purpose ({@code tooltip.factorycore.<item>.description}) and, for
 * devices with a status lamp, the lamp legend to Factory Core item tooltips. Items whose own
 * tooltip already explains them simply have no description key.
 */
@EventBusSubscriber(modid = FactoryCoreMod.MOD_ID, value = Dist.CLIENT)
public final class ItemDescriptions {
  private static final Set<String> LAMP_DEVICES =
      Set.of("network_controller", "gateway", "wireless_access_point");

  private ItemDescriptions() {}

  /** Translation key of an item's purpose line. */
  public static String descriptionKey(String path) {
    return "tooltip." + FactoryCoreMod.MOD_ID + "." + path + ".description";
  }

  /** Lamp legend key for a device item path, or null when it has no status lamp. */
  public static String lampLegendKey(String path) {
    if (path.equals("program_controller")) return "tooltip.factorycore.lamp_legend_program";
    if (path.equals("wireless_link_endpoint")) return "tooltip.factorycore.lamp_legend_link";
    return LAMP_DEVICES.contains(path) ? "tooltip.factorycore.lamp_legend" : null;
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
    String legend = lampLegendKey(id.getPath());
    if (legend != null)
      lines.add(at, Component.translatable(legend).withStyle(ChatFormatting.DARK_GRAY));
  }
}
