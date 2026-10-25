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
public final class DriveScreen extends AbstractContainerScreen<DriveMenu>
    implements FilterDropTarget {
  private static final int ROW_WIDTH = 214;

  /** B3: the bay whose partition panel is open, or -1 for the bay list. */
  private int filterBay = -1, selectedRule;

  private boolean typing;
  private final List<Button> bayButtons = new ArrayList<>(), filterButtons = new ArrayList<>();
  private FilterSlots filterSlots;
  private net.minecraft.client.gui.components.EditBox filterId;
  private Button back, mode, addById, addId, addTag, clear;

  /** B4: the drive's priority; Enter saves it. */
  private net.minecraft.client.gui.components.EditBox priority;

  private int priorityShown = Integer.MIN_VALUE;

  public DriveScreen(DriveMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = 230;
    imageHeight = DriveMenu.INVENTORY_Y + 82;
    inventoryLabelY = DriveMenu.INVENTORY_Y - 11;
  }

  @Override
  protected void init() {
    super.init();
    bayButtons.clear();
    filterButtons.clear();
    // Added before the bay rows so a click on the small button opens the panel, not the bay.
    for (int bay = 0; bay < DrivePackets.BAYS; bay++) {
      int selected = bay;
      filterButtons.add(
          addRenderableWidget(
              Button.builder(
                      Component.literal("⚙"),
                      button -> {
                        filterBay = selected;
                        typing = false;
                        selectedRule = 0;
                      })
                  .bounds(
                      leftPos + DriveMenu.BAY_X + ROW_WIDTH - 18, topPos + bayTop(bay) + 2, 16, 16)
                  .tooltip(
                      net.minecraft.client.gui.components.Tooltip.create(
                          Component.translatable("gui.factorycore.drive.filter.help")))
                  .build()));
    }
    int top = DriveMenu.BAY_Y;
    back =
        addRenderableWidget(
            Button.builder(
                    Component.translatable("gui.factorycore.drive.filter.back"),
                    b -> filterBay = -1)
                .bounds(leftPos + 170, topPos + top, 52, 14)
                .build());
    mode =
        addRenderableWidget(
            Button.builder(Component.empty(), b -> filter(DrivePackets.FilterCommand.MODE, 0, ""))
                .bounds(leftPos + 8, topPos + top + 16, 214, 16)
                .tooltip(
                    net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("gui.factorycore.drive.filter.mode.help")))
                .build());
    addById =
        addRenderableWidget(
            Button.builder(
                    Component.translatable("gui.factorycore.buffer.add_by_id"), b -> typing = true)
                .bounds(leftPos + 8, topPos + top + 34, 96, 16)
                .build());
    filterId =
        addRenderableWidget(
            new net.minecraft.client.gui.components.EditBox(
                font,
                leftPos + 8,
                topPos + top + 34,
                120,
                16,
                Component.translatable("gui.factorycore.buffer.id")));
    filterId.setMaxLength(256);
    addId =
        addRenderableWidget(
            Button.builder(
                    Component.translatable("gui.factorycore.machine.add_id"),
                    b -> filter(DrivePackets.FilterCommand.REGISTRY, 0, filterId.getValue()))
                .bounds(leftPos + 132, topPos + top + 34, 44, 16)
                .build());
    addTag =
        addRenderableWidget(
            Button.builder(
                    Component.translatable("gui.factorycore.machine.add_tag"),
                    b -> filter(DrivePackets.FilterCommand.TAG, 0, filterId.getValue()))
                .bounds(leftPos + 178, topPos + top + 34, 44, 16)
                .build());
    filterSlots =
        addRenderableWidget(
            new FilterSlots(
                leftPos + 8,
                topPos + top + 54,
                new FilterSlots.Actions() {
                  @Override
                  public void addCarried() {
                    filter(DrivePackets.FilterCommand.CARRIED, 0, "");
                  }

                  @Override
                  public void cycle(int index, String shown) {
                    filter(DrivePackets.FilterCommand.CYCLE, index, shown);
                  }

                  @Override
                  public void remove(int index) {
                    filter(DrivePackets.FilterCommand.REMOVE, index, "");
                  }
                },
                index -> selectedRule = index));
    clear =
        addRenderableWidget(
            Button.builder(
                    Component.translatable("gui.factorycore.machine.clear_rules"),
                    b -> filter(DrivePackets.FilterCommand.CLEAR, 0, ""))
                .bounds(leftPos + 156, topPos + top + 54, 66, 16)
                .build());
    priority =
        addRenderableWidget(
            new net.minecraft.client.gui.components.EditBox(
                font,
                leftPos + 182,
                topPos + bayTop(DrivePackets.BAYS) + 1,
                40,
                14,
                Component.translatable("gui.factorycore.drive.priority")));
    priority.setMaxLength(5);
    priority.setTooltip(
        net.minecraft.client.gui.components.Tooltip.create(
            Component.translatable("gui.factorycore.drive.priority.help")));
    priorityShown = Integer.MIN_VALUE;
    for (int bay = 0; bay < DrivePackets.BAYS; bay++) {
      int selected = bay;
      bayButtons.add(
          addRenderableWidget(
              new Button(
                  leftPos + DriveMenu.BAY_X,
                  topPos + bayTop(bay),
                  ROW_WIDTH - 20, // stops short of the partition button
                  24,
                  Component.translatable("gui.factorycore.drive.bay", bay + 1),
                  button -> actOnBay(selected),
                  narration -> narration.get()) {
                @Override
                protected void renderWidget(
                    GuiGraphics graphics, int mouseX, int mouseY, float partial) {
                  if (isFocused())
                    graphics.renderOutline(getX(), getY(), width, height, 0xffffffff);
                }
              }));
    }
    sync();
  }

  @Override
  public boolean keyPressed(int key, int scan, int modifiers) {
    if (priority.isFocused()
        && (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
            || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)) {
      net.neoforged.neoforge.network.PacketDistributor.sendToServer(
          new DrivePackets.FilterCommand(
              menu.containerId,
              menu.view().session(),
              0,
              DrivePackets.FilterCommand.PRIORITY,
              0,
              priority.getValue()));
      priorityShown = Integer.MIN_VALUE;
      return true;
    }
    // Typing into a box must not close the screen on the inventory key.
    if (getFocused() instanceof net.minecraft.client.gui.components.EditBox box
        && box.isFocused()
        && key != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE)
      return box.keyPressed(key, scan, modifiers) || box.canConsumeInput();
    return super.keyPressed(key, scan, modifiers);
  }

  private void filter(int action, int selection, String text) {
    if (filterBay < 0) return;
    net.neoforged.neoforge.network.PacketDistributor.sendToServer(
        new DrivePackets.FilterCommand(
            menu.containerId,
            menu.view().session(),
            filterBay,
            action,
            Math.max(0, selection),
            text));
  }

  @Override
  public net.minecraft.client.renderer.Rect2i filterArea() {
    return filterSlots == null || !filterSlots.visible
        ? null
        : new net.minecraft.client.renderer.Rect2i(
            filterSlots.getX(),
            filterSlots.getY(),
            filterSlots.getWidth(),
            filterSlots.getHeight());
  }

  @Override
  public void addFilterId(dev.izzy.factorycore.core.resource.ResourceKind kind, String id) {
    if (filterBay < 0) return;
    boolean fluid = menu.view().bays().get(filterBay).fluid();
    if ((kind == dev.izzy.factorycore.core.resource.ResourceKind.FLUID) == fluid)
      filter(DrivePackets.FilterCommand.REGISTRY, 0, id);
  }

  @Override
  protected void containerTick() {
    super.containerTick();
    sync();
  }

  /** Shows the bay list or the open bay's partition panel. */
  private void sync() {
    if (filterBay >= 0 && menu.view().bays().get(filterBay).item().isEmpty()) filterBay = -1;
    boolean panel = filterBay >= 0;
    for (int bay = 0; bay < DrivePackets.BAYS; bay++) {
      bayButtons.get(bay).visible = !panel;
      filterButtons.get(bay).visible = !panel && !menu.view().bays().get(bay).item().isEmpty();
    }
    back.visible = mode.visible = filterSlots.visible = clear.visible = panel;
    priority.visible = !panel;
    if (priorityShown != menu.view().priority() && !priority.isFocused()) {
      priorityShown = menu.view().priority();
      priority.setValue(Integer.toString(priorityShown));
    }
    addById.visible = panel && !typing;
    filterId.visible = addId.visible = addTag.visible = panel && typing;
    if (!panel) return;
    var row = menu.view().bays().get(filterBay);
    mode.setMessage(
        Component.translatable(
            "gui.factorycore.connector.insert." + row.partitionMode().toLowerCase(Locale.ROOT)));
    selectedRule = Math.max(0, Math.min(selectedRule, row.partition().size() - 1));
    filterSlots.show(
        row.partition(),
        row.fluid()
            ? dev.izzy.factorycore.core.resource.ResourceKind.FLUID
            : dev.izzy.factorycore.core.resource.ResourceKind.ITEM,
        selectedRule);
    clear.active = !row.partition().isEmpty();
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
    int hovered = filterBay >= 0 ? -1 : bayAt(mouseX, mouseY);
    for (int bay = 0; bay < DrivePackets.BAYS && filterBay < 0; bay++) {
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
    if (filterBay >= 0) {
      var row = menu.view().bays().get(filterBay);
      var name =
          new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(row.item())))
              .getHoverName();
      graphics.drawString(
          font,
          font.plainSubstrByWidth(
              Component.translatable("gui.factorycore.drive.filter.title", filterBay + 1, name)
                  .getString(),
              158),
          8,
          DriveMenu.BAY_Y + 3,
          0xffffff,
          false);
      graphics.drawString(
          font,
          font.plainSubstrByWidth(
              Component.translatable("gui.factorycore.drive.filter.note").getString(), 214),
          8,
          DriveMenu.BAY_Y + 94,
          0xa5b5c5,
          false);
    }
    for (int bay = 0; bay < DrivePackets.BAYS && filterBay < 0; bay++) {
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
    if (filterBay < 0)
      graphics.drawString(
          font,
          Component.translatable("gui.factorycore.drive.priority"),
          182 - 4 - font.width(Component.translatable("gui.factorycore.drive.priority")),
          bayTop(DrivePackets.BAYS) + 4,
          0xffffff,
          false);
    // Narrower while the priority field shares the row.
    var lines = font.split(status, filterBay < 0 ? 120 : ROW_WIDTH);
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
    int bay = filterBay >= 0 ? -1 : bayAt(mouseX, mouseY);
    if (bay >= 0 && filterButtons.get(bay).isHovered()) bay = -1;
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
