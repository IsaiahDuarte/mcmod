package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.core.storage.FuelGenerator;
import dev.izzy.factorycore.platform.client.ui.PanelLayout;
import dev.izzy.factorycore.platform.client.ui.Panels;
import dev.izzy.factorycore.platform.client.ui.Rect;
import dev.izzy.factorycore.platform.power.FuelGeneratorMenu;
import java.text.NumberFormat;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Q142 (playthrough note 2): the Fuel Generator on the panel kit. The stored FE with a meter, the
 * FE given out last tick, the status in words, then the fuel slot, a bar for the burn left on the
 * current fuel and the container slot.
 */
public final class FuelGeneratorScreen extends AbstractContainerScreen<FuelGeneratorMenu> {
  private static final int STORED_Y = PanelLayout.BODY,
      METER_Y = STORED_Y + PanelLayout.LINE,
      OUTPUT_Y = METER_Y + 10,
      STATUS_Y = OUTPUT_Y + PanelLayout.LINE,
      BURN_LINE_Y = STATUS_Y + PanelLayout.LINE,
      BURN_X = FuelGeneratorMenu.FUEL_X + 22,
      BURN_Y = FuelGeneratorMenu.FUEL_Y + 5;

  public FuelGeneratorScreen(FuelGeneratorMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = PanelLayout.NARROW;
    imageHeight = FuelGeneratorMenu.HOTBAR_Y + PanelLayout.SLOT - 1 + PanelLayout.EDGE;
    inventoryLabelX = FuelGeneratorMenu.INVENTORY_X - 1;
    inventoryLabelY = FuelGeneratorMenu.INVENTORY_Y - 11;
  }

  private static String number(long value) {
    return NumberFormat.getIntegerInstance(Locale.ROOT).format(value);
  }

  /** The burn bar's panel-relative track, between the two slots' labels. */
  private int burnWidth() {
    return labelX() - 6 - BURN_X;
  }

  private int labelX() {
    return FuelGeneratorMenu.REMAINDER_X
        - 4
        - font.width(Component.translatable("gui.factorycore.generator.remainder"));
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
    Panels.panel(graphics, new Rect(leftPos, topPos, imageWidth, imageHeight));
    for (var slot : menu.slots) Panels.slot(graphics, leftPos + slot.x - 1, topPos + slot.y - 1);
    bar(
        graphics,
        new Rect(leftPos + PanelLayout.EDGE + 1, topPos + METER_Y, imageWidth - 2 * 8, 6),
        menu.energy(),
        FuelGenerator.CAPACITY);
    bar(
        graphics,
        new Rect(leftPos + BURN_X, topPos + BURN_Y, burnWidth(), 6),
        menu.burnRemaining(),
        menu.burnTotal());
  }

  /** An inset track filled to {@code value / of}. */
  private static void bar(GuiGraphics graphics, Rect track, long value, long of) {
    Panels.inset(graphics, track);
    int filled = of <= 0 ? 0 : (int) Math.min(track.width() - 2, (track.width() - 2) * value / of);
    if (filled > 0)
      Panels.meter(graphics, new Rect(track.x() + 1, track.y() + 1, filled, track.height() - 2));
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    int width = imageWidth - 2 * PanelLayout.EDGE;
    Panels.title(graphics, font, title, 0, 0, Panels.titleRoom(imageWidth));
    graphics.drawString(
        font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, Panels.TEXT, false);
    line(
        graphics,
        Component.translatable(
            "gui.factorycore.generator.stored",
            number(menu.energy()),
            number(FuelGenerator.CAPACITY)),
        STORED_Y,
        Panels.TEXT,
        width);
    line(
        graphics,
        Component.translatable(
            "gui.factorycore.generator.output",
            number(menu.output()),
            number(FuelGenerator.MAX_OUTPUT)),
        OUTPUT_Y,
        Panels.TEXT,
        width);
    // The burn left on the current fuel, under the status.
    var burn =
        menu.burnRemaining() > 0
            ? Component.translatable(
                "gui.factorycore.generator.burn",
                String.format(Locale.ROOT, "%.1f", menu.burnRemaining() / 20.0))
            : Component.translatable("gui.factorycore.generator.burn.none");
    line(graphics, burn, BURN_LINE_Y, Panels.MUTED, width);
    var status = menu.status();
    if (!status.equals("loading"))
      line(
          graphics,
          Component.translatable("status.factorycore.generator." + status),
          STATUS_Y,
          status.equals("burning") ? Panels.GOOD : Panels.WARN,
          width);
    graphics.drawString(
        font,
        Component.translatable("gui.factorycore.generator.fuel"),
        PanelLayout.EDGE + 1,
        FuelGeneratorMenu.FUEL_Y + 4,
        Panels.TEXT,
        false);
    graphics.drawString(
        font,
        Component.translatable("gui.factorycore.generator.remainder"),
        labelX(),
        FuelGeneratorMenu.REMAINDER_Y + 4,
        Panels.TEXT,
        false);
  }

  private void line(GuiGraphics graphics, Component text, int y, int color, int width) {
    graphics.drawString(
        font,
        font.plainSubstrByWidth(text.getString(), width),
        PanelLayout.EDGE + 1,
        y,
        color,
        false);
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
    super.render(graphics, mouseX, mouseY, partial);
    if (menu.getCarried().isEmpty()
        && hoveredSlot != null
        && !hoveredSlot.hasItem()
        && menu.slots.indexOf(hoveredSlot) < 2) {
      var help =
          menu.slots.indexOf(hoveredSlot) == 0
              ? Component.translatable(
                  "gui.factorycore.generator.fuel.help", FuelGenerator.PER_BURN_TICK)
              : Component.translatable("gui.factorycore.generator.remainder.help");
      graphics.renderTooltip(font, font.split(help, 200), mouseX, mouseY);
    } else renderTooltip(graphics, mouseX, mouseY);
  }
}
