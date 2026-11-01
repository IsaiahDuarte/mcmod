package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.platform.client.ui.Panels;
import dev.izzy.factorycore.platform.client.ui.Rect;
import dev.izzy.factorycore.platform.storage.PortableStorageMenu;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Q32: the Portable Storage screen. Shown stacks carry short counts ({@code 1.2k}); the tooltip
 * gives the exact amount. The arrows page through the cell's contents.
 */
public final class PortableStorageScreen extends AbstractContainerScreen<PortableStorageMenu> {
  private Button previous;
  private Button next;

  public PortableStorageScreen(PortableStorageMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = 176;
    imageHeight = PortableStorageMenu.HOTBAR_Y + 18 + 7;
    inventoryLabelY = PortableStorageMenu.INVENTORY_Y - 11;
  }

  @Override
  protected void init() {
    super.init();
    int y = topPos + PortableStorageMenu.CELL_Y - 1;
    previous =
        addRenderableWidget(
            Button.builder(Component.literal("<"), button -> page(0))
                .bounds(leftPos + imageWidth - 7 - 40, y, 18, 18)
                .build());
    next =
        addRenderableWidget(
            Button.builder(Component.literal(">"), button -> page(1))
                .bounds(leftPos + imageWidth - 7 - 18, y, 18, 18)
                .build());
  }

  private void page(int button) {
    if (minecraft != null && minecraft.gameMode != null)
      minecraft.gameMode.handleInventoryButtonClick(menu.containerId, button);
  }

  @Override
  protected void containerTick() {
    super.containerTick();
    previous.active = menu.page() > 0;
    next.active = menu.page() + 1 < menu.pages();
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
    Panels.panel(graphics, new Rect(leftPos, topPos, imageWidth, imageHeight));
    for (var slot : menu.slots) Panels.slot(graphics, leftPos + slot.x - 1, topPos + slot.y - 1);
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    Panels.title(graphics, font, title, 0, 0, imageWidth - 16);
    graphics.drawString(
        font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, Panels.TEXT, false);
    int x = PortableStorageMenu.CELL_X + 22, y = PortableStorageMenu.CELL_Y + 5;
    var status =
        menu.hasCell()
            ? Component.translatable(
                "gui.factorycore.portable_storage.page", menu.page() + 1, menu.pages())
            : Component.translatable("gui.factorycore.portable_storage.no_cell");
    graphics.drawString(
        font,
        font.plainSubstrByWidth(status.getString(), imageWidth - x - 50),
        x,
        y,
        Panels.MUTED,
        false);
  }

  private static boolean shown(Slot slot) {
    return slot.index >= 1 && slot.index <= PortableStorageMenu.SHOWN;
  }

  @Override
  protected void renderSlot(GuiGraphics graphics, Slot slot) {
    if (!shown(slot) || !slot.hasItem()) {
      super.renderSlot(graphics, slot);
      return;
    }
    var stack = slot.getItem();
    graphics.renderItem(stack, slot.x, slot.y);
    graphics.renderItemDecorations(
        font, stack, slot.x, slot.y, CountText.compact(Integer.toString(stack.getCount())));
  }

  @Override
  protected List<Component> getTooltipFromContainerItem(ItemStack stack) {
    var lines = new java.util.ArrayList<>(super.getTooltipFromContainerItem(stack));
    if (hoveredSlot != null && shown(hoveredSlot))
      lines.add(
          Component.translatable(
                  "gui.factorycore.portable_storage.stored",
                  String.format(java.util.Locale.ROOT, "%,d", stack.getCount()))
              .withStyle(net.minecraft.ChatFormatting.GRAY));
    return lines;
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
    super.render(graphics, mouseX, mouseY, partial);
    renderTooltip(graphics, mouseX, mouseY);
  }
}
