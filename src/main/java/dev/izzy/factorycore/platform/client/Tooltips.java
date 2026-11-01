package dev.izzy.factorycore.platform.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Vector2i;

/**
 * Q135: tooltips that stay on screen. Lines wrap to at most half the screen (and 260 px), and the
 * box is placed beside the cursor, flipped and then clamped so no edge leaves the screen, at any
 * GUI scale.
 */
public final class Tooltips {
  private static final int MARGIN = 4;

  private Tooltips() {}

  /** Wraps {@code lines} to fit and shows them after this frame's widgets, on top. */
  public static void show(Font font, List<Component> lines) {
    var screen = Minecraft.getInstance().screen;
    if (screen == null || lines.isEmpty()) return;
    int width = Math.max(80, Math.min(260, screen.width / 2 - 2 * MARGIN));
    var wrapped = new ArrayList<FormattedCharSequence>();
    for (var line : lines) wrapped.addAll(font.split(line, width));
    screen.setTooltipForNextRenderPass(wrapped, ON_SCREEN, true);
  }

  static final ClientTooltipPositioner ON_SCREEN =
      (screenWidth, screenHeight, mouseX, mouseY, width, height) -> {
        int[] at = place(screenWidth, screenHeight, mouseX, mouseY, width, height);
        return new Vector2i(at[0], at[1]);
      };

  /** Top-left corner for a {@code width}×{@code height} box near the cursor, fully on screen. */
  static int[] place(
      int screenWidth, int screenHeight, int mouseX, int mouseY, int width, int height) {
    int x = mouseX + 12;
    if (x + width > screenWidth - MARGIN) x = mouseX - 12 - width;
    x = Math.max(MARGIN, Math.min(x, screenWidth - MARGIN - width));
    int y = mouseY - 12;
    if (y + height > screenHeight - MARGIN) y = screenHeight - MARGIN - height;
    y = Math.max(MARGIN, y);
    return new int[] {x, y};
  }
}
