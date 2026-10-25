package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.core.storage.RedstoneGate;
import dev.izzy.factorycore.platform.storage.RedstoneMenu;
import java.util.Locale;
import java.util.function.IntSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** C2: the Machine and Buffer preset's redstone control; each click selects the next setting. */
final class RedstoneButton {
  private RedstoneButton() {}

  static Button create(int containerId, IntSupplier state, int x, int y, int width) {
    var button =
        Button.builder(
                Component.empty(),
                pressed -> {
                  var next =
                      RedstoneGate.Control.values()[
                          (RedstoneMenu.control(state.getAsInt()).ordinal() + 1)
                              % RedstoneGate.Control.values().length];
                  Minecraft.getInstance()
                      .gameMode
                      .handleInventoryButtonClick(
                          containerId, RedstoneMenu.BUTTON + next.ordinal());
                })
            .bounds(x, y, width, 18)
            .build();
    refresh(button, state.getAsInt());
    return button;
  }

  static void refresh(Button button, int state) {
    var control = RedstoneMenu.control(state).name().toLowerCase(Locale.ROOT);
    button.setMessage(Component.translatable("gui.factorycore.redstone." + control));
    button.setTooltip(
        Tooltip.create(
            Component.translatable(
                "gui.factorycore.redstone.help",
                Component.translatable("gui.factorycore.redstone." + control + ".help"),
                Component.translatable(
                    RedstoneMenu.powered(state)
                        ? "gui.factorycore.redstone.input.on"
                        : "gui.factorycore.redstone.input.off"))));
  }
}
