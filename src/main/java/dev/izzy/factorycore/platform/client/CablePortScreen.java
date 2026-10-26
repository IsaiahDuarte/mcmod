package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.platform.client.ui.ChoiceButton;
import dev.izzy.factorycore.platform.client.ui.PanelLayout;
import dev.izzy.factorycore.platform.client.ui.Panels;
import dev.izzy.factorycore.platform.client.ui.Rect;
import dev.izzy.factorycore.platform.storage.MachinePresetPackets;
import dev.izzy.factorycore.platform.storage.MachineSettingsMenu;
import dev.izzy.factorycore.platform.storage.MachineSettingsPackets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Cable port screen (playtest H5, ADR 0065), on the panel kit: the machine's label, the resource
 * programs move through it, and its status. Apply saves both through the Machine-port binding.
 */
public final class CablePortScreen extends AbstractContainerScreen<MachineSettingsMenu> {
  private static final ResourceKind[] KINDS = ResourceKind.values();

  private final PanelLayout layout =
      new PanelLayout(
          PanelLayout.NARROW,
          PanelLayout.heightFor(
              PanelLayout.ROW
                  + PanelLayout.GAP
                  + PanelLayout.ROW
                  + PanelLayout.GAP
                  + 3 * PanelLayout.LINE));
  private final List<ChoiceButton> kinds = new ArrayList<>();
  private EditBox label;
  private Button apply, returning;
  private ResourceKind kind;
  private Rect help, footer;
  private boolean loaded;

  public CablePortScreen(MachineSettingsMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = layout.width();
    imageHeight = layout.height();
  }

  @Override
  protected void init() {
    super.init();
    kinds.clear();
    var rows = new PanelLayout(layout.width(), layout.height());
    var labelRow = rows.row();
    String typed = label == null ? menu.view().form().label() : label.getValue();
    label =
        addRenderableWidget(
            new EditBox(
                font,
                leftPos + labelRow.x() + 1,
                topPos + labelRow.y() + 1,
                labelRow.width() - 2,
                16,
                Component.translatable("gui.factorycore.cable_port.label")));
    label.setMaxLength(64);
    label.setValue(typed);
    label.setHint(Component.translatable("gui.factorycore.cable_port.label.hint"));
    var widths = new int[KINDS.length];
    for (int i = 0; i < KINDS.length; i++) widths[i] = font.width(kindName(KINDS[i])) + 10;
    var cells = PanelLayout.fit(rows.row(), widths, PanelLayout.GAP);
    for (int i = 0; i < KINDS.length; i++) {
      var choice = KINDS[i];
      var button =
          new ChoiceButton(
              cells.get(i).offset(leftPos, topPos), kindName(choice), b -> kind = choice);
      button.setTooltip(
          Tooltip.create(Component.translatable("gui.factorycore.cable_port.kind.help")));
      kinds.add(addRenderableWidget(button));
    }
    help = rows.row(3 * PanelLayout.LINE);
    footer = rows.footer();
    var applyLabel = Component.translatable("gui.factorycore.port.apply");
    var returnLabel = Component.translatable("gui.factorycore.preset.return");
    var actions =
        PanelLayout.alignRight(
            footer,
            new int[] {font.width(returnLabel) + 10, font.width(applyLabel) + 12},
            PanelLayout.GAP);
    returning =
        addRenderableWidget(
            Button.builder(
                    returnLabel,
                    b ->
                        PacketDistributor.sendToServer(
                            new MachinePresetPackets.Command(
                                menu.containerId,
                                menu.session(),
                                menu.presetView().revision(),
                                MachinePresetPackets.RETURN,
                                0,
                                "",
                                new byte[32])))
                .bounds(
                    leftPos + actions.get(0).x(),
                    topPos + actions.get(0).y(),
                    actions.get(0).width(),
                    actions.get(0).height())
                .tooltip(
                    Tooltip.create(Component.translatable("gui.factorycore.preset.return.help")))
                .build());
    apply =
        addRenderableWidget(
            Button.builder(applyLabel, b -> apply())
                .bounds(
                    leftPos + actions.get(1).x(),
                    topPos + actions.get(1).y(),
                    actions.get(1).width(),
                    actions.get(1).height())
                .tooltip(
                    Tooltip.create(Component.translatable("gui.factorycore.cable_port.apply.help")))
                .build());
  }

  private static Component kindName(ResourceKind kind) {
    return Component.translatable("kind.factorycore." + kind.name().toLowerCase(Locale.ROOT));
  }

  private void apply() {
    var chosen = kind == null ? menu.view().form().kind() : kind;
    PacketDistributor.sendToServer(
        new MachineSettingsPackets.Apply(
            menu.containerId,
            menu.session(),
            menu.view().generation(),
            new MachineSettingsPackets.Form(
                label.getValue().strip(),
                chosen,
                ConnectorSettings.Access.READ_WRITE,
                0,
                0,
                64,
                ConnectorSettings.defaults(chosen).rate())));
  }

  @Override
  protected void containerTick() {
    super.containerTick();
    var view = menu.view();
    // The first synced view fills the label box; typing is never overwritten afterwards.
    if (!loaded && view.generation() > 0) {
      loaded = true;
      label.setValue(view.form().label());
    }
    var shown = kind == null ? view.form().kind() : kind;
    for (int i = 0; i < KINDS.length; i++) {
      kinds.get(i).setSelected(KINDS[i] == shown);
      kinds.get(i).active = view.editable() && KINDS[i] != shown;
    }
    label.setEditable(view.editable());
    apply.active = view.editable();
    returning.visible = menu.presetView().cleanup();
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
    Panels.panel(graphics, leftPos, topPos, layout);
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    Panels.title(graphics, font, title, 0, 0);
    var name = label.getValue().strip();
    Panels.wrapped(
        graphics,
        font,
        name.isEmpty()
            ? Component.translatable("gui.factorycore.cable_port.help.unlabelled")
            : Component.translatable("gui.factorycore.cable_port.help", name),
        help,
        Panels.MUTED);
    var status = status();
    StatusStrip.drawOnPanel(
        graphics, font, menu.deviceStatus(), status.x(), status.y() + 5, status.width());
  }

  /** The footer's free width left of the visible actions (Return shows only when needed). */
  private Rect status() {
    int right = (returning.visible ? returning.getX() : apply.getX()) - leftPos - PanelLayout.GAP;
    return new Rect(footer.x(), footer.y(), right - footer.x(), footer.height());
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
    super.render(graphics, mouseX, mouseY, partial);
    renderTooltip(graphics, mouseX, mouseY);
    var status = status();
    StatusStrip.tooltip(
        graphics,
        font,
        menu.deviceStatus(),
        leftPos + status.x(),
        topPos + status.y() + 5,
        status.width(),
        mouseX,
        mouseY);
  }

  @Override
  public boolean keyPressed(int key, int scan, int modifiers) {
    if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && label.isFocused()) {
      apply();
      return true;
    }
    if (label.isFocused() && key != GLFW.GLFW_KEY_ESCAPE)
      return label.keyPressed(key, scan, modifiers) || label.canConsumeInput();
    return super.keyPressed(key, scan, modifiers);
  }
}
