package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.core.EnumKeys;
import dev.izzy.factorycore.platform.client.ui.Panels;
import dev.izzy.factorycore.platform.client.ui.Rect;
import dev.izzy.factorycore.platform.session.DeviceStatus;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Draws the shared one-line device status (U7): a lamp, the network state, the device's reason and
 * stored power, shortened at part and word boundaries ({@link FitText}) to the room left on the
 * title line; the tooltip shows it all. Coordinates are screen-relative.
 */
public final class StatusStrip {
  private StatusStrip() {}

  public static Component text(DeviceStatus.View status) {
    return Component.literal(String.join(FitText.SEPARATOR, parts(status)));
  }

  private static List<String> parts(DeviceStatus.View status) {
    if (status.network() == null)
      return List.of(Component.translatable("gui.factorycore.status.loading").getString());
    var parts = new ArrayList<String>();
    parts.add(
        Component.translatable("gui.factorycore.status.network." + EnumKeys.key(status.network()))
            .getString());
    if (!status.reason().isEmpty()) parts.add(Component.translatable(status.reason()).getString());
    if (!status.power().isEmpty()) parts.add(status.power());
    return parts;
  }

  /** Shows the full status when the mouse is over a strip drawn at absolute {@code x, y}. */
  public static void tooltip(
      GuiGraphics graphics,
      Font font,
      DeviceStatus.View status,
      int x,
      int y,
      int width,
      int mouseX,
      int mouseY) {
    if (mouseX >= x && mouseX < x + width && mouseY >= y - 1 && mouseY < y + 10)
      graphics.renderTooltip(font, text(status), mouseX, mouseY);
  }

  /**
   * Draws the strip on a light kit panel (ADR 0064): the kit's lamp and text, green when online,
   * amber otherwise.
   */
  public static void drawOnPanel(
      GuiGraphics graphics, Font font, DeviceStatus.View status, int x, int y, int width) {
    if (width < 16) return;
    Panels.lamp(graphics, new Rect(x, y + 1, 5, 5), status.online());
    graphics.drawString(
        font,
        FitText.fit(parts(status), width - 7, font::width),
        x + 7,
        y,
        status.online() ? Panels.GOOD : Panels.WARN,
        false);
  }
}
