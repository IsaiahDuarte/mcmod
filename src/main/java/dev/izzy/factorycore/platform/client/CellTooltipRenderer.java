package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.storage.CellSummary;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;

/**
 * Q72: a cell's largest stored resources in its tooltip, as up to eight slots in a row with their
 * amounts (fluids in mB), like a pattern's recipe ({@link PatternTooltipRenderer}).
 */
@EventBusSubscriber(modid = FactoryCoreMod.MOD_ID, value = Dist.CLIENT)
public final class CellTooltipRenderer implements ClientTooltipComponent {
  private static final int SLOT = 18;
  private static final int SLOT_COLOR = 0xFF8B8B8B, SLOT_SHADOW = 0xFF373737;

  private final CellSummary summary;

  private CellTooltipRenderer(CellSummary summary) {
    this.summary = summary;
  }

  @SubscribeEvent
  public static void factories(RegisterClientTooltipComponentFactoriesEvent event) {
    event.register(CellSummary.class, CellTooltipRenderer::new);
  }

  @Override
  public int getHeight() {
    return SLOT + 2;
  }

  @Override
  public int getWidth(Font font) {
    return summary.entries().size() * SLOT;
  }

  @Override
  public void renderImage(Font font, int x, int y, GuiGraphics graphics) {
    for (int index = 0; index < summary.entries().size(); index++) {
      var entry = summary.entries().get(index);
      int left = x + index * SLOT;
      graphics.fill(left, y, left + SLOT, y + SLOT, SLOT_SHADOW);
      graphics.fill(left + 1, y + 1, left + SLOT, y + SLOT, SLOT_COLOR);
      if (entry.fluid()) {
        if (!ResourceIcons.renderFluid(graphics, entry.id(), left + 1, y + 1))
          graphics.renderItem(new ItemStack(Items.BUCKET), left + 1, y + 1);
      } else {
        var id = ResourceLocation.tryParse(entry.id());
        if (id != null && BuiltInRegistries.ITEM.containsKey(id))
          graphics.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(id)), left + 1, y + 1);
      }
      // The amount, drawn where an item's count goes.
      var amount = CountText.compact(Long.toString(entry.amount()));
      graphics.pose().pushPose();
      graphics.pose().translate(0, 0, 200);
      graphics.drawString(
          font, amount, left + SLOT - 1 - font.width(amount), y + SLOT - 9, 0xFFFFFFFF, true);
      graphics.pose().popPose();
    }
  }
}
