package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.platform.storage.DriveMenu;
import dev.izzy.factorycore.platform.storage.DrivePackets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Four drive bays with fill bars above the player inventory; clicks act with the cursor item. */
public final class DriveScreen extends AbstractContainerScreen<DriveMenu> {
  private static final int ROW_WIDTH = 214;

  public DriveScreen(DriveMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = 230;
    imageHeight = DriveMenu.INVENTORY_Y + 82;
    inventoryLabelY = DriveMenu.INVENTORY_Y - 11;
  }

  @Override
  protected void init() {
    super.init();
    for (int bay = 0; bay < DrivePackets.BAYS; bay++) {
      int selected = bay;
      addRenderableWidget(
          new Button(
              leftPos + DriveMenu.BAY_X,
              topPos + bayTop(bay),
              ROW_WIDTH,
              24,
              Component.translatable("gui.factorycore.drive.bay", bay + 1),
              button -> actOnBay(selected),
              narration -> narration.get()) {
            @Override
            protected void renderWidget(
                GuiGraphics graphics, int mouseX, int mouseY, float partial) {
              if (isFocused()) graphics.renderOutline(getX(), getY(), width, height, 0xffffffff);
            }
          });
    }
  }

  private void actOnBay(int bay) {
    minecraft.gameMode.handleInventoryButtonClick(
        menu.containerId, Screen.hasShiftDown() ? bay + DrivePackets.BAYS : bay);
  }

  private static int bayTop(int bay) {
    return DriveMenu.BAY_Y + bay * DriveMenu.BAY_HEIGHT;
  }

  /** The bay under a screen point, or -1. */
  private int bayAt(double mouseX, double mouseY) {
    double x = mouseX - leftPos - DriveMenu.BAY_X, y = mouseY - topPos - DriveMenu.BAY_Y;
    if (x < 0 || x >= ROW_WIDTH || y < 0) return -1;
    int bay = (int) (y / DriveMenu.BAY_HEIGHT);
    return bay < DrivePackets.BAYS && y - bay * DriveMenu.BAY_HEIGHT < 24 ? bay : -1;
  }

  private static String amount(long value) {
    return String.format(Locale.ROOT, "%,d", value);
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
    graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff282c30);
    graphics.fill(
        leftPos + 4, topPos + 4, leftPos + imageWidth - 4, topPos + imageHeight - 4, 0xff44484d);
    int hovered = bayAt(mouseX, mouseY);
    for (int bay = 0; bay < DrivePackets.BAYS; bay++) {
      int x = leftPos + DriveMenu.BAY_X, y = topPos + bayTop(bay);
      graphics.fill(x, y, x + ROW_WIDTH, y + 24, bay == hovered ? 0xff3a4e60 : 0xff30373e);
      graphics.fill(x + 1, y + 1, x + 19, y + 19, 0xff151b20);
      var row = menu.view().bays().get(bay);
      if (row.item().isEmpty()) continue;
      graphics.renderItem(
          new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(row.item()))),
          x + 2,
          y + 2);
      int barX = x + 24, barY = y + 21, barWidth = ROW_WIDTH - 28;
      graphics.fill(barX, barY, barX + barWidth, barY + 2, 0xff151b20);
      if (!row.infinite() && row.capacity() > 0) {
        int filled = (int) Math.min(barWidth, (double) row.total() / row.capacity() * barWidth);
        if (row.total() > 0) filled = Math.max(1, filled);
        int color = filled * 10 >= barWidth * 9 ? 0xffd9534f : 0xff4fb6d9;
        graphics.fill(barX, barY, barX + filled, barY + 2, color);
      }
    }
    for (var slot : menu.slots) {
      graphics.fill(
          leftPos + slot.x - 1,
          topPos + slot.y - 1,
          leftPos + slot.x + 17,
          topPos + slot.y + 17,
          0xff151b20);
      graphics.fill(
          leftPos + slot.x,
          topPos + slot.y,
          leftPos + slot.x + 16,
          topPos + slot.y + 16,
          0xff30373e);
    }
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    graphics.drawString(font, title, 8, 6, 0xffffff, false);
    int strip = 8 + font.width(title) + 10;
    StatusStrip.draw(graphics, font, menu.deviceStatus(), strip, 6, imageWidth - 8 - strip);
    graphics.drawString(font, playerInventoryTitle, 8, inventoryLabelY, 0xd8dee9, false);
    for (int bay = 0; bay < DrivePackets.BAYS; bay++) {
      var row = menu.view().bays().get(bay);
      int x = DriveMenu.BAY_X + 24, y = bayTop(bay) + 3;
      if (row.item().isEmpty()) {
        graphics.drawString(
            font, Component.translatable("gui.factorycore.drive.empty"), x, y + 3, 0x9aa5b1, false);
        continue;
      }
      var name =
          new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(row.item())))
              .getHoverName();
      var heading = Component.translatable("gui.factorycore.drive.heading", name, row.types());
      graphics.drawString(font, heading, x, y - 1, 0xffffff, false);
      var text = amountLine(row);
      graphics.drawString(
          font,
          font.plainSubstrByWidth(text.getString(), ROW_WIDTH - 28),
          x,
          y + 9,
          0xffffff,
          false);
    }
    var view = menu.view();
    var status =
        view.detail().isEmpty()
            ? Component.translatable("gui.factorycore.drive.status." + view.status())
            : Component.literal(view.detail());
    var lines = font.split(status, ROW_WIDTH);
    for (int line = 0; line < Math.min(2, lines.size()); line++)
      graphics.drawString(
          font, lines.get(line), 8, bayTop(DrivePackets.BAYS) + 2 + line * 10, 0xe4bc83, false);
  }

  private static Component amountLine(DrivePackets.Bay row) {
    var unit =
        Component.translatable("gui.factorycore.drive.unit." + (row.fluid() ? "fluid" : "item"));
    return row.infinite()
        ? Component.translatable("gui.factorycore.drive.unlimited", amount(row.total()), unit)
        : Component.translatable(
            "gui.factorycore.drive.used", amount(row.total()), amount(row.capacity()), unit);
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
    super.render(graphics, mouseX, mouseY, partial);
    int bay = bayAt(mouseX, mouseY);
    if (bay >= 0) {
      var row = menu.view().bays().get(bay);
      var lines = new ArrayList<Component>();
      if (!row.item().isEmpty()) {
        lines.add(
            new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(row.item())))
                .getHoverName());
        lines.add(amountLine(row));
        lines.add(Component.translatable("gui.factorycore.drive.bay.full.help"));
      } else lines.add(Component.translatable("gui.factorycore.drive.bay.empty.help"));
      graphics.renderTooltip(font, List.copyOf(lines), Optional.empty(), mouseX, mouseY);
    } else renderTooltip(graphics, mouseX, mouseY);
  }
}
