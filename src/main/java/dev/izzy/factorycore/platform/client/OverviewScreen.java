package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.platform.client.ui.PanelLayout;
import dev.izzy.factorycore.platform.client.ui.Panels;
import dev.izzy.factorycore.platform.client.ui.Rect;
import dev.izzy.factorycore.platform.network.OverviewMenu;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Network overview (playtest H10), on the panel kit: whether the network works and why not, what it
 * has, and a short checklist of what a first network needs (storage, a terminal, then machines),
 * each with what to do next. Access… opens the Network Access screen.
 */
public final class OverviewScreen extends AbstractContainerScreen<OverviewMenu> {
  /** The checklist, in play order: a role and the step that adds it. */
  private record Step(NetworkTopology.Role role, String key, boolean optional) {}

  private static final List<Step> STEPS =
      List.of(
          new Step(NetworkTopology.Role.STORAGE, "storage", false),
          new Step(NetworkTopology.Role.TERMINAL, "terminal", false),
          new Step(NetworkTopology.Role.INTERFACE, "machines", true),
          new Step(NetworkTopology.Role.CRAFTER, "crafter", true));

  private static final int STATUS_HEIGHT = 2 * PanelLayout.LINE,
      COUNTS_HEIGHT = 3 * PanelLayout.LINE,
      STEP_HEIGHT = 2 * PanelLayout.LINE + 2,
      CHECK_WIDTH = PanelLayout.WIDE - 2 * PanelLayout.EDGE - 10;

  /** Sized to what is shown now; a change (online, counts, steps done) lays it out again. */
  private PanelLayout layout;

  private Rect status, counts, checklist;

  public OverviewScreen(OverviewMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = PanelLayout.WIDE;
  }

  /** Counts and the checklist show only when the network is online and viewable. */
  private boolean detailed() {
    return menu.status() == NetworkTopology.Status.VALID
        && menu.count(NetworkTopology.Role.STORAGE) >= 0;
  }

  private Component stepText(Step step) {
    boolean done = menu.count(step.role()) > 0;
    return Component.translatable(
        "gui.factorycore.overview.step." + step.key() + (done ? ".done" : ".todo"));
  }

  /** The checklist's height: each step takes the lines its text wraps to. */
  private int checklistHeight() {
    int height = 0;
    for (var step : STEPS)
      height += Math.max(1, font.split(stepText(step), CHECK_WIDTH).size()) * PanelLayout.LINE + 2;
    return height;
  }

  private int bodyHeight() {
    return detailed()
        ? STATUS_HEIGHT + PanelLayout.GAP + COUNTS_HEIGHT + PanelLayout.GAP + checklistHeight()
        : STATUS_HEIGHT + PanelLayout.LINE;
  }

  @Override
  protected void init() {
    layout = new PanelLayout(PanelLayout.WIDE, PanelLayout.heightFor(bodyHeight()));
    imageHeight = layout.height();
    super.init();
    var rows = new PanelLayout(layout.width(), layout.height());
    status = rows.row(detailed() ? STATUS_HEIGHT : STATUS_HEIGHT + PanelLayout.LINE);
    counts = detailed() ? rows.row(COUNTS_HEIGHT) : null;
    checklist = detailed() ? rows.row(checklistHeight()) : null;
    var access = Component.translatable("gui.factorycore.overview.access");
    var cell =
        PanelLayout.alignRight(rows.footer(), new int[] {font.width(access) + 12}, PanelLayout.GAP)
            .getFirst();
    addRenderableWidget(
        Button.builder(
                access,
                b ->
                    minecraft.gameMode.handleInventoryButtonClick(
                        menu.containerId, OverviewMenu.ACCESS_BUTTON))
            .bounds(leftPos + cell.x(), topPos + cell.y(), cell.width(), cell.height())
            .tooltip(Tooltip.create(Component.translatable("gui.factorycore.overview.access.help")))
            .build());
  }

  @Override
  protected void containerTick() {
    super.containerTick();
    if (PanelLayout.heightFor(bodyHeight()) != layout.height()) rebuildWidgets();
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
    Panels.panel(graphics, leftPos, topPos, layout);
  }

  private static String key(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    Panels.title(graphics, font, title, 0, 0);
    var state = menu.status();
    boolean online = state == NetworkTopology.Status.VALID;
    Panels.wrapped(
        graphics,
        font,
        Component.translatable(
            "gui.factorycore.overview.status." + (state == null ? "loading" : key(state))),
        status,
        online ? Panels.GOOD : Panels.WARN);
    if (!online) return;
    if (!detailed()) {
      Panels.wrapped(
          graphics,
          font,
          Component.translatable("gui.factorycore.overview.no_view"),
          new Rect(status.x(), status.y() + STATUS_HEIGHT, status.width(), PanelLayout.LINE),
          Panels.MUTED);
      return;
    }
    // The state just changed and the next tick lays the panel out again; draw nothing until then.
    if (counts == null || checklist == null) return;
    // What the network has, in two columns.
    var columns = PanelLayout.columns(counts, 2, PanelLayout.GAP);
    NetworkTopology.Role[] shown = {
      NetworkTopology.Role.STORAGE,
      NetworkTopology.Role.TERMINAL,
      NetworkTopology.Role.INTERFACE,
      NetworkTopology.Role.CRAFTER,
      NetworkTopology.Role.PROGRAM
    };
    for (int i = 0; i < shown.length; i++) {
      var cell = columns.get(i % 2);
      graphics.drawString(
          font,
          Component.translatable(
              "gui.factorycore.overview.count." + key(shown[i]), menu.count(shown[i])),
          cell.x(),
          cell.y() + (i / 2) * PanelLayout.LINE,
          Panels.TEXT,
          false);
    }
    // The checklist: a tick or a cross (never colour alone) and the next thing to do.
    int y = checklist.y();
    for (var step : STEPS) {
      boolean done = menu.count(step.role()) > 0;
      String mark = done ? "✔" : step.optional() ? "○" : "✖";
      int color = done ? Panels.GOOD : step.optional() ? Panels.MUTED : Panels.BAD;
      graphics.drawString(font, mark, checklist.x(), y, color, false);
      int lines =
          Panels.wrapped(
              graphics,
              font,
              stepText(step),
              new Rect(checklist.x() + 10, y, CHECK_WIDTH, STEP_HEIGHT),
              done ? Panels.TEXT : Panels.MUTED);
      // Steps sit one under another; a finished one takes a single line.
      y += Math.max(1, lines) * PanelLayout.LINE + 2;
    }
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
    super.render(graphics, mouseX, mouseY, partial);
    renderTooltip(graphics, mouseX, mouseY);
  }
}
