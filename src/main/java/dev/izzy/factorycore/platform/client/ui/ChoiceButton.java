package dev.izzy.factorycore.platform.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;

/**
 * One option of a choice row (ADR 0064). The selected option is drawn pressed in copper with white
 * text, whether or not it is clickable, so the current choice is visible instead of looking like a
 * disabled button. Other options render as vanilla buttons.
 */
public final class ChoiceButton extends Button {
  private boolean selected;

  public ChoiceButton(Rect bounds, Component message, OnPress onPress) {
    super(
        bounds.x(),
        bounds.y(),
        bounds.width(),
        bounds.height(),
        message,
        onPress,
        DEFAULT_NARRATION);
  }

  public boolean selected() {
    return selected;
  }

  public void setSelected(boolean selected) {
    this.selected = selected;
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
    if (!selected) {
      super.renderWidget(graphics, mouseX, mouseY, partial);
      return;
    }
    graphics.blitSprite(Panels.CHOICE_SELECTED, getX(), getY(), getWidth(), getHeight());
    renderString(graphics, Minecraft.getInstance().font, 0xffffff | Mth.ceil(alpha * 255) << 24);
  }
}
