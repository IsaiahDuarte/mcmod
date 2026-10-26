package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.platform.client.ui.ChoiceButton;
import dev.izzy.factorycore.platform.client.ui.PanelLayout;
import dev.izzy.factorycore.platform.client.ui.Panels;
import dev.izzy.factorycore.platform.client.ui.Rect;
import dev.izzy.factorycore.platform.storage.InterfaceKind;
import dev.izzy.factorycore.platform.storage.InterfaceSwitch;
import java.util.function.UnaryOperator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * The "Mode: Storage / Buffer / Signal" row shared by the Interface screens (U11, ADR 0059; on the
 * panel kit since playtest H3a). The current mode is selected and inactive; the others ask the
 * server to switch through a menu button.
 */
final class InterfaceModeRow {
  private static final Component CAPTION = Component.translatable("gui.factorycore.interface.mode");

  private InterfaceModeRow() {}

  /** Adds one choice per mode after the caption, filling {@code row} (absolute coordinates). */
  static void add(
      UnaryOperator<ChoiceButton> adder,
      Font font,
      int containerId,
      InterfaceKind current,
      Rect row) {
    var kinds = InterfaceKind.values();
    int left = row.x() + font.width(CAPTION) + 6;
    var widths = new int[kinds.length];
    for (int i = 0; i < kinds.length; i++) widths[i] = font.width(name(kinds[i])) + 10;
    var cells =
        PanelLayout.fit(
            new Rect(left, row.y(), row.right() - left, row.height()), widths, PanelLayout.GAP);
    for (var kind : kinds) {
      int index = kind.ordinal();
      var button =
          adder.apply(
              new ChoiceButton(
                  cells.get(index),
                  name(kind),
                  pressed ->
                      Minecraft.getInstance()
                          .gameMode
                          .handleInventoryButtonClick(
                              containerId, InterfaceSwitch.BUTTON + index)));
      button.setTooltip(
          Tooltip.create(
              Component.translatable(
                  kind == current
                      ? "gui.factorycore.interface.mode.current"
                      : "gui.factorycore.interface.mode.help")));
      button.setSelected(kind == current);
      button.active = kind != current;
    }
  }

  /** Draws the caption at the start of {@code row} (coordinates relative to the panel). */
  static void caption(GuiGraphics graphics, Font font, Rect row) {
    graphics.drawString(font, CAPTION, row.x(), row.y() + 5, Panels.TEXT, false);
  }

  private static Component name(InterfaceKind kind) {
    return Component.translatable("gui.factorycore.interface.mode." + kind.getSerializedName());
  }
}
