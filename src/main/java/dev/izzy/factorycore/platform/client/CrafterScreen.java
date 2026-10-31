package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.core.EnumKeys;
import dev.izzy.factorycore.platform.client.ui.PanelLayout;
import dev.izzy.factorycore.platform.client.ui.Panels;
import dev.izzy.factorycore.platform.client.ui.Rect;
import dev.izzy.factorycore.platform.crafting.CrafterEntity;
import dev.izzy.factorycore.platform.crafting.CrafterMenu;
import dev.izzy.factorycore.platform.crafting.CrafterPackets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * The Crafter (ergonomics Q2), on the panel kit: its nine pattern slots, its speed, what it is
 * doing, and each machine beside it with the job holding it. Patterns move like any slot: click to
 * take, click with a pattern to insert, Shift-click to move; without Edit they stay put.
 */
public final class CrafterScreen extends AbstractContainerScreen<CrafterMenu> {
  private static final int ROW_WIDTH = PanelLayout.WIDE - 2 * PanelLayout.EDGE;

  /** Rows under the pattern slots: speed, activity, the machines heading and its rows. */
  private static final int SPEED_Y = CrafterMenu.PATTERN_Y + PanelLayout.SLOT + 3,
      ACTIVITY_Y = SPEED_Y + PanelLayout.LINE,
      MACHINES_Y = ACTIVITY_Y + PanelLayout.LINE + PanelLayout.GAP,
      ROWS_Y = MACHINES_Y + PanelLayout.LINE;

  public CrafterScreen(CrafterMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = PanelLayout.WIDE;
    imageHeight = CrafterMenu.HOTBAR_Y + PanelLayout.SLOT - 1 + PanelLayout.EDGE;
    inventoryLabelX = CrafterMenu.INVENTORY_X - 1;
    inventoryLabelY = CrafterMenu.INVENTORY_Y - 12;
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
    Panels.panel(graphics, new Rect(leftPos, topPos, imageWidth, imageHeight));
    for (var slot : menu.slots) Panels.slot(graphics, leftPos + slot.x - 1, topPos + slot.y - 1);
    // Without Edit the pattern slots are shown but washed out: they cannot be changed.
    if (!menu.editable())
      for (int slot = 0; slot < CrafterMenu.PATTERN_COUNT; slot++)
        Panels.disabled(
            graphics,
            new Rect(
                leftPos + CrafterMenu.PATTERN_X + 18 * slot,
                topPos + CrafterMenu.PATTERN_Y,
                16,
                16));
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    Panels.title(graphics, font, title, 0, 0, Panels.titleRoomBeside(imageWidth));
    int strip =
        PanelLayout.EDGE
            + 1
            + Panels.titleWidth(font, title, Panels.titleRoomBeside(imageWidth))
            + 8;
    StatusStrip.drawOnPanel(
        graphics, font, menu.deviceStatus(), strip, PanelLayout.TITLE_Y, imageWidth - 8 - strip);
    graphics.drawString(
        font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, Panels.TEXT, false);
    var view = menu.view();
    line(
        graphics,
        Component.translatable(
            menu.editable()
                ? "gui.factorycore.crafter.patterns"
                : "gui.factorycore.crafter.patterns.locked"),
        PanelLayout.BODY,
        menu.editable() ? Panels.TEXT : Panels.MUTED);
    line(
        graphics,
        Component.translatable("gui.factorycore.crafter.speed", CrafterEntity.batch(view.tier())),
        SPEED_Y,
        Panels.TEXT);
    var activity = view.activity();
    line(
        graphics,
        view.reason() == null
            ? Component.translatable("gui.factorycore.crafter.activity." + activity.key())
            : Component.translatable(
                "gui.factorycore.crafter.activity." + activity.key(),
                Component.translatable(
                    "gui.factorycore.terminal.job.reason." + view.reason().key())),
        ACTIVITY_Y,
        switch (activity) {
          case WORKING -> Panels.GOOD;
          case WAITING, NO_PATTERNS -> Panels.WARN;
          default -> Panels.MUTED;
        });
    line(
        graphics,
        Component.translatable("gui.factorycore.crafter.machines"),
        MACHINES_Y,
        Panels.TEXT);
    if (view.machines().isEmpty()) {
      line(
          graphics,
          Component.translatable("gui.factorycore.crafter.machines.none"),
          ROWS_Y,
          Panels.MUTED);
      return;
    }
    int y = ROWS_Y;
    for (var row : view.machines()) {
      var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(row.block())).getName();
      var side = Component.translatable("gui.factorycore.crafter.side." + EnumKeys.key(row.side()));
      Component state =
          switch (row.state()) {
            case FREE -> Component.translatable("gui.factorycore.crafter.machine.free");
            case BUSY ->
                Component.translatable("gui.factorycore.crafter.machine.busy", output(row));
            case WAITING ->
                Component.translatable(
                    "gui.factorycore.crafter.machine.waiting",
                    output(row),
                    Component.translatable(
                        "gui.factorycore.terminal.job.reason." + row.reason().key()));
          };
      int color =
          switch (row.state()) {
            case FREE -> Panels.MUTED;
            case BUSY -> Panels.GOOD;
            case WAITING -> Panels.WARN;
          };
      line(
          graphics,
          Component.translatable("gui.factorycore.crafter.machine", side, block, state),
          y,
          color);
      y += PanelLayout.LINE;
    }
  }

  /** The job's current output, or a dash when the job has none (it is finishing). */
  private static Component output(CrafterPackets.Machine row) {
    if (row.output().isEmpty()) return Component.literal("—");
    var id = ResourceLocation.parse(row.output());
    return row.fluid()
        ? BuiltInRegistries.FLUID.get(id).getFluidType().getDescription()
        : BuiltInRegistries.ITEM.get(id).getDescription();
  }

  /** One line of text, cut to the panel's width. */
  private void line(GuiGraphics graphics, Component text, int y, int color) {
    String full = text.getString(), shown = font.plainSubstrByWidth(full, ROW_WIDTH - 2);
    graphics.drawString(font, shown, PanelLayout.EDGE + 1, y, color, false);
    if (!shown.equals(full))
      cut.add(new Cut(new Rect(PanelLayout.EDGE + 1, y - 1, ROW_WIDTH, 10), text));
  }

  /** A line too long for the panel, shown whole as a tooltip on hover (panel-relative). */
  private record Cut(Rect area, Component text) {}

  private final List<Cut> cut = new ArrayList<>();

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
    cut.clear();
    super.render(graphics, mouseX, mouseY, partial);
    renderTooltip(graphics, mouseX, mouseY);
    for (var line : cut)
      if (line.area().contains(new Rect(mouseX - leftPos, mouseY - topPos, 1, 1)))
        graphics.renderTooltip(font, font.split(line.text(), 200), mouseX, mouseY);
    StatusStrip.tooltip(
        graphics,
        font,
        menu.deviceStatus(),
        leftPos
            + PanelLayout.EDGE
            + 1
            + Panels.titleWidth(font, title, Panels.titleRoomBeside(imageWidth))
            + 8,
        topPos + PanelLayout.TITLE_Y,
        imageWidth
            - 8
            - (PanelLayout.EDGE
                + 1
                + Panels.titleWidth(font, title, Panels.titleRoomBeside(imageWidth))
                + 8),
        mouseX,
        mouseY);
  }
}
