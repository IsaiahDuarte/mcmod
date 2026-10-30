package dev.izzy.factorycore.platform.client;

import static dev.izzy.factorycore.platform.client.TerminalScreen.COLUMNS;
import static dev.izzy.factorycore.platform.client.TerminalScreen.GRID_X;
import static dev.izzy.factorycore.platform.client.TerminalScreen.GRID_Y;
import static dev.izzy.factorycore.platform.client.TerminalScreen.MAIN;

import dev.izzy.factorycore.platform.client.ui.PanelLayout;
import dev.izzy.factorycore.platform.client.ui.Panels;
import dev.izzy.factorycore.platform.client.ui.Rect;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
import dev.izzy.factorycore.platform.storage.TerminalPackets;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Terminal's Items, Fluids and Energy view (ADR 0017, playtest H2): the 9-column grid and its
 * scrollbar, the info row under it (capacity, Fill, Charge/Drain, Return and Crafting) and the
 * offline note over the grid. It owns the requested window (rows and offset) and the selected
 * entry. Coordinates are panel-relative unless a name says absolute.
 */
final class TerminalStorageView {
  private static final int SCROLL_X = GRID_X + COLUMNS * 18 + 4, SCROLL_WIDTH = 12;

  private final TerminalScreen screen;
  private final List<AbstractButton> cells = new ArrayList<>();
  private Button one, stack, returning, craftingToggle;
  private int rows = 4, desiredOffset, selected;
  private long shownRevision;
  private boolean dragging;

  TerminalStorageView(TerminalScreen screen) {
    this.screen = screen;
  }

  private TerminalMenu menu() {
    return screen.getMenu();
  }

  int rows() {
    return rows;
  }

  int selected() {
    return selected;
  }

  void select(int index) {
    if (index < menu().view().rows().size()) selected = index;
  }

  boolean windowSettled() {
    var view = menu().view();
    return view.size() == COLUMNS * rows && view.offset() == desiredOffset;
  }

  int itemX(int index) {
    return screen.left() + GRID_X + 1 + 18 * (index % COLUMNS);
  }

  int itemY(int index) {
    return screen.top() + GRID_Y + 1 + 18 * (index / COLUMNS);
  }

  private int infoY() {
    return GRID_Y + rows * 18 + 4;
  }

  int inventoryTop() {
    return infoY() + PanelLayout.ROW + 4;
  }

  int hotbarTop() {
    return inventoryTop() + 54 + 4;
  }

  void layout(int rows) {
    this.rows = rows;
    desiredOffset = desiredOffset / COLUMNS * COLUMNS;
  }

  void resetOffset() {
    desiredOffset = 0;
  }

  void clampOffset() {
    desiredOffset = Math.min(desiredOffset, maxOffset());
  }

  void requestWindow() {
    screen.send(TerminalPackets.WINDOW, desiredOffset, COLUMNS * rows, new byte[32]);
  }

  private int maxOffset() {
    return Math.max(0, ((menu().view().total() + COLUMNS - 1) / COLUMNS - rows) * COLUMNS);
  }

  /** Adds the info row's buttons, then one invisible button per grid cell for focus and input. */
  void init() {
    cells.clear();
    var font = screen.font();
    int kind = screen.kind();
    // Info row: the tab's actions on the left, Return and Crafting on the right.
    var info = new Rect(GRID_X, infoY(), MAIN - 2 * GRID_X, PanelLayout.ROW);
    var crafting = Component.translatable("gui.factorycore.terminal.grid.panel");
    var close = Component.translatable("gui.factorycore.terminal.grid.panel.close");
    var returnLabel = Component.translatable("gui.factorycore.terminal.return");
    var right =
        PanelLayout.alignRight(
            info,
            new int[] {
              font.width(returnLabel) + 10, Math.max(font.width(crafting), font.width(close)) + 10
            },
            4);
    returning =
        screen.add(
            button(
                right.get(0),
                returnLabel,
                () -> screen.send(TerminalPackets.RETURN, 0, 1, new byte[32])));
    craftingToggle =
        screen.add(
            button(right.get(1), screen.craftingOpen() ? close : crafting, screen::toggleCrafting));
    // Fluids show Fill; Energy shows Charge and Drain. Size them for the longest label.
    var oneLabel = Component.translatable("gui.factorycore.terminal.action." + kind + ".one");
    var twoLabel = Component.translatable("gui.factorycore.terminal.action." + kind + ".two");
    int oneWidth =
        Math.max(
                font.width(Component.translatable("gui.factorycore.terminal.action.1.one")),
                font.width(Component.translatable("gui.factorycore.terminal.action.2.one")))
            + 10;
    int twoWidth = font.width(Component.translatable("gui.factorycore.terminal.action.2.two")) + 10;
    one =
        screen.add(
            button(
                new Rect(info.x(), info.y(), oneWidth, info.height()),
                oneLabel,
                () -> {
                  if (screen.kind() == 1)
                    screen.send(TerminalPackets.FILL, selected, 1, new byte[32]);
                  else
                    screen.send(
                        TerminalPackets.CHARGE,
                        screen.client().player.getInventory().selected,
                        1,
                        new byte[32]);
                }));
    stack =
        screen.add(
            button(
                new Rect(info.x() + oneWidth + 4, info.y(), twoWidth, info.height()),
                twoLabel,
                () ->
                    screen.send(
                        TerminalPackets.DISCHARGE,
                        screen.client().player.getInventory().selected,
                        1,
                        new byte[32])));

    for (int index = 0; index < COLUMNS * rows; index++) {
      int cell = index;
      cells.add(
          screen.add(
              new AbstractButton(itemX(index) - 1, itemY(index) - 1, 18, 18, Component.empty()) {
                @Override
                protected void updateWidgetNarration(
                    net.minecraft.client.gui.narration.NarrationElementOutput output) {
                  defaultButtonNarrationText(output);
                }

                @Override
                public void onPress() {
                  clickRow(cell, 0);
                }

                @Override
                protected void renderWidget(
                    GuiGraphics graphics, int mouseX, int mouseY, float partial) {
                  if (isFocused()) Panels.focusEdge(graphics, new Rect(getX(), getY(), 18, 18));
                }
              }));
    }
  }

  private Button button(Rect cell, Component label, Runnable action) {
    return Button.builder(label, button -> action.run())
        .bounds(screen.left() + cell.x(), screen.top() + cell.y(), cell.width(), cell.height())
        .build();
  }

  /** Grid cells follow the view: hidden in Jobs, active only for listed entries. */
  void tick(boolean jobsMode) {
    var view = menu().view();
    int kind = screen.kind();
    for (int i = 0; i < cells.size(); i++) {
      var cell = cells.get(i);
      cell.visible = !jobsMode;
      cell.active = !view.busy() && i < view.rows().size() && view.kind() == kind;
      cell.setMessage(
          i < view.rows().size()
              ? Component.literal(view.rows().get(i).name())
              : Component.empty());
    }
  }

  /** Keeps the selection inside the listed rows and updates the info row's actions. */
  void tickActions() {
    var view = menu().view();
    int kind = screen.kind();
    if (shownRevision != view.revision()) {
      selected = Math.min(selected, Math.max(0, view.rows().size() - 1));
      shownRevision = view.revision();
    }
    one.active = !view.busy() && (kind == 2 || !view.rows().isEmpty());
    stack.active = kind == 0 ? one.active : kind == 2;
    one.setMessage(Component.translatable("gui.factorycore.terminal.action." + kind + ".one"));
    stack.setMessage(Component.translatable("gui.factorycore.terminal.action." + kind + ".two"));
    returning.active = !view.busy() && view.staging() && !view.uncertain();
  }

  void tickVisibility(boolean jobsMode) {
    var view = menu().view();
    int kind = screen.kind();
    one.visible = !jobsMode && kind != 0;
    stack.visible = !jobsMode && kind == 2;
    returning.visible = !jobsMode && view.staging();
    // The crafting grid takes items or fluids, so the Energy tab has no Crafting button.
    craftingToggle.visible = !jobsMode && kind != 2;
  }

  // ---------------------------------------------------------------- drawing

  void renderBg(GuiGraphics graphics) {
    int left = screen.left(), top = screen.top(), kind = screen.kind();
    var entries = menu().view().rows();
    for (int index = 0; index < COLUMNS * rows; index++) {
      int x = itemX(index) - 1, y = itemY(index) - 1;
      Panels.slot(graphics, x, y);
      if (index >= entries.size()) continue;
      var icon = new Rect(x + 1, y + 1, 16, 16);
      if (index == selected) Panels.selected(graphics, icon);
      var value = entries.get(index);
      var shown = rowIcon(value.icon()).getDefaultInstance();
      if (kind != 1 || !ResourceIcons.renderFluid(graphics, value.icon(), x + 1, y + 1))
        graphics.renderItem(shown, x + 1, y + 1);
      if (value.craftable() && value.none()) {
        // A ghost: nothing stored, but a pattern can make it.
        Panels.veil(graphics, icon);
        screen.drawCount(graphics, shown, x, y, "+");
        continue;
      }
      if (value.craftable()) Panels.marker(graphics, new Rect(x + 1, y + 1, 3, 3));
      screen.drawCount(graphics, shown, x, y, CountText.compact(value.available()));
    }
    var view = menu().view();
    Panels.scrollbar(
        graphics,
        new Rect(left + SCROLL_X, top + GRID_Y, SCROLL_WIDTH, rows * 18),
        COLUMNS * rows,
        view.total(),
        desiredOffset,
        maxOffset());
    if (kind == 0 && view.status() == TerminalPackets.Status.READY) {
      var bar = capacityBar();
      Panels.inset(graphics, bar.offset(left, top));
      var capacity = new BigInteger(view.capacity());
      int inner = bar.width() - 2;
      int filled =
          view.infinite() || view.external() || capacity.signum() == 0
              ? 0
              : new BigInteger(view.used())
                  .multiply(BigInteger.valueOf(inner))
                  .divide(capacity)
                  .min(BigInteger.valueOf(inner))
                  .intValue();
      Panels.meter(
          graphics, new Rect(left + bar.x() + 1, top + bar.y() + 1, filled, bar.height() - 2));
    }
  }

  /** The info row's free width on the left (Items tab): capacity text above a thin bar. */
  private int infoTextWidth() {
    int right = returning.visible ? returning.getX() : craftingToggle.getX();
    return right - screen.left() - 4 - GRID_X;
  }

  private Rect capacityBar() {
    return new Rect(GRID_X, infoY() + 12, Math.max(10, infoTextWidth()), 5);
  }

  /** Problems show over the grid, where the player is looking. */
  void renderProblems(GuiGraphics graphics) {
    if (!screen.online() && menu().deviceStatus().network() != null)
      Panels.wrapped(
          graphics,
          screen.font(),
          StatusStrip.text(menu().deviceStatus()),
          new Rect(GRID_X + 4, GRID_Y + 4, COLUMNS * 18 - 8, 3 * PanelLayout.LINE),
          Panels.BAD);
  }

  /** The info row's text: a storage problem, or (Items) the capacity. */
  void renderInfo(GuiGraphics graphics) {
    var view = menu().view();
    var font = screen.font();
    int textY = infoY() + 2;
    if (view.status() != TerminalPackets.Status.READY) {
      graphics.drawString(
          font,
          font.plainSubstrByWidth(
              Component.translatable("gui.factorycore.terminal.status." + view.status().key())
                  .getString(),
              Math.max(10, infoTextWidth())),
          GRID_X + 1,
          textY + 3,
          Panels.WARN,
          false);
      return;
    }
    if (screen.kind() != 0) return;
    var capacityText =
        view.external()
            ? Component.translatable(
                "gui.factorycore.terminal.capacity.unknown", CountText.compact(view.used()))
            : view.infinite()
                ? Component.translatable(
                    "gui.factorycore.terminal.capacity.infinite", CountText.compact(view.used()))
                : Component.translatable(
                    "gui.factorycore.terminal.capacity",
                    CountText.compact(view.used()),
                    CountText.compact(view.capacity()));
    graphics.drawString(
        font,
        font.plainSubstrByWidth(capacityText.getString(), Math.max(10, infoTextWidth())),
        GRID_X + 1,
        textY,
        Panels.TEXT,
        false);
  }

  /** A grid entry's counts, or the capacity explanation over the info row; false when neither. */
  boolean renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
    var font = screen.font();
    int kind = screen.kind(), left = screen.left(), top = screen.top();
    int row = rowAt(mouseX, mouseY);
    if (row >= 0 && row < menu().view().rows().size()) {
      var value = menu().view().rows().get(row);
      graphics.renderTooltip(
          font,
          List.of(
              Component.literal(value.name()),
              Component.literal(value.icon()),
              Component.translatable(
                  "gui.factorycore.terminal.counts" + (kind == 0 ? "" : "." + kind),
                  value.total(),
                  value.reserved(),
                  value.available()),
              Component.translatable(
                  value.craftable()
                      ? value.none()
                          ? "gui.factorycore.terminal.craftable.ghost"
                          : "gui.factorycore.terminal.craftable.stocked"
                      : "gui.factorycore.terminal.select.help" + (kind == 0 ? "" : "." + kind))),
          Optional.empty(),
          mouseX,
          mouseY);
      return true;
    }
    if (kind == 0
        && mouseX >= left + GRID_X
        && mouseX < left + GRID_X + infoTextWidth()
        && mouseY >= top + infoY()
        && mouseY < top + infoY() + PanelLayout.ROW) {
      var view = menu().view();
      graphics.renderTooltip(
          font,
          List.of(
              view.status() == TerminalPackets.Status.READY
                  ? Component.translatable(
                      view.infinite()
                          ? "gui.factorycore.terminal.capacity.infinite"
                          : "gui.factorycore.terminal.capacity",
                      view.used(),
                      view.capacity())
                  : Component.translatable(
                      "gui.factorycore.terminal.status." + view.status().key()),
              Component.translatable(
                  view.external()
                      ? "gui.factorycore.terminal.capacity.external"
                      : "gui.factorycore.terminal.capacity.cells")),
          Optional.empty(),
          mouseX,
          mouseY);
      return true;
    }
    return false;
  }

  /** Items show themselves; energy shows redstone; a fluid's bucket is the fallback icon. */
  private Item rowIcon(String id) {
    int kind = screen.kind();
    if (kind == 2) return Items.REDSTONE;
    var location = ResourceLocation.tryParse(id);
    if (location == null) return Items.BARRIER;
    if (kind == 1)
      return BuiltInRegistries.FLUID.containsKey(location)
              && BuiltInRegistries.FLUID.get(location).getBucket() != Items.AIR
          ? BuiltInRegistries.FLUID.get(location).getBucket()
          : Items.BUCKET;
    return BuiltInRegistries.ITEM.containsKey(location)
        ? BuiltInRegistries.ITEM.get(location)
        : Items.BARRIER;
  }

  // ---------------------------------------------------------------- input

  private boolean overScrollbar(double mouseX, double mouseY) {
    int left = screen.left(), top = screen.top();
    return mouseY >= top + GRID_Y
        && mouseY < top + GRID_Y + rows * 18
        && mouseX >= left + SCROLL_X
        && mouseX < left + SCROLL_X + SCROLL_WIDTH;
  }

  private int rowAt(double mouseX, double mouseY) {
    int x = (int) Math.floor((mouseX - screen.left() - GRID_X) / 18);
    int y = (int) Math.floor((mouseY - screen.top() - GRID_Y) / 18);
    return x < 0 || x >= COLUMNS || y < 0 || y >= rows ? -1 : x + COLUMNS * y;
  }

  /** Scrollbar drags and grid clicks; A3: middle-click any craftable, or click a ghost, to ask. */
  boolean mouseClicked(double mouseX, double mouseY, int button) {
    if (overScrollbar(mouseX, mouseY)) {
      dragging = true;
      scrollTo(mouseY);
      return true;
    }
    int row = rowAt(mouseX, mouseY);
    if (row >= 0 && screen.kind() == 0 && row < menu().view().rows().size()) {
      var value = menu().view().rows().get(row);
      boolean ghost = value.craftable() && value.none();
      if (value.craftable() && (button == 2 || ghost && menu().getCarried().isEmpty())) {
        request(row);
        return true;
      }
    }
    if (row >= 0 && (button == 0 || button == 1)) {
      clickRow(row, button);
      return true;
    }
    return false;
  }

  /**
   * Requests visible row {@code index} (a craftable): the server scrolls the Jobs list to it, then
   * the screen switches to Jobs, selects it and focuses the amount box.
   */
  private void request(int index) {
    var rows = menu().view().rows();
    if (index >= rows.size() || !rows.get(index).craftable()) return;
    var icon = rows.get(index).icon();
    screen.send(TerminalPackets.REQUEST, index, 1, new byte[32]);
    screen.showJobs(icon);
  }

  /** Q18: the last Shift-click deposit (slot, item, time), for Shift-double-click. */
  private int lastDepositSlot = -1;

  private ItemStack lastDeposit = ItemStack.EMPTY;
  private long lastDepositAt;

  /**
   * Q18: a second Shift-click on the same slot within 250 ms deposits every matching stack. The
   * first click's stack may already be gone, so any slot still holding the same item is named.
   */
  private boolean depositAllClicked(net.minecraft.world.inventory.Slot slot) {
    long now = net.minecraft.Util.getMillis();
    boolean repeat =
        slot.getContainerSlot() == lastDepositSlot
            && now - lastDepositAt <= 250
            && !lastDeposit.isEmpty();
    var remembered = lastDeposit;
    lastDepositSlot = slot.getContainerSlot();
    lastDeposit = slot.getItem().copy();
    lastDepositAt = now;
    if (!repeat) return false;
    lastDepositSlot = -1;
    var inventory = screen.client().player.getInventory();
    for (int index = 0; index < 36; index++)
      if (ItemStack.isSameItemSameComponents(inventory.getItem(index), remembered))
        try {
          var key =
              new PlatformResourceCodec(screen.client().level.registryAccess()).itemKey(remembered);
          screen.send(TerminalPackets.DEPOSIT_ALL, index, 1, TerminalPackets.fingerprint(key));
          return true;
        } catch (IllegalArgumentException unsupported) {
          return false;
        }
    return true;
  }

  /**
   * Shift-click on an inventory stack empties a fluid container, drains energy or deposits;
   * Shift-double-click deposits every matching stack (Q18).
   */
  boolean depositClicked(double mouseX, double mouseY, int button) {
    var slot = screen.getSlotUnderMouse();
    var player = screen.client().player;
    int left = screen.left(), top = screen.top(), kind = screen.kind();
    if (button == 0
        && Screen.hasShiftDown()
        && kind == 0
        && slot != null
        && slot.container == player.getInventory()
        && depositAllClicked(slot)) return true;
    if (button != 0
        || !Screen.hasShiftDown()
        || slot == null
        || slot.container != player.getInventory()
        || !slot.hasItem()
        || mouseX < left + slot.x
        || mouseX >= left + slot.x + 16
        || mouseY < top + slot.y
        || mouseY >= top + slot.y + 16) return false;
    if (kind == 1) {
      screen.send(TerminalPackets.EMPTY, slot.getContainerSlot(), 1, new byte[32]);
      return true;
    }
    if (kind == 2) {
      screen.send(TerminalPackets.DISCHARGE, slot.getContainerSlot(), 1, new byte[32]);
      return true;
    }
    try {
      var key =
          new PlatformResourceCodec(screen.client().level.registryAccess()).itemKey(slot.getItem());
      screen.send(
          TerminalPackets.DEPOSIT,
          slot.getContainerSlot(),
          Math.min(64, slot.getItem().getCount()),
          TerminalPackets.fingerprint(key));
    } catch (IllegalArgumentException unsupported) {
      player.displayClientMessage(
          Component.translatable("gui.factorycore.terminal.status.unsupported"), true);
    }
    return true;
  }

  boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
    if ((rowAt(mouseX, mouseY) >= 0 || overScrollbar(mouseX, mouseY)) && scrollY != 0) {
      desiredOffset =
          Math.max(0, Math.min(maxOffset(), desiredOffset + (scrollY > 0 ? -COLUMNS : COLUMNS)));
      return true;
    }
    return false;
  }

  boolean mouseDragged(double y) {
    if (dragging) scrollTo(y);
    return dragging;
  }

  void mouseReleased() {
    dragging = false;
  }

  private void scrollTo(double y) {
    double fraction = Math.max(0, Math.min(1, (y - screen.top() - GRID_Y) / (rows * 18)));
    desiredOffset = (int) Math.round(fraction * maxOffset() / COLUMNS) * COLUMNS;
  }

  private void clickRow(int index, int button) {
    var view = menu().view();
    int kind = screen.kind();
    if (view.busy() || view.kind() != kind) return;
    if (!menu().getCarried().isEmpty() && kind == 0) {
      try {
        var key =
            new PlatformResourceCodec(screen.client().level.registryAccess())
                .itemKey(menu().getCarried());
        screen.send(
            TerminalPackets.DEPOSIT_CURSOR,
            0,
            Math.min(64, menu().getCarried().getCount()),
            TerminalPackets.fingerprint(key));
      } catch (IllegalArgumentException unsupported) {
        screen
            .client()
            .player
            .displayClientMessage(
                Component.translatable("gui.factorycore.terminal.status.unsupported"), true);
      }
    } else if (index < view.rows().size()) {
      selected = index;
      if (kind == 0 && !Screen.hasControlDown())
        screen.send(
            Screen.hasShiftDown()
                ? TerminalPackets.WITHDRAW
                : button == 1 ? TerminalPackets.HALF_CURSOR : TerminalPackets.TAKE_CURSOR,
            index,
            64,
            new byte[32]);
    }
  }
}
