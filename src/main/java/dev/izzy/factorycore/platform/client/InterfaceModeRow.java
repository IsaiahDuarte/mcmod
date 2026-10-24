package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.platform.storage.InterfaceKind;
import dev.izzy.factorycore.platform.storage.InterfaceSwitch;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * The Storage / Machine / Buffer buttons shared by the three Interface screens (U11, ADR 0059). The
 * current mode's button is disabled; the others ask the server to switch through a menu button.
 */
final class InterfaceModeRow {
  private InterfaceModeRow() {}

  /**
   * Adds one button per mode at {@code (x + i * stepX, y + i * stepY)}, each {@code width} wide.
   */
  static List<Button> add(
      UnaryOperator<Button> adder,
      int containerId,
      InterfaceKind current,
      int x,
      int y,
      int stepX,
      int stepY,
      int width,
      int height) {
    var buttons = new ArrayList<Button>();
    for (var kind : InterfaceKind.values()) {
      int index = kind.ordinal();
      var button =
          adder.apply(
              Button.builder(
                      Component.translatable(
                          "gui.factorycore.interface.mode." + kind.getSerializedName()),
                      pressed ->
                          Minecraft.getInstance()
                              .gameMode
                              .handleInventoryButtonClick(
                                  containerId, InterfaceSwitch.BUTTON + index))
                  .bounds(x + index * stepX, y + index * stepY, width, height)
                  .tooltip(
                      Tooltip.create(
                          Component.translatable(
                              kind == current
                                  ? "gui.factorycore.interface.mode.current"
                                  : "gui.factorycore.interface.mode.help")))
                  .build());
      button.active = kind != current;
      buttons.add(button);
    }
    return buttons;
  }
}
