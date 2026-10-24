package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.platform.session.DeviceStatus;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Draws the shared one-line device status (U7): a lamp, the network state, the device's reason and
 * stored power, truncated to the room left on the title line. Coordinates are screen-relative.
 */
public final class StatusStrip {
  private StatusStrip() {}

  public static Component text(DeviceStatus.View status) {
    if (status.network().isEmpty()) return Component.translatable("gui.factorycore.status.loading");
    var line = Component.translatable("gui.factorycore.status.network." + status.network()).copy();
    if (!status.reason().isEmpty())
      line.append(" · ").append(Component.translatable(status.reason()));
    if (!status.power().isEmpty()) line.append(" · ").append(status.power());
    return line;
  }

  /** Online is green; anything else (rebuilding, unloaded, invalid) is amber. */
  public static int color(DeviceStatus.View status) {
    return status.network().equals("valid") ? 0x9fd49b : 0xe4bc83;
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

  public static void draw(
      GuiGraphics graphics, Font font, DeviceStatus.View status, int x, int y, int width) {
    if (width < 16) return;
    graphics.fill(x, y + 2, x + 4, y + 6, 0xff000000 | color(status));
    graphics.drawString(
        font,
        font.plainSubstrByWidth(text(status).getString(), width - 7),
        x + 7,
        y,
        color(status),
        false);
  }
}
