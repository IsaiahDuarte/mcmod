package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.platform.client.ui.PanelLayout;
import dev.izzy.factorycore.platform.client.ui.Panels;
import dev.izzy.factorycore.platform.client.ui.Rect;
import dev.izzy.factorycore.platform.storage.EnergyBankMenu;
import java.text.NumberFormat;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Q20: the Energy Bank on the panel kit (ADR 0064): its charge with a meter, the FE that went in
 * and out during the last tick, its rate limit and network state, and the module slot that upgrades
 * it in place.
 */
public final class EnergyBankScreen extends AbstractContainerScreen<EnergyBankMenu> {
  private static final int TEXT_WIDTH = EnergyBankMenu.MODULE_X - PanelLayout.EDGE - 4;
  private static final int CHARGE_Y = PanelLayout.BODY,
      METER_Y = CHARGE_Y + PanelLayout.LINE,
      FLOW_Y = METER_Y + 10,
      RATE_Y = FLOW_Y + PanelLayout.LINE,
      NETWORK_Y = RATE_Y + PanelLayout.LINE;

  public EnergyBankScreen(EnergyBankMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = PanelLayout.NARROW;
    imageHeight = EnergyBankMenu.HOTBAR_Y + PanelLayout.SLOT - 1 + PanelLayout.EDGE;
    inventoryLabelX = EnergyBankMenu.INVENTORY_X - 1;
    inventoryLabelY = EnergyBankMenu.INVENTORY_Y - 11;
  }

  private static String number(long value) {
    return NumberFormat.getIntegerInstance(Locale.ROOT).format(value);
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
    Panels.panel(graphics, new Rect(leftPos, topPos, imageWidth, imageHeight));
    for (var slot : menu.slots) Panels.slot(graphics, leftPos + slot.x - 1, topPos + slot.y - 1);
    var track = new Rect(leftPos + PanelLayout.EDGE + 1, topPos + METER_Y, imageWidth - 2 * 8, 6);
    Panels.inset(graphics, track);
    long capacity = menu.tier().capacity();
    int filled =
        capacity <= 0
            ? 0
            : (int) Math.min(track.width() - 2, (track.width() - 2) * menu.energy() / capacity);
    if (filled > 0)
      Panels.meter(graphics, new Rect(track.x() + 1, track.y() + 1, filled, track.height() - 2));
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    // The module label sits left of its slot, on the network line's row; that line stops before it.
    var moduleLabel = Component.translatable("gui.factorycore.bank.module");
    int labelX = EnergyBankMenu.MODULE_X - 4 - font.width(moduleLabel);
    Panels.title(graphics, font, title, 0, 0, Panels.titleRoom(imageWidth));
    graphics.drawString(
        font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, Panels.TEXT, false);
    line(
        graphics,
        Component.translatable(
            "gui.factorycore.bank.charge", number(menu.energy()), number(menu.tier().capacity())),
        CHARGE_Y,
        Panels.TEXT,
        imageWidth - 2 * PanelLayout.EDGE);
    line(
        graphics,
        Component.translatable(
            "gui.factorycore.bank.flow", number(menu.input()), number(menu.output())),
        FLOW_Y,
        Panels.TEXT,
        TEXT_WIDTH);
    line(
        graphics,
        Component.translatable("gui.factorycore.bank.rate", number(menu.tier().rate())),
        RATE_Y,
        Panels.MUTED,
        TEXT_WIDTH);
    line(
        graphics,
        Component.translatable(
            "gui.factorycore.bank.network",
            Component.translatable("status.factorycore." + menu.networkKey())),
        NETWORK_Y,
        menu.networkStatus() == dev.izzy.factorycore.core.network.NetworkTopology.Status.VALID
            ? Panels.GOOD
            : Panels.WARN,
        labelX - PanelLayout.EDGE - 5);
    graphics.drawString(font, moduleLabel, labelX, EnergyBankMenu.MODULE_Y + 4, Panels.TEXT, false);
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
    var module = menu.slots.getFirst();
    if (hoveredSlot == module && !module.hasItem() && menu.getCarried().isEmpty())
      graphics.renderTooltip(
          font,
          font.split(Component.translatable("gui.factorycore.bank.module.help"), 200),
          mouseX,
          mouseY);
    else renderTooltip(graphics, mouseX, mouseY);
  }
}
