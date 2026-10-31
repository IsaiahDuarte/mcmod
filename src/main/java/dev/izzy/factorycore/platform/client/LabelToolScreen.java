package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.platform.client.ui.PanelLayout;
import dev.izzy.factorycore.platform.client.ui.Panels;
import dev.izzy.factorycore.platform.client.ui.Rect;
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

/**
 * D8: the Label Tool's screen (on the panel kit since playtest H3c); sets the label it applies and
 * can forget its copied settings.
 */
public final class LabelToolScreen extends Screen {
  private static final int BODY = PanelLayout.ROW + PanelLayout.GAP + 2 * PanelLayout.LINE;

  private final PanelLayout layout = new PanelLayout(PanelLayout.WIDE, PanelLayout.heightFor(BODY));
  private final InteractionHand hand;
  private final String label;
  private final boolean copied;
  private EditBox box;
  private int left, top;
  private Rect labelRow, stateRow;

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
    left = (width - layout.width()) / 2;
    top = (height - layout.height()) / 2;
    var rows = new PanelLayout(layout.width(), layout.height());
    labelRow = rows.row();
    stateRow = rows.row(2 * PanelLayout.LINE);
    int caption =
        font.width(Component.translatable("gui.factorycore.label_tool.label"))
            + PanelLayout.GAP
            + 2;
    box =
        addRenderableWidget(
            new EditBox(
                font,
                left + labelRow.x() + caption + 1,
                top + labelRow.y() + 1,
                labelRow.width() - caption - 2,
                16,
                Component.translatable("gui.factorycore.label_tool.label")));
    box.setMaxLength(LabelToolItem.MAX_LABEL);
    box.setValue(label);
    box.setTooltip(Tooltip.create(Component.translatable("gui.factorycore.label_tool.save.help")));
    setInitialFocus(box);
    var footer = layout.footer();
    var saveLabel = Component.translatable("gui.factorycore.label_tool.save");
    var clearLabel = Component.translatable("gui.factorycore.label_tool.clear");
    var cells =
        PanelLayout.alignRight(
            footer,
            new int[] {font.width(clearLabel) + 12, font.width(saveLabel) + 16},
            PanelLayout.GAP);
    var clear =
        addRenderableWidget(
            Button.builder(clearLabel, b -> send(true))
                .bounds(
                    left + cells.get(0).x(),
                    top + footer.y(),
                    cells.get(0).width(),
                    footer.height())
                .tooltip(
                    Tooltip.create(Component.translatable("gui.factorycore.label_tool.clear.help")))
                .build());
    clear.active = copied;
    addRenderableWidget(
        Button.builder(saveLabel, b -> send(false))
            .bounds(
                left + cells.get(1).x(), top + footer.y(), cells.get(1).width(), footer.height())
            .tooltip(Tooltip.create(Component.translatable("gui.factorycore.label_tool.save.help")))
            .build());
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

  /** The panel and its text sit on the screen background, under the widgets. */
  @Override
  public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) {
    super.renderBackground(g, mouseX, mouseY, partial);
    Panels.panel(g, left, top, layout);
    Panels.title(g, font, title, left, top, Panels.titleRoom(layout.width()));
    g.drawString(
        font,
        Component.translatable("gui.factorycore.label_tool.label"),
        left + labelRow.x(),
        top + labelRow.y() + 5,
        Panels.TEXT,
        false);
    Panels.wrapped(
        g,
        font,
        Component.translatable(
            copied ? "gui.factorycore.label_tool.copied" : "gui.factorycore.label_tool.help"),
        stateRow.offset(left, top),
        copied ? Panels.WARN : Panels.MUTED);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }
}
