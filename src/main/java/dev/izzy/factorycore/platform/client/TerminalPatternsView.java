package dev.izzy.factorycore.platform.client;

import static dev.izzy.factorycore.platform.client.TerminalScreen.GRID_X;
import static dev.izzy.factorycore.platform.client.TerminalScreen.GRID_Y;
import static dev.izzy.factorycore.platform.client.TerminalScreen.MAIN;

import dev.izzy.factorycore.platform.client.ui.PanelLayout;
import dev.izzy.factorycore.platform.client.ui.Panels;
import dev.izzy.factorycore.platform.client.ui.Rect;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
import dev.izzy.factorycore.platform.storage.TerminalPatternPackets;
import java.util.ArrayList;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The Terminal's Patterns view (ergonomics Q3): each Crafter this terminal can use, a line naming
 * it and a row of its nine pattern slots, in the grid's place, with a scrollbar on the right. A
 * click on a slot takes its pattern onto the cursor, or puts the cursor's pattern there (swapping
 * if both); the server checks Edit where that Crafter is. The terminal search filters by output.
 * The inventory stays below, so patterns can be carried to and from it. Coordinates are
 * panel-relative unless a name says absolute.
 */
final class TerminalPatternsView {
  private static final int ROW_HEIGHT = PanelLayout.LINE + PanelLayout.SLOT + 2;
  private static final int SCROLLBAR = 6;

  private final TerminalScreen screen;

  TerminalPatternsView(TerminalScreen screen) {
    this.screen = screen;
  }

  private TerminalMenu menu() {
    return screen.getMenu();
  }

  private TerminalPatternPackets.View view() {
    return menu().patternView();
  }

  /** Crafter rows that fit the grid's height (at most {@link TerminalPatternPackets#ROWS}). */
  private int fit() {
    return Math.max(1, Math.min(TerminalPatternPackets.ROWS, screen.rows() * 18 / ROW_HEIGHT));
  }

  private int infoY() {
    return GRID_Y + screen.rows() * 18 + 4;
  }

  /** Top of the slot row of visible Crafter {@code row}. */
  private static int slotsY(int row) {
    return GRID_Y + row * ROW_HEIGHT + PanelLayout.LINE;
  }

  private static int slotX(int slot) {
    return GRID_X + 1 + 18 * slot;
  }

  /** Absolute x of slot {@code slot}'s centre, for verification clicks. */
  int slotCenterX(int slot) {
    return screen.left() + slotX(slot) + 8;
  }

  /** Absolute y of row {@code row}'s slot centres, for verification clicks. */
  int slotCenterY(int row) {
    return screen.top() + slotsY(row) + 8;
  }

  private net.minecraft.client.gui.components.Button filterButton, modeButton;

  /** Q100: the filter and search-mode buttons, on the info row's right. */
  void init() {
    var font = screen.font();
    int width = 0;
    for (int value = 0; value <= 2; value++) {
      width =
          Math.max(
              width,
              font.width(
                  Component.translatable("gui.factorycore.terminal.patterns.filter." + value)));
      width =
          Math.max(
              width,
              font.width(
                  Component.translatable("gui.factorycore.terminal.patterns.mode." + value)));
    }
    width += 10;
    int right = MAIN - GRID_X, y = infoY();
    modeButton =
        screen.add(
            net.minecraft.client.gui.components.Button.builder(
                    Component.empty(),
                    button -> send(TerminalPatternPackets.MODE, (view().mode() + 1) % 3, 0))
                .bounds(screen.left() + right - width, screen.top() + y, width, PanelLayout.ROW)
                .tooltip(
                    net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("gui.factorycore.terminal.patterns.mode.help")))
                .build());
    filterButton =
        screen.add(
            net.minecraft.client.gui.components.Button.builder(
                    Component.empty(),
                    button -> send(TerminalPatternPackets.FILTER, (view().filter() + 1) % 3, 0))
                .bounds(
                    screen.left() + right - 2 * width - 4, screen.top() + y, width, PanelLayout.ROW)
                .tooltip(
                    net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("gui.factorycore.terminal.patterns.filter.help")))
                .build());
    tick(false);
  }

  void tick(boolean shown) {
    if (filterButton == null) return;
    filterButton.visible = modeButton.visible = shown;
    filterButton.setMessage(
        Component.translatable("gui.factorycore.terminal.patterns.filter." + view().filter()));
    modeButton.setMessage(
        Component.translatable("gui.factorycore.terminal.patterns.mode." + view().mode()));
  }

  /** Q100: Shift-click on a pattern in the inventory stores it in the first free Crafter. */
  void store(int inventorySlot) {
    send(TerminalPatternPackets.STORE, inventorySlot, 0);
  }

  /** Q100: where row {@code row}'s hide/show link is drawn (panel-relative). */
  private Rect hideLink(int row) {
    var font = screen.font();
    var label =
        Component.translatable(
            view().crafters().get(row).hidden()
                ? "gui.factorycore.terminal.patterns.show"
                : "gui.factorycore.terminal.patterns.hide");
    int width = font.width(label);
    return new Rect(MAIN - GRID_X - SCROLLBAR - 2 - width, GRID_Y + row * ROW_HEIGHT + 1, width, 8);
  }

  /** Opening the view asks the server for its window (and starts its refreshes). */
  void show() {
    send(TerminalPatternPackets.WINDOW, view().offset(), 0);
  }

  private void send(int action, int selection, int slot) {
    var menu = menu();
    PacketDistributor.sendToServer(
        new TerminalPatternPackets.Command(
            menu.containerId, menu.session(), view().revision(), action, selection, slot));
  }

  private void scroll(int offset) {
    int to =
        Math.max(
            0,
            Math.min(Math.min(offset, TerminalPatternPackets.MAX_OFFSET), view().total() - fit()));
    if (to != view().offset()) send(TerminalPatternPackets.WINDOW, to, 0);
  }

  // ---------------------------------------------------------------- drawing

  void renderBg(GuiGraphics graphics) {
    var view = view();
    int left = screen.left(), top = screen.top();
    boolean searching = !menu().view().search().isBlank();
    int rows = Math.min(fit(), view.crafters().size());
    for (int row = 0; row < rows; row++) {
      var crafter = view.crafters().get(row);
      for (int slot = 0; slot < TerminalPatternPackets.SLOTS; slot++) {
        int x = left + slotX(slot), y = top + slotsY(row);
        Panels.slot(graphics, x - 1, y - 1);
        var entry = crafter.patterns().get(slot);
        if (entry.icon().isEmpty()) continue;
        if (!entry.fluid() || !ResourceIcons.renderFluid(graphics, entry.icon(), x, y)) {
          var item = ResourceIcons.item(entry.icon());
          if (item != null) graphics.renderItem(new ItemStack(item), x, y);
        }
        // Without Edit, or outside the search, a slot is washed out.
        if (!crafter.editable() || (searching && !entry.match()))
          Panels.disabled(graphics, new Rect(x, y, 16, 16));
      }
    }
    var track =
        new Rect(left + MAIN - GRID_X - SCROLLBAR, top + GRID_Y, SCROLLBAR, screen.rows() * 18);
    Panels.scrollbar(
        graphics, track, rows, view.total(), view.offset(), Math.max(1, view.total() - fit()));
  }

  void renderLabels(GuiGraphics graphics) {
    var view = view();
    var font = screen.font();
    int rows = Math.min(fit(), view.crafters().size());
    int width = MAIN - 2 * GRID_X - SCROLLBAR - 2;
    if (view.crafters().isEmpty())
      Panels.wrapped(
          graphics,
          font,
          Component.translatable(
              menu().view().search().isBlank()
                  ? "gui.factorycore.terminal.patterns.none"
                  : "gui.factorycore.terminal.patterns.no_match"),
          new Rect(GRID_X + 1, GRID_Y + 2, width, screen.rows() * 18),
          Panels.MUTED);
    for (int row = 0; row < rows; row++) {
      var crafter = view.crafters().get(row);
      var pos = crafter.position();
      // Q100: hide or show, for Crafters the viewer may edit.
      int nameWidth = width;
      if (crafter.editable()) {
        var link = hideLink(row);
        graphics.drawString(
            font,
            Component.translatable(
                    crafter.hidden()
                        ? "gui.factorycore.terminal.patterns.show"
                        : "gui.factorycore.terminal.patterns.hide")
                .withStyle(net.minecraft.ChatFormatting.UNDERLINE),
            link.x(),
            link.y(),
            Panels.MUTED,
            false);
        nameWidth = link.x() - GRID_X - 5;
      }
      // Q107: a label names the Crafter; without one, its position does.
      var name =
          crafter.label().isEmpty()
              ? Component.translatable(
                  crafter.published()
                      ? "gui.factorycore.terminal.patterns.crafter.branch"
                      : "gui.factorycore.terminal.patterns.crafter",
                  pos.getX(),
                  pos.getY(),
                  pos.getZ())
              : Component.translatable(
                  crafter.published()
                      ? "gui.factorycore.terminal.patterns.crafter.branch_named"
                      : "gui.factorycore.terminal.patterns.crafter.named",
                  crafter.label());
      if (!crafter.editable())
        name = Component.translatable("gui.factorycore.terminal.patterns.locked", name);
      if (crafter.hidden())
        name = Component.translatable("gui.factorycore.terminal.patterns.hidden", name);
      graphics.drawString(
          font,
          font.plainSubstrByWidth(name.getString(), nameWidth),
          GRID_X + 1,
          GRID_Y + row * ROW_HEIGHT + 1,
          crafter.editable() ? Panels.TEXT : Panels.MUTED,
          false);
    }
    var status = view.status();
    Component info =
        status == TerminalPatternPackets.Status.READY
            ? Component.translatable("gui.factorycore.terminal.patterns.count", view.total())
            : Component.translatable("gui.factorycore.terminal.patterns.status." + status.key());
    int color =
        switch (status) {
          case DENIED, REFUSED, GONE, NO_POWER, FULL -> Panels.WARN;
          case TAKEN, INSERTED, SWAPPED, HIDDEN, SHOWN -> Panels.GOOD;
          default -> Panels.MUTED;
        };
    graphics.drawString(
        font,
        font.plainSubstrByWidth(
            info.getString(),
            filterButton == null
                ? MAIN - 2 * GRID_X
                : filterButton.getX() - screen.left() - GRID_X - 4),
        GRID_X + 1,
        infoY() + 5,
        color,
        false);
  }

  /** A slot's output and what a click does there; nothing elsewhere. */
  boolean renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
    int[] at = slotAt(mouseX, mouseY);
    if (at == null) return false;
    var crafter = view().crafters().get(at[0]);
    var entry = crafter.patterns().get(at[1]);
    var lines = new ArrayList<Component>();
    if (!entry.icon().isEmpty()) {
      var item = ResourceIcons.item(entry.icon());
      lines.add(
          entry.fluid()
              ? Component.literal(entry.icon())
              : item == null ? Component.literal(entry.icon()) : item.getDescription());
      lines.add(
          Component.translatable(
              entry.processing()
                  ? "gui.factorycore.terminal.patterns.processing"
                  : "gui.factorycore.terminal.patterns.crafting"));
    }
    lines.add(
        Component.translatable(
            !crafter.editable()
                ? "gui.factorycore.terminal.patterns.help.locked"
                : entry.icon().isEmpty()
                    ? "gui.factorycore.terminal.patterns.help.insert"
                    : "gui.factorycore.terminal.patterns.help.take"));
    graphics.renderComponentTooltip(screen.font(), lines, mouseX, mouseY);
    return true;
  }

  // ---------------------------------------------------------------- input

  /** {visible row, slot} under the mouse, or null. */
  private int[] slotAt(double mouseX, double mouseY) {
    int x = (int) Math.floor(mouseX) - screen.left(), y = (int) Math.floor(mouseY) - screen.top();
    int rows = Math.min(fit(), view().crafters().size());
    for (int row = 0; row < rows; row++) {
      if (y < slotsY(row) || y >= slotsY(row) + 16) continue;
      for (int slot = 0; slot < TerminalPatternPackets.SLOTS; slot++)
        if (x >= slotX(slot) && x < slotX(slot) + 16) return new int[] {row, slot};
    }
    return null;
  }

  boolean mouseClicked(double mouseX, double mouseY, int button) {
    if (button != 0) return false;
    int x = (int) Math.floor(mouseX) - screen.left(), y = (int) Math.floor(mouseY) - screen.top();
    int rows = Math.min(fit(), view().crafters().size());
    for (int row = 0; row < rows; row++)
      if (view().crafters().get(row).editable() && hideLink(row).contains(new Rect(x, y, 1, 1))) {
        send(TerminalPatternPackets.HIDE, row, 0);
        return true;
      }
    int[] at = slotAt(mouseX, mouseY);
    if (at == null) return false;
    send(TerminalPatternPackets.CLICK, at[0], at[1]);
    return true;
  }

  /** The list scrolls by one Crafter (Shift: by a window). */
  boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
    int y = (int) Math.floor(mouseY) - screen.top();
    if (scrollY == 0 || y < GRID_Y || y >= GRID_Y + screen.rows() * 18) return false;
    int step = Screen.hasShiftDown() ? fit() : 1;
    scroll(view().offset() + (scrollY > 0 ? -step : step));
    return true;
  }
}
