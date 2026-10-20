package dev.izzy.factorycore.platform.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** The one convention for screen buttons: label {@code prefix + key}, tooltip {@code ...help}. */
final class ScreenButtons {
  private ScreenButtons() {}

  static Button labeled(
      String prefix, String key, int x, int y, int width, int height, Runnable action) {
    return Button.builder(Component.translatable(prefix + key), button -> action.run())
        .bounds(x, y, width, height)
        .tooltip(Tooltip.create(Component.translatable(prefix + key + ".help")))
        .build();
  }
}
