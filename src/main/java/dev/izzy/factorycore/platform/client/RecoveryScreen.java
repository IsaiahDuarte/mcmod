package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.platform.storage.RecoveryMenu;
import dev.izzy.factorycore.platform.storage.RecoveryPackets;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Transfer Recovery screen (ADR 0055): a bounded page of transfer records, the selected record's
 * safe next step and only the actions the server would accept for it. Each row states its class in
 * words ({@code [known]}, {@code [uncertain]} ...), never by color alone.
 */
public final class RecoveryScreen extends AbstractContainerScreen<RecoveryMenu> {
  private static final int ROWS = RecoveryPackets.PAGE_ROWS, ROW_Y = 30, ROW_HEIGHT = 14;
  private final Button[] rows = new Button[ROWS];
  private Button returning, settle, retire, home, next;
  private UUID selected = RecoveryPackets.NONE;
  private long shown = -1;

  public RecoveryScreen(RecoveryMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = 300;
    imageHeight = 204;
  }

  @Override
  protected void init() {
    super.init();
    shown = -1;
    for (int row = 0; row < ROWS; row++) {
      int index = row;
      rows[row] =
          addRenderableWidget(
              Button.builder(Component.empty(), button -> select(index))
                  .bounds(leftPos + 8, topPos + ROW_Y + row * ROW_HEIGHT, imageWidth - 16, 13)
                  .build());
    }
    int y = topPos + 150;
    returning =
        addRenderableWidget(
            Button.builder(
                    Component.translatable("gui.factorycore.recovery.return"),
                    button -> act(RecoveryPackets.RETURN))
                .bounds(leftPos + 8, y, 64, 16)
                .tooltip(
                    Tooltip.create(
                        Component.translatable("gui.factorycore.recovery.return.tooltip")))
                .build());
    settle =
        addRenderableWidget(
            Button.builder(
                    Component.translatable("gui.factorycore.recovery.settle"),
                    button -> act(RecoveryPackets.SETTLE))
                .bounds(leftPos + 76, y, 64, 16)
                .tooltip(
                    Tooltip.create(
                        Component.translatable("gui.factorycore.recovery.settle.tooltip")))
                .build());
    retire =
        addRenderableWidget(
            Button.builder(
                    Component.translatable("gui.factorycore.recovery.retire"),
                    button -> act(RecoveryPackets.RETIRE))
                .bounds(leftPos + 144, y, 64, 16)
                .tooltip(
                    Tooltip.create(
                        Component.translatable("gui.factorycore.recovery.retire.tooltip")))
                .build());
    home =
        addRenderableWidget(
            Button.builder(
                    Component.translatable("gui.factorycore.recovery.home"),
                    button -> menu.send(RecoveryPackets.HOME, RecoveryPackets.NONE))
                .bounds(leftPos + 212, y, 38, 16)
                .build());
    next =
        addRenderableWidget(
            Button.builder(
                    Component.translatable("gui.factorycore.recovery.next"),
                    button -> menu.send(RecoveryPackets.NEXT, RecoveryPackets.NONE))
                .bounds(leftPos + 254, y, 38, 16)
                .build());
    refresh();
  }

  private void select(int row) {
    var view = menu.view();
    if (row < view.rows().size()) selected = view.rows().get(row).id();
    refresh();
  }

  private void act(int action) {
    var row = selectedRow();
    if (row != null) menu.send(action, row.id());
  }

  private RecoveryPackets.Row selectedRow() {
    for (var row : menu.view().rows()) if (row.id().equals(selected)) return row;
    return null;
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
        + Component.translatable("gui.factorycore.recovery.class." + row.classification())
            .getString()
        + "] "
        + resource
        + " "
        + Component.translatable("gui.factorycore.recovery.staged", row.staged()).getString()
        + " — "
        + row.owner();
  }

  /** Rebuilds row labels and which actions apply to the selected record. */
  public void refresh() {
    var view = menu.view();
    shown = view.revision();
    var visible = view.rows();
    if (selectedRow() == null)
      selected = visible.isEmpty() ? RecoveryPackets.NONE : visible.get(0).id();
    for (int row = 0; row < ROWS; row++) {
      var button = rows[row];
      button.visible = row < visible.size();
      if (!button.visible) continue;
      var record = visible.get(row);
      boolean chosen = record.id().equals(selected);
      button.setMessage(Component.literal((chosen ? "> " : "") + label(record)));
      button.setTooltip(
          Tooltip.create(
              Component.translatable(
                  "gui.factorycore.recovery.row.tooltip",
                  record.id().toString(),
                  record.state(),
                  record.kind().toLowerCase(Locale.ROOT),
                  record.resource(),
                  record.delivered(),
                  record.returned(),
                  record.pending())));
    }
    var row = selectedRow();
    returning.active = row != null && row.mine() && row.classification().equals("known");
    settle.active = row != null && row.mine() && row.classification().equals("legacy");
    retire.visible = view.operator();
    retire.active =
        view.operator()
            && row != null
            && row.classification().equals("complete")
            && row.staged() == 0;
    home.active = view.page() > 0;
    next.active = view.more();
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
    graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff282c30);
    graphics.fill(
        leftPos + 4, topPos + 4, leftPos + imageWidth - 4, topPos + imageHeight - 4, 0xff44484d);
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
    graphics.drawString(font, title, 8, 6, 0xffffff, false);
    graphics.drawString(
        font,
        font.plainSubstrByWidth(
            Component.translatable(
                    view.operator()
                        ? "gui.factorycore.recovery.operator_scope"
                        : "gui.factorycore.recovery.own_scope",
                    view.page() + 1)
                .getString(),
            imageWidth - 16),
        8,
        18,
        0xe4bc83,
        false);
    if (view.rows().isEmpty())
      graphics.drawString(
          font,
          Component.translatable("gui.factorycore.recovery.empty"),
          8,
          ROW_Y + 3,
          0xd8dee9,
          false);
    var row = selectedRow();
    int y = ROW_Y + ROWS * ROW_HEIGHT + 4;
    if (row != null)
      for (var line :
          font.split(
              Component.translatable(
                  "gui.factorycore.recovery.next."
                      + row.classification()
                      + (row.mine() || row.classification().equals("uncertain") ? "" : ".other")),
              imageWidth - 16)) {
        if (y > 140) break;
        graphics.drawString(font, line, 8, y, 0xebcb8b, false);
        y += 10;
      }
    y = 170;
    for (var line : font.split(status(view), imageWidth - 16)) {
      if (y > 190) break;
      graphics.drawString(font, line, 8, y, 0xd8dee9, false);
      y += 10;
    }
  }
}
