package dev.izzy.factorycore.platform.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * A tab on a panel's top edge (ADR 0064). The panel body starts at {@link #STRIP}; the selected tab
 * is drawn down over the panel's border so it joins the face, and the others sit behind it. The
 * selected tab is inactive, so it reads as the current view rather than an action.
 */
public final class TabButton extends Button {
  /** Height of the tab strip above the panel body. */
  public static final int STRIP = 16;

  private static final int PADDING = 10;

  private boolean selected;

  public TabButton(int x, int y, int width, Component message, OnPress onPress) {
    super(x, y, width, STRIP + 3, message, onPress, DEFAULT_NARRATION);
  }

  /** The width that shows {@code label} in full. */
  public static int width(Font font, Component label) {
    return font.width(label) + PADDING;
  }

  /**
   * Widths for a row of tabs {@code gap} apart within {@code available} pixels: each label in full
   * with the usual padding when that fits, otherwise with the padding shrunk evenly (to no less
   * than 4, which keeps two pixels either side of the text inside the tab's border).
   */
  public static int[] widths(Font font, java.util.List<Component> labels, int available, int gap) {
    int text = 0;
    for (var label : labels) text += font.width(label);
    int spare = available - text - gap * Math.max(0, labels.size() - 1);
    int padding = Math.max(4, Math.min(PADDING, spare / Math.max(1, labels.size())));
    int[] widths = new int[labels.size()];
    for (int i = 0; i < widths.length; i++) widths[i] = font.width(labels.get(i)) + padding;
    return widths;
  }

  public boolean selected() {
    return selected;
  }

  public void setSelected(boolean selected) {
    this.selected = selected;
    this.active = !selected;
  }

  @Override
  protected MutableComponent createNarrationMessage() {
    var message = super.createNarrationMessage();
    return selected
        ? CommonComponents.joinForNarration(
            message, Component.translatable("gui.factorycore.ui.selected"))
        : message;
  }

  @Override
  protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
    var font = Minecraft.getInstance().font;
    // Unselected tabs end on the panel's top outline; the selected one covers the outline and
    // bevel.
    int top = getY() + (selected ? 0 : 3);
    int height = selected ? getHeight() : STRIP + 1 - 3;
    graphics.blitSprite(
        selected ? Panels.TAB_SELECTED : Panels.TAB, getX(), top, getWidth(), height);
    int color = selected ? Panels.TEXT : isHoveredOrFocused() ? 0x202020 : Panels.MUTED;
    graphics.drawString(
        font,
        font.plainSubstrByWidth(getMessage().getString(), getWidth() - 4),
        getX() + (getWidth() - Math.min(font.width(getMessage()), getWidth() - 4)) / 2,
        top + (selected ? 5 : 3),
        color,
        false);
  }
}
