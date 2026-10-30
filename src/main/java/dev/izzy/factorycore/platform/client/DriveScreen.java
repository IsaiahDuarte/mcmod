package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.platform.client.ui.PanelLayout;
import dev.izzy.factorycore.platform.client.ui.Panels;
import dev.izzy.factorycore.platform.client.ui.Rect;
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

/**
 * Four drive bays with fill bars above the player inventory; clicks act with the cursor item. A
 * bay's ⚙ opens its partition in place of the list. On the panel kit since playtest H3a.
 */
public final class DriveScreen extends AbstractContainerScreen<DriveMenu>
    implements FilterDropTarget {
  private static final int ROW_WIDTH = PanelLayout.WIDE - 2 * PanelLayout.EDGE;

  /** The priority row under the bays, and the partition rows in the same space. */
  private static final int PRIORITY_Y = DriveMenu.BAY_Y + DrivePackets.BAYS * DriveMenu.BAY_HEIGHT;

  /** B3: the bay whose partition panel is open, or -1 for the bay list. */
  private int filterBay = -1, selectedRule;

  private boolean typing;
  private int describeY;
  private final List<Button> bayButtons = new ArrayList<>(), filterButtons = new ArrayList<>();
  private FilterSlots filterSlots;
  private net.minecraft.client.gui.components.EditBox filterId;
  private Button back, mode, addById, addId, addTag, clear;

  /** B4: the drive's priority; Enter saves it. */
  private net.minecraft.client.gui.components.EditBox priority;

  private int priorityShown = Integer.MIN_VALUE;

  public DriveScreen(DriveMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = PanelLayout.WIDE;
    imageHeight = DriveMenu.HOTBAR_Y + PanelLayout.SLOT - 1 + PanelLayout.EDGE;
    inventoryLabelX = DriveMenu.INVENTORY_X - 1;
    inventoryLabelY = DriveMenu.INVENTORY_Y - 12;
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
                      leftPos + DriveMenu.BAY_X + ROW_WIDTH - 20, topPos + bayTop(bay) + 3, 18, 18)
                  .tooltip(
                      net.minecraft.client.gui.components.Tooltip.create(
                          Component.translatable("gui.factorycore.drive.filter.help")))
                  .build()));
    }
    // The partition panel: heading and Back; the insert mode; the slots with Add by ID and Clear
    // beside them (a right-click removes one slot); then the selected rule or the typed-ID row.
    int top = DriveMenu.BAY_Y, left = DriveMenu.BAY_X;
    var backLabel = Component.translatable("gui.factorycore.drive.filter.back");
    int backWidth = font.width(backLabel) + 10;
    back =
        addRenderableWidget(
            Button.builder(backLabel, b -> filterBay = -1)
                .bounds(leftPos + left + ROW_WIDTH - backWidth, topPos + top, backWidth, 18)
                .build());
    int modeY = top + PanelLayout.ROW + PanelLayout.GAP;
    mode =
        addRenderableWidget(
            Button.builder(Component.empty(), b -> filter(DrivePackets.FilterCommand.MODE, 0, ""))
                .bounds(leftPos + left, topPos + modeY, ROW_WIDTH, 18)
                .tooltip(
                    net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("gui.factorycore.drive.filter.mode.help")))
                .build());
    int slotsY = modeY + PanelLayout.ROW + PanelLayout.GAP;
    int side = left + FilterSlots.COLUMNS * PanelLayout.SLOT + PanelLayout.GAP;
    int sideWidth = left + ROW_WIDTH - side;
    addById =
        addRenderableWidget(
            Button.builder(
                    Component.translatable("gui.factorycore.buffer.add_by_id"),
                    b -> typing = !typing)
                .bounds(leftPos + side, topPos + slotsY, sideWidth, 17)
                .tooltip(
                    net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("gui.factorycore.buffer.add_by_id.help")))
                .build());
    describeY = slotsY + 2 * PanelLayout.SLOT + PanelLayout.GAP;
    var idCells =
        PanelLayout.fit(
            new Rect(left, describeY, ROW_WIDTH, PanelLayout.ROW),
            new int[] {100, 40, 40},
            PanelLayout.GAP);
    filterId =
        addRenderableWidget(
            new net.minecraft.client.gui.components.EditBox(
                font,
                leftPos + idCells.get(0).x() + 1,
                topPos + describeY + 1,
                idCells.get(0).width() - 2,
                16,
                Component.translatable("gui.factorycore.buffer.id")));
    filterId.setMaxLength(256);
    addId =
        addRenderableWidget(
            Button.builder(
                    Component.translatable("gui.factorycore.machine.add_id"),
                    b -> filter(DrivePackets.FilterCommand.REGISTRY, 0, filterId.getValue()))
                .bounds(
                    leftPos + idCells.get(1).x(), topPos + describeY, idCells.get(1).width(), 18)
                .build());
    addTag =
        addRenderableWidget(
            Button.builder(
                    Component.translatable("gui.factorycore.machine.add_tag"),
                    b -> filter(DrivePackets.FilterCommand.TAG, 0, filterId.getValue()))
                .bounds(
                    leftPos + idCells.get(2).x(), topPos + describeY, idCells.get(2).width(), 18)
                .build());
    filterSlots =
        addRenderableWidget(
            new FilterSlots(
                leftPos + left,
                topPos + slotsY,
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
                .bounds(leftPos + side, topPos + slotsY + 19, sideWidth, 17)
                .build());
    priority =
        addRenderableWidget(
            new net.minecraft.client.gui.components.EditBox(
                font,
                leftPos + DriveMenu.BAY_X + ROW_WIDTH - 40,
                topPos + PRIORITY_Y + 1,
                40,
                16,
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
                    Panels.lightFocusEdge(graphics, new Rect(getX(), getY(), width, height));
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
    Panels.panel(graphics, new Rect(leftPos, topPos, imageWidth, imageHeight));
    int hovered = filterBay >= 0 ? -1 : bayAt(mouseX, mouseY);
    for (int bay = 0; bay < DrivePackets.BAYS && filterBay < 0; bay++) {
      int x = leftPos + DriveMenu.BAY_X, y = topPos + bayTop(bay);
      Panels.inset(graphics, new Rect(x, y, ROW_WIDTH, 24));
      if (bay == hovered) Panels.hovered(graphics, new Rect(x + 1, y + 1, ROW_WIDTH - 2, 22));
      Panels.slot(graphics, x + 3, y + 3);
      var row = menu.view().bays().get(bay);
      if (row.item().isEmpty()) continue;
      graphics.renderItem(
          new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(row.item()))),
          x + 4,
          y + 4);
      int barX = x + 25, barY = y + 21, barWidth = ROW_WIDTH - 50;
      Panels.track(graphics, new Rect(barX, barY, barWidth, 2));
      if (!row.infinite() && row.capacity() > 0) {
        int filled = (int) Math.min(barWidth, (double) row.total() / row.capacity() * barWidth);
        if (row.total() > 0) filled = Math.max(1, filled);
        Panels.meter(graphics, new Rect(barX, barY, filled, 2), filled * 10 >= barWidth * 9);
      }
    }
    for (var slot : menu.slots) Panels.slot(graphics, leftPos + slot.x - 1, topPos + slot.y - 1);
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    Panels.title(graphics, font, title, 0, 0);
    int strip = PanelLayout.EDGE + 1 + font.width(title) + 8;
    StatusStrip.drawOnPanel(
        graphics, font, menu.deviceStatus(), strip, PanelLayout.TITLE_Y, imageWidth - 8 - strip);
    graphics.drawString(
        font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, Panels.TEXT, false);
    var view = menu.view();
    if (filterBay >= 0) {
      var row = view.bays().get(filterBay);
      var name =
          new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(row.item())))
              .getHoverName();
      graphics.drawString(
          font,
          font.plainSubstrByWidth(
              Component.translatable("gui.factorycore.drive.filter.title", filterBay + 1, name)
                  .getString(),
              back.getX() - leftPos - DriveMenu.BAY_X - PanelLayout.GAP),
          DriveMenu.BAY_X,
          DriveMenu.BAY_Y + 5,
          Panels.TEXT,
          false);
      if (!typing && selectedRule < row.partition().size())
        FilterSlots.describe(
            graphics,
            font,
            row.partition().get(selectedRule),
            DriveMenu.BAY_X,
            describeY,
            ROW_WIDTH);
    }
    for (int bay = 0; bay < DrivePackets.BAYS && filterBay < 0; bay++) {
      var row = view.bays().get(bay);
      int x = DriveMenu.BAY_X + 25, y = bayTop(bay) + 3;
      if (row.item().isEmpty()) {
        graphics.drawString(
            font,
            Component.translatable("gui.factorycore.drive.empty"),
            x,
            y + 4,
            Panels.MUTED,
            false);
        continue;
      }
      var name =
          new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(row.item())))
              .getHoverName();
      var heading = Component.translatable("gui.factorycore.drive.heading", name, row.types());
      int textWidth = ROW_WIDTH - 50;
      graphics.drawString(
          font, font.plainSubstrByWidth(heading.getString(), textWidth), x, y, Panels.TEXT, false);
      graphics.drawString(
          font,
          font.plainSubstrByWidth(amountLine(row).getString(), textWidth),
          x,
          y + 9,
          Panels.TEXT,
          false);
    }
    if (filterBay >= 0) return;
    var priorityLabel = Component.translatable("gui.factorycore.drive.priority");
    int priorityX = priority.getX() - leftPos - 6 - font.width(priorityLabel);
    graphics.drawString(font, priorityLabel, priorityX, PRIORITY_Y + 5, Panels.TEXT, false);
    // The result of the last bay click (instructions are in the bay tooltips).
    if (view.status() == DrivePackets.Status.READY && view.detail().isEmpty()) return;
    var status =
        view.detail().isEmpty()
            ? Component.translatable("gui.factorycore.drive.status." + view.status().key())
            : Component.literal(view.detail());
    Panels.wrapped(
        graphics,
        font,
        status,
        new Rect(DriveMenu.BAY_X, PRIORITY_Y, priorityX - DriveMenu.BAY_X - PanelLayout.GAP, 20),
        Panels.WARN);
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
