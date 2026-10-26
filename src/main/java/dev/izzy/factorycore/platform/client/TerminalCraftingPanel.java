package dev.izzy.factorycore.platform.client;

import static dev.izzy.factorycore.platform.client.TerminalScreen.MAIN;
import static dev.izzy.factorycore.platform.client.TerminalScreen.SIDE;

import dev.izzy.factorycore.platform.client.ui.ChoiceButton;
import dev.izzy.factorycore.platform.client.ui.PanelLayout;
import dev.izzy.factorycore.platform.client.ui.Panels;
import dev.izzy.factorycore.platform.client.ui.Rect;
import dev.izzy.factorycore.platform.client.ui.TabButton;
import dev.izzy.factorycore.platform.client.ui.ToggleButton;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.TerminalCraftingPackets;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
import dev.izzy.factorycore.platform.storage.TerminalPackets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

/**
 * The Terminal's crafting panel (ADR 0028, ADR 0032, playtest H2), docked to the right of the main
 * panel and only as tall as its content: the Table/Inputs/Outputs choice, the ghost 3×3 grid with
 * the result or per-cell counts, Use row, Clear, Encode, Substitutes and Craft/×64. Shown only
 * while the screen's {@link TerminalScreen#craftingOpen()} is true. Coordinates are panel-relative.
 */
final class TerminalCraftingPanel {
  /** The ghost grid, relative to this panel's left edge. */
  static final int GRID_X = 7, GRID_Y = TabButton.STRIP + 29;

  private static final int STATUS_Y = GRID_Y + 54 + 4 + ToggleButton.HEIGHT + 4,
      CRAFT_Y = STATUS_Y + 3 * PanelLayout.LINE + 4,
      BOTTOM = CRAFT_Y + PanelLayout.ROW + PanelLayout.EDGE;

  private final TerminalScreen screen;
  private final List<ChoiceButton> gridTabs = new ArrayList<>();
  private Button useRow, craftOne, craftMany, clearGrid, encode, countDown, countUp;
  private ToggleButton substitutesButton;
  private boolean substitutes;

  /** 0 = crafting grid, 1 = processing inputs, 2 = processing outputs (first row = 3 outputs). */
  private int gridPage;

  private int target;
  private long seenGridRevision;
  private int requestedMode = -1;
  private long modeRevision;

  TerminalCraftingPanel(TerminalScreen screen) {
    this.screen = screen;
  }

  private TerminalMenu menu() {
    return screen.getMenu();
  }

  int gridX() {
    return MAIN + GRID_X;
  }

  int resultX() {
    return MAIN + GRID_X + 54 + 14;
  }

  private int resultY() {
    return GRID_Y + 16;
  }

  /** The mode row, ghost grid, result or counts, actions and Craft/×64. */
  void init() {
    gridTabs.clear();
    var font = screen.font();
    int left = screen.left(), top = screen.top();
    int x0 = MAIN;
    var modes = new int[3];
    for (int page = 0; page < 3; page++)
      modes[page] =
          font.width(Component.translatable("gui.factorycore.terminal.grid.mode." + page)) + 10;
    var modeCells =
        PanelLayout.fit(
            new Rect(x0 + 7, TabButton.STRIP + 7, SIDE - 14, PanelLayout.ROW), modes, 4);
    for (int page = 0; page < 3; page++) {
      int choice = page;
      var button =
          new ChoiceButton(
              modeCells.get(page).offset(left, top),
              Component.translatable("gui.factorycore.terminal.grid.mode." + page),
              b -> selectPage(choice));
      button.setTooltip(
          Tooltip.create(Component.translatable("gui.factorycore.terminal.grid.mode.help")));
      gridTabs.add(screen.add(button));
    }
    int column = x0 + SIDE - 7 - 72;
    useRow =
        gridButton(
            "use_row",
            column,
            GRID_Y,
            72,
            () ->
                grid(
                    TerminalCraftingPackets.SET_ROW,
                    target,
                    screen.selectedEntry(),
                    1,
                    new byte[32]));
    clearGrid =
        gridButton(
            "clear",
            column,
            GRID_Y + 22,
            72,
            () -> grid(TerminalCraftingPackets.CLEAR, -1, 0, 1, new byte[32]));
    encode =
        gridButton(
            "encode",
            column,
            GRID_Y + 44,
            72,
            () -> grid(TerminalCraftingPackets.ENCODE, -1, substitutes ? 1 : 0, 1, new byte[32]));
    // E1: whether the next encoded crafting pattern may use substitutes (client-side choice).
    var substitutesLabel = Component.translatable("gui.factorycore.terminal.grid.substitutes");
    substitutesButton =
        screen.add(
            new ToggleButton(
                left + x0 + GRID_X,
                top + GRID_Y + 54 + 4,
                ToggleButton.width(font, substitutesLabel),
                substitutesLabel,
                button -> {
                  substitutes = !substitutes;
                  substitutesButton.setOn(substitutes);
                }));
    substitutesButton.setOn(substitutes);
    substitutesButton.setTooltip(
        Tooltip.create(Component.translatable("gui.factorycore.terminal.grid.substitutes.help")));
    int counts = resultX();
    countDown =
        screen.add(
            Button.builder(Component.literal("−"), button -> changeCount(-1))
                .bounds(left + counts, top + GRID_Y, 22, 18)
                .tooltip(
                    Tooltip.create(
                        Component.translatable("gui.factorycore.terminal.grid.count.down")))
                .build());
    countUp =
        screen.add(
            Button.builder(Component.literal("+"), button -> changeCount(1))
                .bounds(left + counts, top + GRID_Y + 36, 22, 18)
                .tooltip(
                    Tooltip.create(
                        Component.translatable("gui.factorycore.terminal.grid.count.up")))
                .build());
    var bottom = PanelLayout.columns(new Rect(x0 + 7, CRAFT_Y, SIDE - 14, PanelLayout.ROW), 2, 4);
    craftOne =
        gridButton(
            "craft_one",
            bottom.get(0).x(),
            bottom.get(0).y(),
            bottom.get(0).width(),
            () -> grid(TerminalCraftingPackets.CRAFT, -1, 0, 1, new byte[32]));
    craftMany =
        gridButton(
            "craft_stack",
            bottom.get(1).x(),
            bottom.get(1).y(),
            bottom.get(1).width(),
            () -> grid(TerminalCraftingPackets.CRAFT, -1, 0, 64, new byte[32]));
  }

  private Button gridButton(String key, int x, int y, int width, Runnable action) {
    return screen.add(
        Button.builder(
                Component.translatable("gui.factorycore.terminal.grid." + key),
                button -> action.run())
            .bounds(screen.left() + x, screen.top() + y, width, 18)
            .tooltip(
                Tooltip.create(
                    Component.translatable("gui.factorycore.terminal.grid." + key + ".help")))
            .build());
  }

  private void grid(int action, int cell, int source, int crafts, byte[] fingerprint) {
    var menu = menu();
    net.neoforged.neoforge.network.PacketDistributor.sendToServer(
        new TerminalCraftingPackets.Command(
            menu.containerId,
            menu.session(),
            menu.view().revision(),
            menu.gridView().gridRevision(),
            action,
            cell,
            source,
            crafts,
            fingerprint));
  }

  private void selectPage(int page) {
    gridPage = page;
    target = page == 2 ? 9 : 0;
    int mode = page == 0 ? 0 : 1;
    if (menu().gridView().processing() != (mode == 1) || requestedMode >= 0) {
      requestedMode = mode;
      modeRevision = menu().gridView().gridRevision();
      grid(TerminalCraftingPackets.MODE, mode, 0, 1, new byte[32]);
    }
    update();
  }

  private void changeCount(int delta) {
    var view = menu().gridView();
    if (!screen.craftingOpen()
        || !view.processing()
        || view.cells().get(target).isEmpty()
        || menu().view().busy()) return;
    int count = Math.max(1, Math.min(64, view.counts().get(target) + delta));
    if (count != view.counts().get(target))
      grid(TerminalCraftingPackets.COUNT, target, 0, count, new byte[32]);
  }

  /**
   * Follows the server's grid: a recipe-viewer fill shows the Table page (and opens the panel), and
   * a confirmed mode change picks the matching page.
   */
  void tick() {
    var gridView = menu().gridView();
    if (gridView.gridRevision() != seenGridRevision && gridView.status().equals("filled")) {
      requestedMode = -1;
      gridPage = 0;
      target = 0;
    }
    if (requestedMode >= 0
        && gridView.gridRevision() > modeRevision
        && gridView.processing() == (requestedMode == 1)) requestedMode = -1;
    if (requestedMode < 0) {
      if (!gridView.processing() && gridPage != 0) {
        gridPage = 0;
        target = 0;
      }
      if (gridView.processing() && gridPage == 0) {
        gridPage = 1;
        target = 0;
      }
    }
    if (gridView.gridRevision() != seenGridRevision) {
      seenGridRevision = gridView.gridRevision();
      // Recipe-viewer fills must reveal their ghosts even when the panel was collapsed.
      if (!screen.craftingOpen() && gridView.status().equals("filled")) screen.openCrafting();
    }
    update();
  }

  void update() {
    var view = menu().view();
    var ghost = menu().gridView();
    boolean open = screen.craftingOpen();
    int kind = screen.kind();
    for (var button : List.of(useRow, craftOne, craftMany, clearGrid, encode))
      button.visible = open;
    for (int page = 0; page < gridTabs.size(); page++) {
      var tab = gridTabs.get(page);
      tab.visible = open;
      tab.setSelected(gridPage == page);
      tab.active = !view.busy() && gridPage != page;
    }
    useRow.active =
        !view.busy()
            && screen.windowSettled()
            && screen.selectedEntry() < view.rows().size()
            && kind != 2
            && view.kind() == kind
            && requestedMode < 0
            && (ghost.processing() || kind == 0);
    craftOne.active = open && !view.busy() && !ghost.processing() && !ghost.result().isEmpty();
    craftMany.active = craftOne.active;
    clearGrid.active = !view.busy();
    encode.active =
        !view.busy()
            && (ghost.processing()
                ? ghost.cells().subList(0, 9).stream().anyMatch(cell -> !cell.isEmpty())
                    && ghost.cells().subList(9, 12).stream().anyMatch(cell -> !cell.isEmpty())
                : !ghost.result().isEmpty());
    countDown.visible = countUp.visible = open && gridPage != 0;
    substitutesButton.visible = open && !ghost.processing();
    substitutesButton.active = !view.busy();
    boolean selected = !ghost.cells().get(target).isEmpty() && ghost.processing() && !view.busy();
    countDown.active = selected && ghost.counts().get(target) > 1;
    countUp.active = selected && ghost.counts().get(target) < 64;
  }

  /** Server cell index shown at a displayed 3×3 position, or -1 for an unused output-page cell. */
  private int cellIndex(int display) {
    if (gridPage == 2) return display < 3 ? 9 + display : -1;
    return display;
  }

  private int cellAt(double mouseX, double mouseY) {
    if (!screen.craftingOpen()) return -1;
    int column = (int) Math.floor((mouseX - screen.left() - gridX()) / 18);
    int row = (int) Math.floor((mouseY - screen.top() - GRID_Y) / 18);
    return column < 0 || column > 2 || row < 0 || row > (gridPage == 2 ? 0 : 2)
        ? -1
        : row * 3 + column;
  }

  // ---------------------------------------------------------------- drawing

  private Item ghostIcon(int index) {
    var view = menu().gridView();
    if (view.fluid(index)) {
      var fluid = BuiltInRegistries.FLUID.get(ResourceLocation.parse(view.cells().get(index)));
      return fluid.getBucket() == Items.AIR ? Items.BUCKET : fluid.getBucket();
    }
    var item = ResourceIcons.item(view.cells().get(index));
    return item == null ? Items.BARRIER : item;
  }

  private Component ghostAmount(int index) {
    var view = menu().gridView();
    return Component.translatable(
        view.fluid(index)
            ? "gui.factorycore.terminal.grid.amount.fluid"
            : view.amount(index) == 1
                ? "gui.factorycore.terminal.grid.amount.item.one"
                : "gui.factorycore.terminal.grid.amount.item",
        String.format(java.util.Locale.ROOT, "%,d", view.amount(index)));
  }

  /** The panel, the ghost cells (target outlined) and the result well. */
  void renderBg(GuiGraphics graphics) {
    int left = screen.left(), top = screen.top(), body = TabButton.STRIP;
    Panels.panel(graphics, new Rect(left + MAIN, top + body, SIDE, BOTTOM - body));
    var view = menu().gridView();
    for (int cell = 0; cell < 9; cell++) {
      int index = cellIndex(cell);
      if (index < 0) continue;
      int cellX = left + gridX() + 18 * (cell % 3), cellY = top + GRID_Y + 18 * (cell / 3);
      Panels.slot(graphics, cellX, cellY);
      if (index == target) Panels.selectedEdge(graphics, new Rect(cellX, cellY, 18, 18));
      if (view.cells().get(index).isEmpty()) continue;
      var shown = ghostIcon(index).getDefaultInstance();
      graphics.renderFakeItem(shown, cellX + 1, cellY + 1);
      if (view.processing())
        screen.drawCount(
            graphics, shown, cellX, cellY, CountText.compact(Integer.toString(view.amount(index))));
    }
    if (!view.processing()) {
      var font = screen.font();
      graphics.drawString(
          font, "→", left + gridX() + 54 + 3, top + resultY() + 7, Panels.TEXT, false);
      Panels.inset(graphics, new Rect(left + resultX(), top + resultY(), 22, 22));
      var output = ResourceIcons.item(view.result());
      if (output != null) {
        var shown = output.getDefaultInstance();
        shown.setCount(Math.max(1, view.resultCount()));
        graphics.renderFakeItem(shown, left + resultX() + 3, top + resultY() + 3);
        graphics.renderItemDecorations(font, shown, left + resultX() + 3, top + resultY() + 3);
      }
    }
  }

  /** The target cell's amount (processing) and the grid's status, if it needs attention. */
  void renderLabels(GuiGraphics graphics) {
    var view = menu().gridView();
    var font = screen.font();
    int x = MAIN + GRID_X, width = SIDE - 14;
    int y = STATUS_Y;
    if (gridPage != 0 && !view.cells().get(target).isEmpty()) {
      graphics.drawString(font, ghostAmount(target), x, GRID_Y + 54 + 6, Panels.TEXT, false);
    }
    if (!view.status().equals("empty") && !view.status().equals("ready")) {
      var status =
          Component.translatable(
              "gui.factorycore.terminal.grid.status." + view.status(), view.crafted());
      Panels.wrapped(graphics, font, status, new Rect(x, y, width, CRAFT_Y - 4 - y), Panels.WARN);
    }
  }

  /** A ghost cell's resource and amount, or the result; false when the mouse is on neither. */
  boolean renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
    var font = screen.font();
    int left = screen.left(), top = screen.top();
    if (cellAt(mouseX, mouseY) >= 0) {
      int cell = cellIndex(cellAt(mouseX, mouseY));
      var ghost = menu().gridView();
      var lines = new ArrayList<Component>();
      if (cell >= 0 && !ghost.cells().get(cell).isEmpty()) {
        if (ghost.fluid(cell))
          lines.add(
              new net.neoforged.neoforge.fluids.FluidStack(
                      BuiltInRegistries.FLUID.get(ResourceLocation.parse(ghost.cells().get(cell))),
                      ghost.amount(cell))
                  .getHoverName());
        else lines.add(ghostIcon(cell).getName(ghostIcon(cell).getDefaultInstance()));
        if (ghost.processing()) lines.add(ghostAmount(cell));
      }
      lines.add(Component.translatable("gui.factorycore.terminal.grid.cell.help"));
      graphics.renderTooltip(font, lines, Optional.empty(), mouseX, mouseY);
      return true;
    }
    if (screen.craftingOpen()
        && !menu().gridView().processing()
        && mouseX >= left + resultX()
        && mouseX < left + resultX() + 22
        && mouseY >= top + resultY()
        && mouseY < top + resultY() + 22
        && !menu().gridView().result().isEmpty()) {
      graphics.renderTooltip(
          font,
          List.of(
              Component.literal(menu().gridView().resultName()),
              Component.translatable(
                  "gui.factorycore.terminal.grid.status." + menu().gridView().status(),
                  menu().gridView().crafted()),
              Component.translatable("gui.factorycore.terminal.grid.result.help")),
          Optional.empty(),
          mouseX,
          mouseY);
      return true;
    }
    return false;
  }

  // ---------------------------------------------------------------- input

  /**
   * A cell click targets it (right-click clears it); Ctrl-click on an inventory stack puts it in
   * the target cell and moves the target on.
   */
  boolean mouseClicked(double mouseX, double mouseY, int button) {
    int cell = cellAt(mouseX, mouseY);
    if (cell >= 0) {
      int index = cellIndex(cell);
      if (index < 0) return true;
      if (button == 1) grid(TerminalCraftingPackets.CLEAR, index, 0, 1, new byte[32]);
      else {
        target = index;
        update();
      }
      return true;
    }
    var slot = screen.getSlotUnderMouse();
    var client = screen.client();
    if (screen.craftingOpen()
        && button == 0
        && Screen.hasControlDown()
        && slot != null
        && slot.container == client.player.getInventory()
        && slot.hasItem()) {
      try {
        var key = new PlatformResourceCodec(client.level.registryAccess()).itemKey(slot.getItem());
        grid(
            TerminalCraftingPackets.SET_OWN,
            target,
            slot.getContainerSlot(),
            1,
            TerminalPackets.fingerprint(key));
        target = gridPage == 2 ? 9 + (target - 8) % 3 : (target + 1) % 9;
      } catch (IllegalArgumentException unsupported) {
        client.player.displayClientMessage(
            Component.translatable("gui.factorycore.terminal.status.unsupported"), true);
      }
      return true;
    }
    return false;
  }

  /** In processing mode, scrolling over a cell changes its per-craft count (Shift: by 8). */
  boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
    int cell = cellAt(mouseX, mouseY);
    var grid = menu().gridView();
    if (cell >= 0 && grid.processing() && scrollY != 0) {
      int index = cellIndex(cell);
      if (index < 0 || grid.cells().get(index).isEmpty()) return true;
      target = index;
      changeCount((Screen.hasShiftDown() ? 8 : 1) * (scrollY > 0 ? 1 : -1));
      return true;
    }
    return false;
  }

  /** C crafts one, Shift+C a stack, when Craft is available. */
  boolean craftKey(int key) {
    if (key != GLFW.GLFW_KEY_C || !craftOne.active) return false;
    grid(TerminalCraftingPackets.CRAFT, -1, 0, Screen.hasShiftDown() ? 64 : 1, new byte[32]);
    return true;
  }
}
