package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.platform.client.ui.PanelLayout;
import dev.izzy.factorycore.platform.client.ui.Panels;
import dev.izzy.factorycore.platform.client.ui.Rect;
import dev.izzy.factorycore.platform.storage.RecoveryMenu;
import dev.izzy.factorycore.platform.storage.RecoveryPackets;
import dev.izzy.factorycore.platform.storage.StagingClass;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Transfer Recovery screen (ADR 0055; on the panel kit since playtest H3b): a bounded page of
 * transfer records, the selected record's safe next step and only the actions the server would
 * accept for it. Each row states its class in words ({@code [known]}, {@code [uncertain]} ...),
 * never by color alone.
 */
public final class RecoveryScreen extends AbstractContainerScreen<RecoveryMenu> {
  private static final int ROWS = RecoveryPackets.PAGE_ROWS, LINE_HEIGHT = 12;

  /** Panel-relative layout: the scope line, the list, the next step, the actions, the result. */
  private static final int SCOPE_Y = PanelLayout.BODY,
      LIST_Y = SCOPE_Y + PanelLayout.LINE + PanelLayout.GAP,
      LIST_HEIGHT = ROWS * LINE_HEIGHT + 4,
      STEP_Y = LIST_Y + LIST_HEIGHT + PanelLayout.GAP,
      ACTION_Y = STEP_Y + 3 * PanelLayout.LINE + PanelLayout.GAP,
      MESSAGE_Y = ACTION_Y + PanelLayout.ROW + PanelLayout.GAP;

  private final PanelLayout layout =
      new PanelLayout(
          PanelLayout.WIDE,
          PanelLayout.heightFor(MESSAGE_Y + 2 * PanelLayout.LINE - PanelLayout.BODY));
  private final Rect list =
      new Rect(PanelLayout.EDGE, LIST_Y, PanelLayout.WIDE - 2 * PanelLayout.EDGE, LIST_HEIGHT);

  private Button returning, settle, retire, home, next;
  private UUID selected = RecoveryPackets.NONE;
  private long shown = -1;

  public RecoveryScreen(RecoveryMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = layout.width();
    imageHeight = layout.height();
  }

  @Override
  protected void init() {
    super.init();
    shown = -1;
    int x = leftPos + list.x();
    returning = action(x, "return", RecoveryPackets.RETURN);
    settle = action(returning.getRight() + PanelLayout.GAP, "settle", RecoveryPackets.SETTLE);
    retire = action(settle.getRight() + PanelLayout.GAP, "retire", RecoveryPackets.RETIRE);
    var footer = layout.footer();
    var homeLabel = Component.translatable("gui.factorycore.recovery.home");
    var nextLabel = Component.translatable("gui.factorycore.recovery.next");
    var cells =
        PanelLayout.alignRight(
            footer,
            new int[] {font.width(homeLabel) + 12, font.width(nextLabel) + 12},
            PanelLayout.GAP);
    home =
        addRenderableWidget(
            Button.builder(
                    homeLabel, button -> menu.send(RecoveryPackets.HOME, RecoveryPackets.NONE))
                .bounds(
                    leftPos + cells.get(0).x(),
                    topPos + footer.y(),
                    cells.get(0).width(),
                    footer.height())
                .build());
    next =
        addRenderableWidget(
            Button.builder(
                    nextLabel, button -> menu.send(RecoveryPackets.NEXT, RecoveryPackets.NONE))
                .bounds(
                    leftPos + cells.get(1).x(),
                    topPos + footer.y(),
                    cells.get(1).width(),
                    footer.height())
                .build());
    refresh();
  }

  private Button action(int x, String key, int action) {
    var label = Component.translatable("gui.factorycore.recovery." + key);
    return addRenderableWidget(
        Button.builder(label, button -> act(action))
            .bounds(x, topPos + ACTION_Y, font.width(label) + 14, PanelLayout.ROW)
            .tooltip(
                Tooltip.create(
                    Component.translatable("gui.factorycore.recovery." + key + ".tooltip")))
            .build());
  }

  private void act(int action) {
    var row = selectedRow();
    if (row != null) menu.send(action, row.id());
  }

  private RecoveryPackets.Row selectedRow() {
    for (var row : menu.view().rows()) if (row.id().equals(selected)) return row;
    return null;
  }

  /** The list row under a screen point, or -1. */
  private int rowAt(double mouseX, double mouseY) {
    double x = mouseX - leftPos - list.x(), y = mouseY - topPos - list.y() - 2;
    if (x < 0 || x >= list.width() || y < 0) return -1;
    int row = (int) (y / LINE_HEIGHT);
    return row < menu.view().rows().size() ? row : -1;
  }

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    int row = rowAt(mouseX, mouseY);
    if (button == 0 && row >= 0) {
      selected = menu.view().rows().get(row).id();
      refresh();
      return true;
    }
    return super.mouseClicked(mouseX, mouseY, button);
  }

  @Override
  protected void containerTick() {
    super.containerTick();
    if (menu.view().revision() != shown) refresh();
  }

  private static String label(RecoveryPackets.Row row) {
    String resource = row.resource();
    int colon = resource.indexOf(':');
    if (colon >= 0) resource = resource.substring(colon + 1);
    return "["
        + Component.translatable("gui.factorycore.recovery.class." + row.classification().key())
            .getString()
        + "] "
        + resource
        + " "
        + Component.translatable("gui.factorycore.recovery.staged", row.staged()).getString()
        + " — "
        + row.owner();
  }

  /** Re-selects a visible record and decides which actions apply to it. */
  public void refresh() {
    var view = menu.view();
    shown = view.revision();
    var visible = view.rows();
    if (selectedRow() == null)
      selected = visible.isEmpty() ? RecoveryPackets.NONE : visible.get(0).id();
    var row = selectedRow();
    returning.active = row != null && row.mine() && row.classification() == StagingClass.KNOWN;
    settle.active = row != null && row.mine() && row.classification() == StagingClass.LEGACY;
    retire.visible = view.operator();
    retire.active =
        view.operator()
            && row != null
            && row.classification() == StagingClass.COMPLETE
            && row.staged() == 0;
    home.active = view.page() > 0;
    next.active = view.more();
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
    Panels.panel(graphics, leftPos, topPos, layout);
    var area = list.offset(leftPos, topPos);
    Panels.inset(graphics, area);
    int hovered = rowAt(mouseX, mouseY);
    var rows = menu.view().rows();
    for (int row = 0; row < rows.size(); row++) {
      int y = area.y() + 2 + row * LINE_HEIGHT;
      Panels.row(
          graphics,
          new Rect(area.x() + 1, y, area.width() - 2, LINE_HEIGHT),
          rows.get(row).id().equals(selected),
          row == hovered);
    }
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
    super.render(graphics, mouseX, mouseY, partial);
    int row = rowAt(mouseX, mouseY);
    if (row < 0) {
      renderTooltip(graphics, mouseX, mouseY);
      return;
    }
    var record = menu.view().rows().get(row);
    var text =
        Component.translatable(
            "gui.factorycore.recovery.row.tooltip",
            record.id().toString(),
            record.state(),
            record.kind().toLowerCase(Locale.ROOT),
            record.resource(),
            record.delivered(),
            record.returned(),
            record.pending());
    graphics.renderComponentTooltip(
        font,
        List.of(text.getString().split("\n")).stream().<Component>map(Component::literal).toList(),
        mouseX,
        mouseY);
  }

  private Component status(RecoveryPackets.View view) {
    return switch (view.status()) {
      case "ready", "loading" ->
          Component.translatable("gui.factorycore.recovery.status." + view.status());
      case "queued" ->
          Component.translatable("gui.factorycore.recovery.queued", view.detail(), view.evidence());
      case "retired" -> Component.translatable("gui.factorycore.recovery.retired", view.evidence());
      case "result", "settled" ->
          Component.translatable("gui.factorycore.recovery." + view.status(), view.detail());
      case "operator" -> Component.translatable("gui.factorycore.recovery.operator");
      default -> Component.translatable("command.factorycore.recovery." + view.status());
    };
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    var view = menu.view();
    Panels.title(graphics, font, title, 0, 0);
    graphics.drawString(
        font,
        font.plainSubstrByWidth(
            Component.translatable(
                    view.operator()
                        ? "gui.factorycore.recovery.operator_scope"
                        : "gui.factorycore.recovery.own_scope",
                    view.page() + 1)
                .getString(),
            list.width()),
        list.x() + 1,
        SCOPE_Y,
        Panels.MUTED,
        false);
    var inner = new Rect(list.x() + 3, list.y() + 2, list.width() - 6, list.height() - 4);
    var rows = view.rows();
    if (rows.isEmpty())
      graphics.drawString(
          font,
          Component.translatable("gui.factorycore.recovery.empty"),
          inner.x(),
          inner.y() + 2,
          Panels.MUTED,
          false);
    for (int row = 0; row < rows.size(); row++)
      graphics.drawString(
          font,
          font.plainSubstrByWidth(label(rows.get(row)), inner.width()),
          inner.x(),
          inner.y() + row * LINE_HEIGHT + 2,
          Panels.TEXT,
          false);
    var row = selectedRow();
    if (row != null)
      Panels.wrapped(
          graphics,
          font,
          Component.translatable(
              "gui.factorycore.recovery.next."
                  + row.classification().key()
                  + (row.mine() || row.classification() == StagingClass.UNCERTAIN ? "" : ".other")),
          new Rect(list.x() + 1, STEP_Y, list.width(), 3 * PanelLayout.LINE),
          row.classification() == StagingClass.UNCERTAIN ? Panels.WARN : Panels.TEXT);
    if (!view.status().equals("ready"))
      Panels.wrapped(
          graphics,
          font,
          status(view),
          new Rect(list.x() + 1, MESSAGE_Y, list.width(), 2 * PanelLayout.LINE),
          Panels.WARN);
  }
}
