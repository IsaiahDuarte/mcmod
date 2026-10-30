package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.SignalRule;
import dev.izzy.factorycore.platform.client.ui.ChoiceButton;
import dev.izzy.factorycore.platform.client.ui.PanelLayout;
import dev.izzy.factorycore.platform.client.ui.Panels;
import dev.izzy.factorycore.platform.client.ui.Rect;
import dev.izzy.factorycore.platform.signal.SignalMenu;
import dev.izzy.factorycore.platform.signal.SignalPackets;
import dev.izzy.factorycore.platform.storage.InterfaceKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * C1 Signal Interface settings (on the panel kit since playtest H3a): what to watch, when to turn
 * on and how strong. The rule is a local draft until Apply; the watched resource is set at once
 * (held item, JEI drop or Add by ID).
 */
public final class SignalScreen extends AbstractContainerScreen<SignalMenu>
    implements FilterDropTarget {
  private static final SignalRule.Condition[] CONDITIONS = SignalRule.Condition.values();
  private static final SignalRule.Compare[] COMPARES = SignalRule.Compare.values();
  private static final SignalRule.Output[] OUTPUTS = SignalRule.Output.values();

  private static final int BODY =
      5 * (PanelLayout.ROW + PanelLayout.GAP) + 2 * PanelLayout.LINE + PanelLayout.GAP;

  private final PanelLayout layout = new PanelLayout(PanelLayout.WIDE, PanelLayout.heightFor(BODY));

  private SignalRule.Condition condition;
  private SignalRule.Compare compare;
  private SignalRule.Output output;
  private EditBox threshold, id;
  private Button apply, addById, setItem, setFluid, setLabel;
  private final List<ChoiceButton> conditions = new ArrayList<>(),
      compares = new ArrayList<>(),
      outputs = new ArrayList<>();
  private EditBox labelBox;
  private String shownLabel;
  private boolean typing, invalid;
  private long shown = -1;
  private int statusWidth;

  /** Panel-relative rows that carry text, and the watched slot. */
  private Rect watchRow, whenRow, powerRow, labelRow, modeRow, messageRow, footer, slot;

  public SignalScreen(SignalMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = layout.width();
    imageHeight = layout.height();
  }

  @Override
  protected void init() {
    String typed = threshold == null ? null : threshold.getValue();
    super.init();
    conditions.clear();
    compares.clear();
    outputs.clear();
    var rows = new PanelLayout(layout.width(), layout.height());

    // Watch: the slot, then the watched name and Add by ID, or the typed-ID row in their place.
    watchRow = rows.row();
    slot =
        new Rect(watchRow.x() + caption("watch"), watchRow.y(), PanelLayout.SLOT, PanelLayout.SLOT);
    var addLabel = Component.translatable("gui.factorycore.signal.add_by_id");
    var addCell =
        PanelLayout.alignRight(watchRow, new int[] {font.width(addLabel) + 10}, 0).getFirst();
    addById =
        button(
            addCell,
            "add_by_id",
            () -> {
              typing = !typing;
              sync();
            });
    var idCells =
        PanelLayout.fit(
            new Rect(
                slot.right() + PanelLayout.GAP,
                watchRow.y(),
                watchRow.right() - slot.right() - PanelLayout.GAP,
                watchRow.height()),
            new int[] {
              100,
              font.width(Component.translatable("gui.factorycore.signal.set_item")) + 10,
              font.width(Component.translatable("gui.factorycore.signal.set_fluid")) + 10
            },
            PanelLayout.GAP);
    id =
        addRenderableWidget(
            new EditBox(
                font,
                leftPos + idCells.get(0).x() + 1,
                topPos + idCells.get(0).y() + 1,
                idCells.get(0).width() - 2,
                16,
                Component.translatable("gui.factorycore.buffer.id")));
    id.setMaxLength(256);
    setItem = button(idCells.get(1), "set_item", () -> resource(false));
    setFluid = button(idCells.get(2), "set_fluid", () -> resource(true));

    // When it turns on.
    whenRow = rows.row();
    int whenLeft = whenRow.x() + caption("when");
    var whenCells =
        fitted(
            new Rect(whenLeft, whenRow.y(), whenRow.right() - whenLeft, whenRow.height()),
            CONDITIONS.length,
            i -> conditionName(CONDITIONS[i]));
    for (int i = 0; i < CONDITIONS.length; i++) {
      var choice = CONDITIONS[i];
      conditions.add(
          choice(
              whenCells.get(i),
              conditionName(choice),
              "gui.factorycore.signal.condition.help",
              () -> {
                condition = choice;
                sync();
              }));
    }

    // For an amount: at least or below, the number, and full or proportional output.
    var ruleRow = rows.row();
    int compareWidth = PanelLayout.SLOT;
    for (int i = 0; i < COMPARES.length; i++) {
      var choice = COMPARES[i];
      compares.add(
          choice(
              new Rect(
                  ruleRow.x() + i * (compareWidth + 2),
                  ruleRow.y(),
                  compareWidth,
                  ruleRow.height()),
              Component.translatable(
                  "gui.factorycore.signal.compare." + choice.name().toLowerCase(Locale.ROOT)),
              "gui.factorycore.signal.compare.help",
              () -> {
                compare = choice;
                sync();
              }));
    }
    var outWidths = new int[OUTPUTS.length];
    for (int i = 0; i < OUTPUTS.length; i++) outWidths[i] = font.width(outputName(OUTPUTS[i])) + 10;
    int outTotal = (OUTPUTS.length - 1) * 2;
    for (int width : outWidths) outTotal += width;
    var outCells = PanelLayout.alignRight(ruleRow, outWidths, 2);
    for (int i = 0; i < OUTPUTS.length; i++) {
      var choice = OUTPUTS[i];
      outputs.add(
          choice(
              outCells.get(i),
              outputName(choice),
              "gui.factorycore.signal.output.help",
              () -> {
                output = choice;
                sync();
              }));
    }
    int boxLeft = ruleRow.x() + COMPARES.length * (compareWidth + 2) + 2;
    threshold =
        addRenderableWidget(
            new EditBox(
                font,
                leftPos + boxLeft + 1,
                topPos + ruleRow.y() + 1,
                ruleRow.right() - outTotal - PanelLayout.GAP - boxLeft - 2,
                16,
                Component.translatable("gui.factorycore.signal.threshold")));
    threshold.setMaxLength(19);
    threshold.setTooltip(
        Tooltip.create(Component.translatable("gui.factorycore.signal.threshold.help")));
    powerRow = rows.row(PanelLayout.LINE);

    // D6: the label programs name in `emit redstone N on machines("label")`.
    labelRow = rows.row();
    var setLabelText = Component.translatable("gui.factorycore.signal.set_label");
    int setWidth = font.width(setLabelText) + 10;
    int labelLeft = labelRow.x() + caption("label");
    labelBox =
        addRenderableWidget(
            new EditBox(
                font,
                leftPos + labelLeft + 1,
                topPos + labelRow.y() + 1,
                labelRow.right() - setWidth - PanelLayout.GAP - labelLeft - 2,
                16,
                Component.translatable("gui.factorycore.signal.label")));
    labelBox.setMaxLength(64);
    labelBox.setTooltip(
        Tooltip.create(Component.translatable("gui.factorycore.signal.label.help")));
    setLabel =
        button(
            new Rect(labelRow.right() - setWidth, labelRow.y(), setWidth, labelRow.height()),
            "set_label",
            () ->
                send(SignalPackets.Command.LABEL, false, labelBox.getValue(), menu.view().rule()));

    modeRow = rows.row();
    InterfaceModeRow.add(
        this::addRenderableWidget,
        font,
        menu.containerId,
        InterfaceKind.SIGNAL,
        modeRow.offset(leftPos, topPos));
    messageRow = rows.row(PanelLayout.LINE);

    footer = rows.footer();
    var resetLabel = Component.translatable("gui.factorycore.signal.reset");
    var closeLabel = Component.translatable("gui.factorycore.signal.cancel");
    var applyLabel = Component.translatable("gui.factorycore.signal.apply");
    var actions =
        PanelLayout.alignRight(
            footer,
            new int[] {
              font.width(resetLabel) + 10, font.width(closeLabel) + 10, font.width(applyLabel) + 12
            },
            PanelLayout.GAP);
    statusWidth = actions.get(0).x() - footer.x() - PanelLayout.GAP;
    button(
        actions.get(0),
        "reset",
        () -> {
          load(menu.view().rule());
          sync();
        });
    button(actions.get(1), "cancel", this::onClose);
    apply = button(actions.get(2), "apply", this::apply);
    if (condition == null) load(menu.view().rule());
    if (typed != null) threshold.setValue(typed);
    sync();
  }

  /** The width a caption takes before its row's controls. */
  private int caption(String key) {
    return font.width(Component.translatable("gui.factorycore.signal." + key)) + 6;
  }

  private List<Rect> fitted(Rect row, int count, java.util.function.IntFunction<Component> label) {
    var widths = new int[count];
    for (int i = 0; i < count; i++) widths[i] = font.width(label.apply(i)) + 10;
    return PanelLayout.fit(row, widths, PanelLayout.GAP);
  }

  private ChoiceButton choice(Rect cell, Component label, String help, Runnable action) {
    var button = new ChoiceButton(cell.offset(leftPos, topPos), label, b -> action.run());
    button.setTooltip(Tooltip.create(Component.translatable(help)));
    return addRenderableWidget(button);
  }

  private Button button(Rect cell, String key, Runnable action) {
    return addRenderableWidget(
        ScreenButtons.labeled(
            "gui.factorycore.signal.",
            key,
            leftPos + cell.x(),
            topPos + cell.y(),
            cell.width(),
            cell.height(),
            action));
  }

  private static Component conditionName(SignalRule.Condition condition) {
    return Component.translatable(
        "gui.factorycore.signal.when." + condition.name().toLowerCase(Locale.ROOT));
  }

  private static Component outputName(SignalRule.Output output) {
    return Component.translatable(
        "gui.factorycore.signal.out." + output.name().toLowerCase(Locale.ROOT));
  }

  private void load(SignalRule rule) {
    condition = rule.condition();
    compare = rule.compare();
    output = rule.output();
    if (threshold != null) threshold.setValue(Long.toString(rule.threshold()));
    invalid = false;
  }

  private void send(int action, boolean fluid, String text, SignalRule rule) {
    PacketDistributor.sendToServer(
        new SignalPackets.Command(menu.containerId, menu.session(), action, fluid, text, rule));
  }

  private void resource(boolean fluid) {
    send(SignalPackets.Command.RESOURCE, fluid, id.getValue(), menu.view().rule());
    typing = false;
    sync();
  }

  private void apply() {
    if (!apply.active) return;
    try {
      long value = Long.parseLong(threshold.getValue().strip());
      send(
          SignalPackets.Command.APPLY,
          false,
          "",
          new SignalRule(condition, compare, value, output));
      invalid = false;
    } catch (IllegalArgumentException badInput) {
      invalid = true;
    }
  }

  @Override
  protected void containerTick() {
    super.containerTick();
    sync();
  }

  private void sync() {
    var view = menu.view();
    if (shown != view.revision() && view.status() == SignalPackets.Status.APPLIED)
      load(view.rule());
    if (shown != view.revision()) shown = view.revision();
    if (!view.label().equals(shownLabel) && !labelBox.isFocused()) {
      labelBox.setValue(view.label());
      shownLabel = view.label();
    }
    boolean editable = view.editable();
    boolean stock = condition == SignalRule.Condition.STOCK;
    for (int i = 0; i < CONDITIONS.length; i++) {
      conditions.get(i).setSelected(CONDITIONS[i] == condition);
      conditions.get(i).active = editable && CONDITIONS[i] != condition;
    }
    for (int i = 0; i < COMPARES.length; i++) {
      compares.get(i).setSelected(COMPARES[i] == compare);
      compares.get(i).active = editable && COMPARES[i] != compare;
      compares.get(i).visible = stock;
    }
    for (int i = 0; i < OUTPUTS.length; i++) {
      outputs.get(i).setSelected(OUTPUTS[i] == output);
      outputs.get(i).active = editable && OUTPUTS[i] != output;
      outputs.get(i).visible = stock;
    }
    threshold.visible = stock;
    for (var widget : List.<AbstractWidget>of(apply, addById, setItem, setFluid))
      widget.active = editable;
    threshold.setEditable(editable);
    labelBox.setEditable(editable);
    setLabel.active = editable;
    id.visible = setItem.visible = setFluid.visible = typing;
    addById.visible = !typing;
  }

  private boolean overSlot(double mouseX, double mouseY) {
    double x = mouseX - leftPos - slot.x(), y = mouseY - topPos - slot.y();
    return x >= 0 && x < PanelLayout.SLOT && y >= 0 && y < PanelLayout.SLOT;
  }

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    // Clicking the watch slot while holding an item watches that item (or the fluid inside it).
    if (button == 0 && overSlot(mouseX, mouseY) && !menu.getCarried().isEmpty()) {
      send(SignalPackets.Command.CARRIED, false, "", menu.view().rule());
      return true;
    }
    return super.mouseClicked(mouseX, mouseY, button);
  }

  @Override
  public boolean keyPressed(int key, int scan, int modifiers) {
    if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && threshold.isFocused()) {
      apply();
      return true;
    }
    if (getFocused() instanceof EditBox box && box.isFocused() && key != GLFW.GLFW_KEY_ESCAPE)
      return box.keyPressed(key, scan, modifiers) || box.canConsumeInput();
    return super.keyPressed(key, scan, modifiers);
  }

  @Override
  public Rect2i filterArea() {
    return new Rect2i(leftPos + slot.x(), topPos + slot.y(), PanelLayout.SLOT, PanelLayout.SLOT);
  }

  @Override
  public void addFilterId(ResourceKind kind, String dropped) {
    send(SignalPackets.Command.RESOURCE, kind == ResourceKind.FLUID, dropped, menu.view().rule());
  }

  @Override
  protected void renderBg(GuiGraphics g, float partial, int mouseX, int mouseY) {
    Panels.panel(g, leftPos, topPos, layout);
    int x = leftPos + slot.x(), y = topPos + slot.y();
    Panels.slot(g, x, y);
    var view = menu.view();
    if (view.resource().isEmpty()) return;
    if (view.fluid()) {
      if (!ResourceIcons.renderFluid(g, view.resource(), x + 1, y + 1))
        g.renderFakeItem(new ItemStack(net.minecraft.world.item.Items.BUCKET), x + 1, y + 1);
    } else {
      var location = ResourceLocation.tryParse(view.resource());
      if (location != null && BuiltInRegistries.ITEM.containsKey(location))
        g.renderFakeItem(new ItemStack(BuiltInRegistries.ITEM.get(location)), x + 1, y + 1);
    }
  }

  private void caption(GuiGraphics g, String key, Rect row) {
    g.drawString(
        font,
        Component.translatable("gui.factorycore.signal." + key),
        row.x(),
        row.y() + 5,
        Panels.TEXT,
        false);
  }

  @Override
  protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
    Panels.title(g, font, title, 0, 0);
    var view = menu.view();
    caption(g, "watch", watchRow);
    int nameX = slot.right() + 6;
    if (!typing)
      g.drawString(
          font,
          font.plainSubstrByWidth(
              view.resource().isEmpty()
                  ? Component.translatable("gui.factorycore.signal.watch.none").getString()
                  : view.name(),
              addById.getX() - leftPos - nameX - PanelLayout.GAP),
          nameX,
          watchRow.y() + 5,
          view.resource().isEmpty() ? Panels.MUTED : Panels.TEXT,
          false);
    caption(g, "when", whenRow);
    var state =
        Component.translatable(
            "gui.factorycore.signal.power",
            view.power(),
            view.reason().isEmpty()
                ? Component.translatable("gui.factorycore.signal.reason.ok")
                : Component.translatable("gui.factorycore.signal.reason." + view.reason()));
    g.drawString(
        font,
        font.plainSubstrByWidth(state.getString(), powerRow.width()),
        powerRow.x(),
        powerRow.y(),
        view.power() > 0 ? Panels.BAD : Panels.MUTED,
        false);
    caption(g, "label", labelRow);
    InterfaceModeRow.caption(g, font, modeRow);
    var status =
        invalid
            ? SignalPackets.Status.INVALID
            : view.editable() ? view.status() : SignalPackets.Status.DENIED;
    if (status != SignalPackets.Status.READY)
      g.drawString(
          font,
          font.plainSubstrByWidth(
              Component.translatable("gui.factorycore.signal.status." + status.key()).getString(),
              messageRow.width()),
          messageRow.x(),
          messageRow.y(),
          status.good() ? Panels.GOOD : Panels.WARN,
          false);
    StatusStrip.drawOnPanel(g, font, menu.deviceStatus(), footer.x(), footer.y() + 5, statusWidth);
  }

  @Override
  public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
    super.render(g, mouseX, mouseY, partial);
    renderTooltip(g, mouseX, mouseY);
    if (overSlot(mouseX, mouseY))
      g.renderTooltip(
          font, Component.translatable("gui.factorycore.signal.slot.help"), mouseX, mouseY);
  }
}
