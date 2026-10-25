package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.platform.storage.LabelToolContent;
import dev.izzy.factorycore.platform.storage.LabelToolItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.PacketDistributor;

/** D8: the Label Tool's screen; sets the label it applies and can forget its copied settings. */
public final class LabelToolScreen extends Screen {
  private static final int WIDTH = 220, HEIGHT = 96;

  private final InteractionHand hand;
  private final String label;
  private final boolean copied;
  private EditBox box;

  private LabelToolScreen(InteractionHand hand, String label, boolean copied) {
    super(Component.translatable("gui.factorycore.label_tool.title"));
    this.hand = hand;
    this.label = label;
    this.copied = copied;
  }

  public static void open(InteractionHand hand, String label, boolean copied) {
    Minecraft.getInstance().setScreen(new LabelToolScreen(hand, label, copied));
  }

  @Override
  protected void init() {
    int left = (width - WIDTH) / 2, top = (height - HEIGHT) / 2;
    box =
        addRenderableWidget(
            new EditBox(
                font,
                left + 10,
                top + 28,
                WIDTH - 20,
                16,
                Component.translatable("gui.factorycore.label_tool.label")));
    box.setMaxLength(LabelToolItem.MAX_LABEL);
    box.setValue(label);
    setInitialFocus(box);
    addRenderableWidget(
        Button.builder(Component.translatable("gui.factorycore.label_tool.save"), b -> send(false))
            .bounds(left + 10, top + 52, 96, 18)
            .tooltip(Tooltip.create(Component.translatable("gui.factorycore.label_tool.save.help")))
            .build());
    var clear =
        addRenderableWidget(
            Button.builder(
                    Component.translatable("gui.factorycore.label_tool.clear"), b -> send(true))
                .bounds(left + WIDTH - 106, top + 52, 96, 18)
                .tooltip(
                    Tooltip.create(Component.translatable("gui.factorycore.label_tool.clear.help")))
                .build());
    clear.active = copied;
  }

  private void send(boolean clearCopy) {
    PacketDistributor.sendToServer(
        new LabelToolContent.SetLabel(
            hand == InteractionHand.MAIN_HAND, box.getValue(), clearCopy));
    onClose();
  }

  @Override
  public boolean keyPressed(int key, int scan, int modifiers) {
    if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
      send(false);
      return true;
    }
    return super.keyPressed(key, scan, modifiers);
  }

  @Override
  public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
    int left = (width - WIDTH) / 2, top = (height - HEIGHT) / 2;
    renderBackground(g, mouseX, mouseY, partial);
    g.fill(left, top, left + WIDTH, top + HEIGHT, 0xff282c30);
    g.fill(left + 4, top + 4, left + WIDTH - 4, top + HEIGHT - 4, 0xff44484d);
    g.drawString(font, title, left + 10, top + 12, 0xffffff, false);
    g.drawString(
        font,
        font.plainSubstrByWidth(
            Component.translatable(
                    copied
                        ? "gui.factorycore.label_tool.copied"
                        : "gui.factorycore.label_tool.help")
                .getString(),
            WIDTH - 20),
        left + 10,
        top + 76,
        0xa5b5c5,
        false);
    super.render(g, mouseX, mouseY, partial);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }
}
