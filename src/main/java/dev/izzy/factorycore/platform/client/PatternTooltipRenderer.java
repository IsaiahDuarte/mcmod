package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.PatternTooltip;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.IItemDecorator;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.neoforged.neoforge.client.event.RegisterItemDecorationsEvent;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Ergonomics Q68: draws a pattern's recipe in its tooltip, as slots: a crafting pattern's 3×3 grid
 * (cells with substitutes cycle through them once a second), an arrow and the output; a processing
 * pattern's inputs (items, then fluids; three per row) and its outputs in a column, with counts
 * (fluids in mB). While Shift is held, pattern icons in any screen show their output instead.
 */
@EventBusSubscriber(modid = FactoryCoreMod.MOD_ID, value = Dist.CLIENT)
public final class PatternTooltipRenderer implements ClientTooltipComponent {
  private static final int SLOT = 18, ARROW = 16, GAP = 3;
  private static final int SLOT_COLOR = 0xFF8B8B8B, SLOT_SHADOW = 0xFF373737;

  /** One drawn cell: an item (with its alternatives) or a fluid. */
  private record Cell(List<ItemStack> items, FluidStack fluid) {}

  private final List<Cell> inputs = new ArrayList<>();
  private final List<Cell> outputs = new ArrayList<>();
  private final int columns;

  private PatternTooltipRenderer(PatternTooltip tooltip) {
    if (tooltip.processing()) {
      for (var stack : tooltip.inputs()) inputs.add(new Cell(List.of(stack), null));
      for (var fluid : tooltip.fluidInputs()) inputs.add(new Cell(List.of(), fluid));
      columns = Math.max(1, Math.min(3, inputs.size()));
    } else {
      for (int cell = 0; cell < tooltip.inputs().size(); cell++) {
        var options = new ArrayList<ItemStack>();
        options.add(tooltip.inputs().get(cell));
        if (cell < tooltip.alternatives().size())
          for (var id : tooltip.alternatives().get(cell)) {
            var location = ResourceLocation.tryParse(id);
            var item = location == null ? Items.AIR : BuiltInRegistries.ITEM.get(location);
            if (item != Items.AIR) options.add(new ItemStack(item));
          }
        inputs.add(new Cell(options, null));
      }
      columns = 3;
    }
    for (var stack : tooltip.outputs()) outputs.add(new Cell(List.of(stack), null));
    for (var fluid : tooltip.fluidOutputs()) outputs.add(new Cell(List.of(), fluid));
  }

  @SubscribeEvent
  public static void factories(RegisterClientTooltipComponentFactoriesEvent event) {
    event.register(PatternTooltip.class, PatternTooltipRenderer::new);
  }

  /** Shift held: a pattern icon shows its output over itself. */
  @SubscribeEvent
  public static void decorations(RegisterItemDecorationsEvent event) {
    IItemDecorator output =
        (graphics, font, stack, x, y) -> {
          if (!Screen.hasShiftDown()) return false;
          var made = stack.getTooltipImage().orElse(null);
          if (!(made instanceof PatternTooltip tooltip) || tooltip.primaryOutput().isEmpty())
            return false;
          graphics.pose().pushPose();
          graphics.pose().translate(0, 0, 100);
          graphics.renderItem(tooltip.primaryOutput(), x, y);
          graphics.pose().popPose();
          return true;
        };
    event.register(CraftingContent.CRAFTING_PATTERN.get(), output);
    event.register(CraftingContent.PROCESSING_PATTERN.get(), output);
  }

  private int rows(List<Cell> cells, int perRow) {
    return Math.max(1, (cells.size() + perRow - 1) / perRow);
  }

  @Override
  public int getHeight() {
    return Math.max(rows(inputs, columns), outputs.size()) * SLOT + 2;
  }

  @Override
  public int getWidth(Font font) {
    return columns * SLOT + GAP + ARROW + GAP + SLOT;
  }

  @Override
  public void renderImage(Font font, int x, int y, GuiGraphics graphics) {
    for (int index = 0; index < inputs.size(); index++)
      cell(
          font,
          graphics,
          inputs.get(index),
          x + index % columns * SLOT,
          y + index / columns * SLOT);
    int arrowX = x + columns * SLOT + GAP;
    int middle = y + Math.max(rows(inputs, columns), outputs.size()) * SLOT / 2 - 4;
    graphics.drawString(font, "→", arrowX + 4, middle, 0xFFFFFFFF, true);
    int outputX = arrowX + ARROW + GAP;
    int outputY = y + (Math.max(rows(inputs, columns), outputs.size()) - outputs.size()) * SLOT / 2;
    for (int index = 0; index < outputs.size(); index++)
      cell(font, graphics, outputs.get(index), outputX, outputY + index * SLOT);
  }

  private static void cell(Font font, GuiGraphics graphics, Cell cell, int x, int y) {
    graphics.fill(x, y, x + SLOT, y + SLOT, SLOT_SHADOW);
    graphics.fill(x + 1, y + 1, x + SLOT, y + SLOT, SLOT_COLOR);
    if (cell.fluid() != null) {
      var id = BuiltInRegistries.FLUID.getKey(cell.fluid().getFluid()).toString();
      if (!ResourceIcons.renderFluid(graphics, id, x + 1, y + 1)) {
        var bucket = cell.fluid().getFluid().getBucket();
        if (bucket != Items.AIR) graphics.renderItem(new ItemStack(bucket), x + 1, y + 1);
      }
      // The amount in mB, drawn where an item's count goes.
      var amount = CountText.compact(Integer.toString(cell.fluid().getAmount()));
      graphics.pose().pushPose();
      graphics.pose().translate(0, 0, 200);
      graphics.drawString(
          font, amount, x + SLOT - 1 - font.width(amount), y + SLOT - 9, 0xFFFFFFFF, true);
      graphics.pose().popPose();
      return;
    }
    if (cell.items().isEmpty()) return;
    // Substitutes cycle once a second; the recorded item comes first.
    var shown = cell.items().get(Math.toIntExact(Util.getMillis() / 1000 % cell.items().size()));
    if (shown.isEmpty()) return;
    graphics.renderItem(shown, x + 1, y + 1);
    graphics.renderItemDecorations(font, shown, x + 1, y + 1);
  }
}
