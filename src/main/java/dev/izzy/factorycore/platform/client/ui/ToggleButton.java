package dev.izzy.factorycore.platform.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * An on/off setting drawn as a ticked box and its label on the panel face (ADR 0064). The message
 * is the bare label; narration adds the state. The screen owns the state and sets it from synced
 * data, so a click only asks the server and the box follows what the server accepted.
 */
public final class ToggleButton extends Button {
  /** Row height of a toggle; the 9 px box is centred in it. */
  public static final int HEIGHT = 12;

  private static final int BOX = 9, LABEL_X = BOX + 4;

  private boolean on;

  public ToggleButton(int x, int y, int width, Component label, OnPress onPress) {
    super(x, y, width, HEIGHT, label, onPress, DEFAULT_NARRATION);
  }

  /** The width that shows {@code label} in full. */
  public static int width(Font font, Component label) {
    return LABEL_X + font.width(label);
  }

  public boolean on() {
    return on;
  }

  public void setOn(boolean on) {
    this.on = on;
  }

  @Override
  protected MutableComponent createNarrationMessage() {
    return CommonComponents.optionNameValue(getMessage(), CommonComponents.optionStatus(on));
  }

  @Override
  protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
    var font = Minecraft.getInstance().font;
    int boxY = getY() + (getHeight() - BOX) / 2;
    graphics.blitSprite(on ? Panels.CHECKBOX_CHECKED : Panels.CHECKBOX, getX(), boxY, BOX, BOX);
    if (active && isHoveredOrFocused())
      graphics.renderOutline(getX() - 1, boxY - 1, BOX + 2, BOX + 2, 0xff000000 | Panels.TEXT);
    graphics.drawString(
        font,
        font.plainSubstrByWidth(getMessage().getString(), getWidth() - LABEL_X),
        getX() + LABEL_X,
        getY() + (getHeight() - 8) / 2,
        active ? Panels.TEXT : Panels.MUTED,
        false);
  }
}
