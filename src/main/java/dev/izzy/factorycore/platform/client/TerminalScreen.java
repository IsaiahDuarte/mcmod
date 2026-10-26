package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.platform.client.ui.ChoiceButton;
import dev.izzy.factorycore.platform.client.ui.PanelLayout;
import dev.izzy.factorycore.platform.client.ui.Panels;
import dev.izzy.factorycore.platform.client.ui.Rect;
import dev.izzy.factorycore.platform.client.ui.TabButton;
import dev.izzy.factorycore.platform.client.ui.ToggleButton;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.TerminalCraftingPackets;
import dev.izzy.factorycore.platform.storage.TerminalJobPackets;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
import dev.izzy.factorycore.platform.storage.TerminalPackets;
import dev.izzy.factorycore.platform.storage.TerminalSearchSupport;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Native GUI; projected icons never act as container stacks or carry hidden item components.
 *
 * <p>Layout (playtest H2, ADR 0064): tabs on the panel's top edge; a search row; a 9-column grid
 * whose columns line up with the player inventory docked under it, like AE2/RS; an info row
 * (capacity or the tab's actions, Return, Crafting); the crafting grid opens as a panel on the
 * right. The grid has six rows, or fills the window height when Tall is on. The Jobs view uses the
 * whole area below the search row, hiding the inventory while it is shown.
 */
public final class TerminalScreen extends AbstractContainerScreen<TerminalMenu> {
  private static final int MAIN = 192, SIDE = 180, COLUMNS = 9;
  private static final int SEARCH_Y = TabButton.STRIP + 6;
  private static final int GRID_X = PanelLayout.EDGE, GRID_Y = SEARCH_Y + PanelLayout.ROW + 4;
  private static final int SCROLL_X = GRID_X + COLUMNS * 18 + 4, SCROLL_WIDTH = 12;

  /** Everything but the grid rows: search, info row, inventory, hotbar and edges. */
  private static final int FIXED =
      GRID_Y + 4 + PanelLayout.ROW + 4 + 54 + 4 + 18 + PanelLayout.EDGE;

  private static final int NORMAL_ROWS = 6, MIN_ROWS = 3;

  /** Jobs view: craftable strip, its scrollbar, three text lines and the job list. */
  private static final int STRIP_Y = GRID_Y, TEXT_Y = STRIP_Y + 24, JOBS_Y = TEXT_Y + 32;

  /** Crafting panel, relative to its own left edge; it is only as tall as its content. */
  private static final int SIDE_GRID_X = 7, SIDE_GRID_Y = TabButton.STRIP + 29;

  private static final int SIDE_STATUS_Y = SIDE_GRID_Y + 54 + 4 + ToggleButton.HEIGHT + 4,
      SIDE_CRAFT_Y = SIDE_STATUS_Y + 3 * PanelLayout.LINE + 4,
      SIDE_BOTTOM = SIDE_CRAFT_Y + PanelLayout.ROW + PanelLayout.EDGE;

  private EditBox search;
  private int selected;
  private long shownRevision;
  private Button one, stack, returning, useRow, craftOne, craftMany, clearGrid, encode;
  private ToggleButton substitutesButton;
  private boolean substitutes;
  private Button previewOne, previewEight, previewMany, startJob, keepJob;
  private Button cancelJob;
  private boolean jobsMode;

  /** Client-selected storage kind (0 items, 1 fluids, 2 energy); the server switches its search. */
  private int kind;

  private Button sortButton;
  private Button directionButton;
  private Button tallButton;

  /** Whether the grid was laid out tall; a synced preference change rebuilds the layout. */
  private boolean laidOutTall;

  /** A3: the "Show craftable" toggle, and a craftable asked for from the grid until it is shown. */
  private Button craftablesButton;

  private String pendingRequest;

  /**
   * A5: a recipe viewer asked to request this item in terminal menu {@code viewerMenu}; the
   * terminal screen picks it up when it is shown again. Client thread only.
   */
  private static int viewerMenu = -1;

  private static String viewerRequest;

  /** Whether the Jobs view is shown (for verification). */
  public boolean jobsMode() {
    return jobsMode;
  }

  /** Called by an optional recipe-viewer integration after it sent the request packet. */
  public static void requestFromViewer(int menu, String itemId) {
    viewerMenu = menu;
    viewerRequest = itemId;
  }

  private final List<AbstractButton> cells = new ArrayList<>();
  private final List<TabButton> tabs = new ArrayList<>();
  private int rows = 4, desiredOffset, clock, lastCommand = -20, searchDelay;
  private boolean dragging;

  public int columns() {
    return COLUMNS;
  }

  public int rows() {
    return rows;
  }

  /**
   * True once the server view matches the requested window. Row indices sent before that refer to a
   * stale view revision, which the server ignores.
   */
  public boolean windowSettled() {
    var view = menu.view();
    return view.size() == COLUMNS * rows && view.offset() == desiredOffset;
  }

  /** The crafting grid's top, relative to the panel. */
  public int gridY() {
    return SIDE_GRID_Y;
  }

  public int resultY() {
    return SIDE_GRID_Y + 16;
  }

  public int itemX(int index) {
    return leftPos + GRID_X + 1 + 18 * (index % COLUMNS);
  }

  public int itemY(int index) {
    return topPos + GRID_Y + 1 + 18 * (index / COLUMNS);
  }

  private String syncedSearch;
  private boolean searchDirty, syncingSearch;
  private Button craftingToggle, countDown, countUp;
  private final List<ChoiceButton> gridTabs = new ArrayList<>();
  private boolean craftingOpen;
  private long seenGridRevision;
  private int requestedMode = -1;
  private long modeRevision;

  public boolean craftingOpen() {
    return craftingOpen && !jobsMode;
  }

  /** The crafting panel's left edge, relative to the terminal's. */
  private int panelX() {
    return MAIN;
  }

  public int gridX() {
    return panelX() + SIDE_GRID_X;
  }

  public int resultX() {
    return panelX() + SIDE_GRID_X + 54 + 14;
  }

  /** 0 = crafting grid, 1 = processing inputs, 2 = processing outputs (first row = 3 outputs). */
  private int gridPage;

  private int target, craftSelected, jobSelected;

  /** A4: first listed job shown in the job list, and the opened job's step scroll. */
  private int jobScroll, detailOffset;

  private boolean showDetail;

  /** A2: the previewed plan's breakdown replaces the craftable strip and job list while shown. */
  private Button planButton;

  private boolean showPlan;
  private int planOffset;

  /** A1: the typed craft amount, and why it was last refused ("" when it parses). */
  private EditBox amountBox;

  private String amountError = "";

  /**
   * The craftable offset last requested, so fast wheel notches add up before the server replies.
   */
  private int pendingCraftOffset = -1;

  /**
   * Ticks left before an unconfirmed pending offset is dropped: the session's command rate gate may
   * discard a WINDOW request, and the strip must then scroll from what the server shows.
   */
  private int pendingCraftTicks;

  public TerminalScreen(TerminalMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = MAIN;
    imageHeight = FIXED + NORMAL_ROWS * 18;
  }

  // ---------------------------------------------------------------- geometry (panel-relative)

  private int infoY() {
    return GRID_Y + rows * 18 + 4;
  }

  private int inventoryTop() {
    return infoY() + PanelLayout.ROW + 4;
  }

  private int hotbarTop() {
    return inventoryTop() + 54 + 4;
  }

  /** Jobs view: the two button rows sit at the bottom; the job list fills the space above. */
  private int jobButtonsY() {
    return imageHeight - PanelLayout.EDGE - 2 * PanelLayout.ROW - 4;
  }

  private int jobsBottom() {
    return jobButtonsY() - 4;
  }

  private int jobRows() {
    return Math.max(1, (jobsBottom() - JOBS_Y) / 18);
  }

  /** The plan and job-step breakdowns cover the strip, the text and the job list. */
  private Rect overlay() {
    return new Rect(GRID_X, STRIP_Y, MAIN - 2 * GRID_X, jobsBottom() - STRIP_Y);
  }

  private int overlayLines() {
    return Math.max(1, (overlay().height() - 13) / 9);
  }

  /** Absolute x of the job list's rows, for verification clicks. */
  public int jobRowX() {
    return leftPos + GRID_X + 20;
  }

  /** Absolute y of the middle of visible job row {@code line}. */
  public int jobRowY(int line) {
    return topPos + JOBS_Y + 9 + 18 * line;
  }

  /** Absolute centre of the craftable strip, for verification scrolls. */
  public int craftableStripX() {
    return leftPos + GRID_X + 4 * 18;
  }

  public int craftableStripY() {
    return topPos + STRIP_Y + 9;
  }

  @Override
  protected void init() {
    String previousSearch = search == null ? menu.view().search() : search.getValue();
    laidOutTall = TerminalSearchSupport.tall(menu.view().sort());
    int fit = (height - 6 - FIXED) / 18;
    rows = Math.max(MIN_ROWS, Math.min(laidOutTall ? 256 / COLUMNS : NORMAL_ROWS, fit));
    desiredOffset = desiredOffset / COLUMNS * COLUMNS;
    imageWidth = craftingOpen() ? MAIN + SIDE : MAIN;
    imageHeight = FIXED + rows * 18;
    menu.layoutInventory(GRID_X + 1, inventoryTop() + 1, hotbarTop() + 1, () -> !jobsMode);
    super.init();
    cells.clear();
    tabs.clear();
    gridTabs.clear();

    // Tabs on the top edge.
    int tabX = PanelLayout.EDGE - 3;
    for (int tab = 0; tab < 4; tab++) {
      int choice = tab;
      var label = Component.translatable("gui.factorycore.terminal.tab." + tab);
      int width = TabButton.width(font, label);
      tabs.add(
          addRenderableWidget(
              new TabButton(
                  leftPos + tabX,
                  topPos,
                  width,
                  label,
                  button -> {
                    boolean previousJobs = jobsMode;
                    jobsMode = choice == 3;
                    if (!jobsMode) {
                      kind = choice;
                      desiredOffset = 0;
                    }
                    if (previousJobs != jobsMode) rebuildWidgets();
                  })));
      tabX += width + 1;
    }

    // Search row: search, then show-craftable, sort, direction and tall.
    var searchRow = new Rect(GRID_X, SEARCH_Y, MAIN - 2 * GRID_X, PanelLayout.ROW);
    var tools = PanelLayout.alignRight(searchRow, new int[] {16, 30, 14, 14}, 2);
    search =
        addRenderableWidget(
            new EditBox(
                font,
                leftPos + searchRow.x() + 1,
                topPos + searchRow.y() + 1,
                tools.getFirst().x() - 4 - searchRow.x() - 2,
                16,
                Component.translatable("gui.factorycore.terminal.search")));
    search.setMaxLength(128);
    search.setValue(previousSearch);
    search.setHint(Component.translatable("gui.factorycore.terminal.search"));
    search.setResponder(
        value -> {
          if (!syncingSearch) {
            searchDelay = 4;
            searchDirty = true;
          }
        });
    craftablesButton =
        addRenderableWidget(
            tool(
                tools.get(0),
                craftablesLabel(),
                "gui.factorycore.terminal.craftables.help",
                () ->
                    send(
                        TerminalPackets.CRAFTABLES,
                        menu.view().craftables() ? 0 : 1,
                        1,
                        new byte[32])));
    sortButton =
        addRenderableWidget(
            tool(
                tools.get(1),
                sortLabel(),
                "gui.factorycore.terminal.sort.help",
                () -> {
                  int sort = menu.view().sort();
                  int mode = (TerminalSearchSupport.mode(sort) + 1) % 3;
                  send(
                      TerminalPackets.SORT,
                      mode | (sort & ~TerminalSearchSupport.mode(sort)),
                      1,
                      new byte[32]);
                }));
    directionButton =
        addRenderableWidget(
            tool(
                tools.get(2),
                directionLabel(),
                "gui.factorycore.terminal.sort.direction.help",
                () ->
                    send(
                        TerminalPackets.SORT,
                        menu.view().sort() ^ TerminalSearchSupport.REVERSED,
                        1,
                        new byte[32])));
    tallButton =
        addRenderableWidget(
            tool(
                tools.get(3),
                Component.translatable("gui.factorycore.terminal.tall"),
                "gui.factorycore.terminal.tall.help",
                () ->
                    send(
                        TerminalPackets.SORT,
                        menu.view().sort() ^ TerminalSearchSupport.TALL,
                        1,
                        new byte[32])));

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
        addRenderableWidget(
            button(
                right.get(0),
                returnLabel,
                null,
                () -> send(TerminalPackets.RETURN, 0, 1, new byte[32])));
    craftingToggle =
        addRenderableWidget(
            button(
                right.get(1),
                crafting,
                null,
                () -> {
                  if (jobsMode) {
                    jobsMode = false;
                    craftingOpen = true;
                  } else craftingOpen = !craftingOpen;
                  rebuildWidgets();
                }));
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
        addRenderableWidget(
            button(
                new Rect(info.x(), info.y(), oneWidth, info.height()),
                oneLabel,
                null,
                () -> {
                  if (kind == 1) send(TerminalPackets.FILL, selected, 1, new byte[32]);
                  else
                    send(
                        TerminalPackets.CHARGE,
                        minecraft.player.getInventory().selected,
                        1,
                        new byte[32]);
                }));
    stack =
        addRenderableWidget(
            button(
                new Rect(info.x() + oneWidth + 4, info.y(), twoWidth, info.height()),
                twoLabel,
                null,
                () ->
                    send(
                        TerminalPackets.DISCHARGE,
                        minecraft.player.getInventory().selected,
                        1,
                        new byte[32])));

    for (int index = 0; index < COLUMNS * rows; index++) {
      int cell = index;
      cells.add(
          addRenderableWidget(
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
                  if (isFocused()) graphics.renderOutline(getX(), getY(), 18, 18, 0xff000000);
                }
              }));
    }
    addCraftingControls();
    addJobControls();
    updateCraftingControls();
  }

  private Button tool(Rect cell, Component label, String help, Runnable action) {
    return Button.builder(label, button -> action.run())
        .bounds(leftPos + cell.x(), topPos + cell.y(), cell.width(), cell.height())
        .tooltip(Tooltip.create(Component.translatable(help)))
        .build();
  }

  private Button button(Rect cell, Component label, String help, Runnable action) {
    var builder =
        Button.builder(label, button -> action.run())
            .bounds(leftPos + cell.x(), topPos + cell.y(), cell.width(), cell.height());
    if (help != null) builder.tooltip(Tooltip.create(Component.translatable(help)));
    return builder.build();
  }

  /** The crafting panel: mode row, ghost grid, result or counts, actions and Craft/×64. */
  private void addCraftingControls() {
    int x0 = panelX();
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
              modeCells.get(page).offset(leftPos, topPos),
              Component.translatable("gui.factorycore.terminal.grid.mode." + page),
              b -> selectGridPage(choice));
      button.setTooltip(
          Tooltip.create(Component.translatable("gui.factorycore.terminal.grid.mode.help")));
      gridTabs.add(addRenderableWidget(button));
    }
    int column = x0 + SIDE - 7 - 72;
    useRow =
        gridButton(
            "use_row",
            column,
            SIDE_GRID_Y,
            72,
            () -> grid(TerminalCraftingPackets.SET_ROW, target, selected, 1, new byte[32]));
    clearGrid =
        gridButton(
            "clear",
            column,
            SIDE_GRID_Y + 22,
            72,
            () -> grid(TerminalCraftingPackets.CLEAR, -1, 0, 1, new byte[32]));
    encode =
        gridButton(
            "encode",
            column,
            SIDE_GRID_Y + 44,
            72,
            () -> grid(TerminalCraftingPackets.ENCODE, -1, substitutes ? 1 : 0, 1, new byte[32]));
    // E1: whether the next encoded crafting pattern may use substitutes (client-side choice).
    var substitutesLabel = Component.translatable("gui.factorycore.terminal.grid.substitutes");
    substitutesButton =
        addRenderableWidget(
            new ToggleButton(
                leftPos + x0 + SIDE_GRID_X,
                topPos + SIDE_GRID_Y + 54 + 4,
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
        addRenderableWidget(
            Button.builder(Component.literal("−"), button -> changeCount(-1))
                .bounds(leftPos + counts, topPos + SIDE_GRID_Y, 22, 18)
                .tooltip(
                    Tooltip.create(
                        Component.translatable("gui.factorycore.terminal.grid.count.down")))
                .build());
    countUp =
        addRenderableWidget(
            Button.builder(Component.literal("+"), button -> changeCount(1))
                .bounds(leftPos + counts, topPos + SIDE_GRID_Y + 36, 22, 18)
                .tooltip(
                    Tooltip.create(
                        Component.translatable("gui.factorycore.terminal.grid.count.up")))
                .build());
    var bottom =
        PanelLayout.columns(new Rect(x0 + 7, SIDE_CRAFT_Y, SIDE - 14, PanelLayout.ROW), 2, 4);
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

  private Component sortLabel() {
    return Component.translatable(
        "gui.factorycore.terminal.sort." + TerminalSearchSupport.mode(menu.view().sort()));
  }

  /** Gold while craftable items without stock are shown in the grid. */
  private Component craftablesLabel() {
    return Component.literal("⚒")
        .withStyle(
            menu.view().craftables()
                ? net.minecraft.ChatFormatting.GOLD
                : net.minecraft.ChatFormatting.GRAY);
  }

  /**
   * Requests visible row {@code index} (a craftable): the server scrolls the Jobs list to it, then
   * this screen switches to Jobs, selects it and focuses the amount box.
   */
  private void request(int index) {
    var rows = menu.view().rows();
    if (index >= rows.size() || !rows.get(index).craftable()) return;
    pendingRequest = rows.get(index).icon();
    send(TerminalPackets.REQUEST, index, 1, new byte[32]);
    boolean previousJobs = jobsMode;
    jobsMode = true;
    showPlan = false;
    if (!previousJobs) rebuildWidgets();
  }

  /** The arrow shows the visible order: down is the mode's natural order, up is reversed. */
  private Component directionLabel() {
    return Component.literal(TerminalSearchSupport.reversed(menu.view().sort()) ? "↑" : "↓");
  }

  private void selectGridPage(int page) {
    gridPage = page;
    target = page == 2 ? 9 : 0;
    int mode = page == 0 ? 0 : 1;
    if (menu.gridView().processing() != (mode == 1) || requestedMode >= 0) {
      requestedMode = mode;
      modeRevision = menu.gridView().gridRevision();
      grid(TerminalCraftingPackets.MODE, mode, 0, 1, new byte[32]);
    }
    updateCraftingControls();
  }

  private void changeCount(int delta) {
    var view = menu.gridView();
    if (!craftingOpen()
        || !view.processing()
        || view.cells().get(target).isEmpty()
        || menu.view().busy()) return;
    int count = Math.max(1, Math.min(64, view.counts().get(target) + delta));
    if (count != view.counts().get(target))
      grid(TerminalCraftingPackets.COUNT, target, 0, count, new byte[32]);
  }

  private void updateCraftingControls() {
    var view = menu.view();
    var ghost = menu.gridView();
    boolean open = craftingOpen();
    for (var button : List.of(useRow, craftOne, craftMany, clearGrid, encode))
      button.visible = open;
    for (int page = 0; page < gridTabs.size(); page++) {
      var tab = gridTabs.get(page);
      tab.visible = open;
      tab.setSelected(gridPage == page);
      tab.active = !view.busy() && gridPage != page;
    }
    craftingToggle.setMessage(
        Component.translatable(
            open
                ? "gui.factorycore.terminal.grid.panel.close"
                : "gui.factorycore.terminal.grid.panel"));
    useRow.active =
        !view.busy()
            && windowSettled()
            && selected < view.rows().size()
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

  /** Jobs view: amounts, typed amount and Start on one row; Keep, Plan and Cancel on the next. */
  private void addJobControls() {
    int y = jobButtonsY();
    var first =
        PanelLayout.fit(
            new Rect(GRID_X, y, MAIN - 2 * GRID_X, PanelLayout.ROW),
            new int[] {20, 20, 26, 36, 34},
            3);
    previewOne =
        jobButton(
            "preview_one", first.get(0), () -> job(TerminalJobPackets.PREVIEW, craftSelected, 1));
    previewEight =
        jobButton(
            "preview_eight", first.get(1), () -> job(TerminalJobPackets.PREVIEW, craftSelected, 8));
    previewMany =
        jobButton(
            "preview_many", first.get(2), () -> job(TerminalJobPackets.PREVIEW, craftSelected, 64));
    String typed = amountBox == null ? "" : amountBox.getValue();
    var box = first.get(3);
    amountBox =
        addRenderableWidget(
            new EditBox(
                font,
                leftPos + box.x() + 1,
                topPos + box.y() + 1,
                box.width() - 2,
                16,
                Component.translatable("gui.factorycore.terminal.job.amount")));
    amountBox.setMaxLength(8);
    amountBox.setValue(typed);
    amountBox.setHint(Component.translatable("gui.factorycore.terminal.job.amount"));
    amountBox.setTooltip(
        Tooltip.create(
            Component.translatable(
                "gui.factorycore.terminal.job.amount.help", TerminalJobPackets.MAX_AMOUNT)));
    amountBox.setResponder(value -> amountError = "");
    startJob = jobButton("start", first.get(4), () -> job(TerminalJobPackets.START, 0, 1));
    var second =
        PanelLayout.columns(
            new Rect(GRID_X, y + PanelLayout.ROW + 4, MAIN - 2 * GRID_X, PanelLayout.ROW), 3, 4);
    keepJob =
        jobButton(
            "keep", second.get(0), () -> job(TerminalJobPackets.KEEP, craftSelected, keepAmount()));
    planButton =
        jobButton(
            "plan_view",
            second.get(1),
            () -> {
              if (detailShown()) {
                showDetail = false;
                job(TerminalJobPackets.DETAIL, 0, 2);
                return;
              }
              showPlan = !showPlan;
              planOffset = 0;
            });
    cancelJob =
        jobButton("cancel", second.get(2), () -> job(TerminalJobPackets.CANCEL, jobSelected, 1));
  }

  /**
   * Previews the typed amount; an empty, zero, non-numeric or over-limit entry is refused here with
   * a reason instead of being capped (the server bound is the same {@code MAX_AMOUNT}).
   */
  private void previewTyped() {
    var parsed = CraftAmount.parse(amountBox.getValue(), TerminalJobPackets.MAX_AMOUNT);
    amountError = parsed.error();
    amountBox.setTextColor(parsed.valid() ? 0xe0e0e0 : 0xff8080);
    if (parsed.valid() && craftSelected < menu.jobView().craftables().size())
      job(TerminalJobPackets.PREVIEW, craftSelected, parsed.amount());
  }

  /** Keep-stock threshold: the previewed amount, or 64 when nothing is previewed. */
  private int keepAmount() {
    var preview = menu.jobView().preview();
    try {
      return preview.amount().isEmpty() ? 64 : Integer.parseInt(preview.amount());
    } catch (NumberFormatException invalid) {
      return 64;
    }
  }

  private Button jobButton(String key, Rect cell, Runnable action) {
    return addRenderableWidget(
        ScreenButtons.labeled(
            "gui.factorycore.terminal.job.",
            key,
            leftPos + cell.x(),
            topPos + cell.y(),
            cell.width(),
            cell.height(),
            action));
  }

  private void job(int action, int selection, int amount) {
    PacketDistributor.sendToServer(
        new TerminalJobPackets.Command(
            menu.containerId,
            menu.session(),
            menu.jobView().revision(),
            action,
            selection,
            amount));
  }

  private Button gridButton(String key, int x, int y, int width, Runnable action) {
    return addRenderableWidget(
        Button.builder(
                Component.translatable("gui.factorycore.terminal.grid." + key),
                button -> action.run())
            .bounds(leftPos + x, topPos + y, width, 18)
            .tooltip(
                Tooltip.create(
                    Component.translatable("gui.factorycore.terminal.grid." + key + ".help")))
            .build());
  }

  private void grid(int action, int cell, int source, int crafts, byte[] fingerprint) {
    PacketDistributor.sendToServer(
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

  private int cellAt(double mouseX, double mouseY) {
    if (!craftingOpen()) return -1;
    int column = (int) Math.floor((mouseX - leftPos - gridX()) / 18);
    int row = (int) Math.floor((mouseY - topPos - gridY()) / 18);
    return column < 0 || column > 2 || row < 0 || row > (gridPage == 2 ? 0 : 2)
        ? -1
        : row * 3 + column;
  }

  private void send(int action, int selection, int amount, byte[] fingerprint) {
    lastCommand = clock;
    if (action == TerminalPackets.SORT) desiredOffset = 0;
    var view = menu.view();
    PacketDistributor.sendToServer(
        new TerminalPackets.Command(
            menu.containerId,
            menu.session(),
            view.revision(),
            action,
            selection,
            amount,
            search.getValue(),
            fingerprint));
  }

  @Override
  protected void containerTick() {
    super.containerTick();
    var view = menu.view();
    if (TerminalSearchSupport.tall(view.sort()) != laidOutTall) {
      rebuildWidgets();
      return;
    }
    clock++;
    desiredOffset = Math.min(desiredOffset, maxOffset());
    if (!view.search().equals(syncedSearch)) {
      if (!searchDirty) {
        syncingSearch = true;
        search.setValue(view.search());
        syncingSearch = false;
      } else if (search.getValue().equals(view.search())) searchDirty = false;
      syncedSearch = view.search();
    }
    if (searchDelay > 0) searchDelay--;
    if (!view.busy() && clock - lastCommand >= 4) {
      if (!jobsMode && view.kind() != kind) {
        send(TerminalPackets.KIND, kind, 1, new byte[32]);
        lastCommand = clock;
      } else if (searchDelay == 0 && !search.getValue().equals(view.search())) {
        send(TerminalPackets.SEARCH, 0, 1, new byte[32]);
        desiredOffset = 0;
        lastCommand = clock;
      } else if (!jobsMode
          && search.getValue().equals(view.search())
          && (view.size() != COLUMNS * rows || view.offset() != desiredOffset)) {
        send(TerminalPackets.WINDOW, desiredOffset, COLUMNS * rows, new byte[32]);
        lastCommand = clock;
      }
    }
    syncedSearch = view.search();
    for (int i = 0; i < tabs.size(); i++) tabs.get(i).setSelected((jobsMode ? 3 : kind) == i);
    for (int i = 0; i < cells.size(); i++) {
      var cell = cells.get(i);
      cell.visible = !jobsMode;
      cell.active = !view.busy() && i < view.rows().size() && view.kind() == kind;
      cell.setMessage(
          i < view.rows().size()
              ? Component.literal(view.rows().get(i).name())
              : Component.empty());
    }
    search.visible = true;
    sortButton.setMessage(sortLabel());
    sortButton.active = !view.busy();
    directionButton.setMessage(directionLabel());
    craftablesButton.setMessage(craftablesLabel());
    craftablesButton.visible = kind == 0 && !jobsMode;
    directionButton.active = !view.busy();
    tallButton.active = !view.busy();
    if (shownRevision != view.revision()) {
      selected = Math.min(selected, Math.max(0, view.rows().size() - 1));
      shownRevision = view.revision();
    }
    one.active = !view.busy() && (kind == 2 || !view.rows().isEmpty());
    stack.active = kind == 0 ? one.active : kind == 2;
    one.setMessage(Component.translatable("gui.factorycore.terminal.action." + kind + ".one"));
    stack.setMessage(Component.translatable("gui.factorycore.terminal.action." + kind + ".two"));

    returning.active =
        !view.busy() && !view.staged().equals("0") && !view.status().equals("uncertain");
    var gridView = menu.gridView();
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
      if (!craftingOpen() && gridView.status().equals("filled")) {
        craftingOpen = true;
        jobsMode = false;
        rebuildWidgets();
      }
    }
    updateCraftingControls();
    var jobs = menu.jobView();
    if (jobs.offset() == pendingCraftOffset || --pendingCraftTicks <= 0) pendingCraftOffset = -1;
    if (viewerRequest != null && viewerMenu == menu.containerId) {
      pendingRequest = viewerRequest;
      viewerRequest = null;
      boolean previousJobs = jobsMode;
      jobsMode = true;
      showPlan = false;
      if (!previousJobs) rebuildWidgets();
    }
    if (pendingRequest != null && jobsMode)
      for (int index = 0; index < jobs.craftables().size(); index++)
        if (jobs.craftables().get(index).icon().equals(pendingRequest)) {
          craftSelected = index;
          pendingRequest = null;
          setFocused(amountBox);
          amountBox.setFocused(true);
          break;
        }
    craftSelected = Math.min(craftSelected, Math.max(0, jobs.craftables().size() - 1));
    jobSelected = Math.min(jobSelected, Math.max(0, jobs.jobs().size() - 1));
    one.visible = !jobsMode && kind != 0;
    stack.visible = !jobsMode && kind == 2;
    returning.visible = !jobsMode && !view.staged().equals("0");
    // The crafting grid takes items or fluids, so the Energy tab has no Crafting button.
    craftingToggle.visible = !jobsMode && kind != 2;
    for (var item : List.of(previewOne, previewEight, previewMany, startJob, keepJob))
      item.visible = jobsMode;
    keepJob.active = craftSelected < jobs.craftables().size();
    amountBox.visible = jobsMode;
    if (jobs.preview().icon().isEmpty()) showPlan = false;
    if (jobs.detail().job().output().icon().isEmpty()) showDetail = false;
    jobScroll = Math.max(0, Math.min(jobScroll, jobs.jobs().size() - jobRows()));
    detailOffset = Math.max(0, Math.min(detailOffset, Math.max(0, detailLines() - overlayLines())));
    planButton.visible = jobsMode;
    planButton.active = detailShown() || !jobs.preview().icon().isEmpty();
    String planKey =
        "gui.factorycore.terminal.job." + (showPlan || detailShown() ? "plan_back" : "plan_view");
    planButton.setMessage(Component.translatable(planKey));
    planButton.setTooltip(Tooltip.create(Component.translatable(planKey + ".help")));
    planOffset = Math.max(0, Math.min(planOffset, Math.max(0, planLines() - overlayLines())));
    amountBox.setEditable(jobsMode);
    previewOne.active = craftSelected < jobs.craftables().size();
    previewEight.active = previewOne.active;
    previewMany.active = previewOne.active;
    startJob.active = !jobs.preview().icon().isEmpty() && jobs.previewReason().isEmpty();
    cancelJob.visible = jobsMode;
    cancelJob.active =
        detailShown()
            ? jobs.detail().cancellable() && !jobs.detail().job().state().equals("cancelling")
            : jobSelected < jobs.jobs().size()
                && jobs.jobs().get(jobSelected).owner().isEmpty()
                && !jobs.jobs().get(jobSelected).state().equals("cancelling");
  }

  // ---------------------------------------------------------------- drawing

  @Override
  protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
    int body = TabButton.STRIP;
    Panels.panel(graphics, new Rect(leftPos, topPos + body, MAIN, imageHeight - body));
    if (craftingOpen()) {
      Panels.panel(graphics, new Rect(leftPos + panelX(), topPos + body, SIDE, SIDE_BOTTOM - body));
      renderCraftingPanel(graphics);
    }
    if (jobsMode) {
      renderJobs(graphics);
      return;
    }
    for (var slot : menu.slots) Panels.slot(graphics, leftPos + slot.x - 1, topPos + slot.y - 1);
    var entries = menu.view().rows();
    for (int index = 0; index < COLUMNS * rows; index++) {
      int x = itemX(index) - 1, y = itemY(index) - 1;
      Panels.slot(graphics, x, y);
      if (index >= entries.size()) continue;
      if (index == selected) graphics.fill(x + 1, y + 1, x + 17, y + 17, 0x60b5683c);
      var value = entries.get(index);
      var shown = rowIcon(value.icon()).getDefaultInstance();
      if (kind != 1 || !ResourceIcons.renderFluid(graphics, value.icon(), x + 1, y + 1))
        graphics.renderItem(shown, x + 1, y + 1);
      if (value.craftable() && value.total().equals("0")) {
        // A ghost: nothing stored, but a pattern can make it.
        graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xa08b8b8b);
        renderCount(graphics, shown, x, y, "+");
        continue;
      }
      if (value.craftable()) graphics.fill(x + 1, y + 1, x + 4, y + 4, 0xffd9822b);
      renderCount(graphics, shown, x, y, compact(value.available()));
    }
    var view = menu.view();
    Panels.scrollbar(
        graphics,
        new Rect(leftPos + SCROLL_X, topPos + GRID_Y, SCROLL_WIDTH, rows * 18),
        COLUMNS * rows,
        view.total(),
        desiredOffset,
        maxOffset());
    if (kind == 0 && view.status().equals("ready")) {
      var bar = capacityBar();
      Panels.inset(graphics, bar.offset(leftPos, topPos));
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
      graphics.fill(
          leftPos + bar.x() + 1,
          topPos + bar.y() + 1,
          leftPos + bar.x() + 1 + filled,
          topPos + bar.bottom() - 1,
          0xff2f8fb5);
    }
  }

  /** The info row's free width on the left (Items tab): capacity text above a thin bar. */
  private int infoTextWidth() {
    int right = returning.visible ? returning.getX() : craftingToggle.getX();
    return right - leftPos - 4 - GRID_X;
  }

  private Rect capacityBar() {
    return new Rect(GRID_X, infoY() + 12, Math.max(10, infoTextWidth()), 5);
  }

  private void renderJobs(GuiGraphics graphics) {
    var jobs = menu.jobView();
    if (detailShown()) {
      renderDetail(graphics);
    } else if (planShown()) {
      renderPlan(graphics);
    } else {
      var craftables = jobs.craftables();
      for (int index = 0; index < TerminalJobPackets.ROWS; index++) {
        int x = leftPos + GRID_X + 18 * index, y = topPos + STRIP_Y;
        Panels.slot(graphics, x, y);
        if (index >= craftables.size()) continue;
        if (index == craftSelected) graphics.renderOutline(x, y, 18, 18, 0xff000000 | 0xb5683c);
        var item = icon(craftables.get(index).icon());
        if (item == null) continue;
        var shown = item.getDefaultInstance();
        graphics.renderItem(shown, x + 1, y + 1);
        var keep = craftables.get(index).amount();
        // "≥N" is the keep-stock amount; "!" marks one that is waiting.
        boolean waiting = !craftables.get(index).note().isEmpty();
        if (!keep.isEmpty())
          renderCount(graphics, shown, x, y, (waiting ? "!" : "≥") + compact(keep));
      }
      int track = TerminalJobPackets.ROWS * 18;
      var bar = new Rect(leftPos + GRID_X, topPos + STRIP_Y + 19, track, 4);
      Panels.inset(graphics, bar);
      int visible = TerminalJobPackets.ROWS;
      int thumb =
          jobs.total() <= visible ? track - 2 : Math.max(12, (track - 2) * visible / jobs.total());
      int thumbX =
          bar.x()
              + 1
              + (track - 2 - thumb)
                  * jobs.offset()
                  / Math.max(1, Math.max(0, jobs.total() - visible));
      graphics.fill(thumbX, bar.y() + 1, thumbX + thumb, bar.bottom() - 1, 0xff555555);
      var list = new Rect(leftPos + GRID_X, topPos + JOBS_Y, MAIN - 2 * GRID_X, jobRows() * 18);
      Panels.inset(graphics, list);
      var jobRows = jobs.jobs();
      for (int line = 0; line < jobRows() && jobScroll + line < jobRows.size(); line++) {
        int index = jobScroll + line;
        int y = list.y() + 18 * line;
        var row = jobRows.get(index);
        if (index == jobSelected)
          graphics.fill(list.x() + 1, y + 1, list.right() - 1, y + 17, 0x60b5683c);
        var item = icon(row.output().icon());
        if (item != null) graphics.renderItem(item.getDefaultInstance(), list.x() + 1, y + 1);
        String progress =
            compact(Long.toString(row.done()))
                + "/"
                + compact(Long.toString(row.total()))
                + " "
                + glyph(row.state())
                + " "
                + row.output().name();
        graphics.drawString(
            font,
            font.plainSubstrByWidth(progress, list.width() - 26),
            list.x() + 20,
            y + 5,
            !row.owner().isEmpty() ? 0xffe8e8e8 : row.reason().isEmpty() ? 0xffffffff : 0xffffd27f,
            true);
      }
      if (jobRows.size() > jobRows()) {
        int track2 = list.height() - 2;
        int thumb2 = Math.max(8, track2 * jobRows() / jobRows.size());
        int thumbY =
            list.y() + 1 + (track2 - thumb2) * jobScroll / Math.max(1, jobRows.size() - jobRows());
        graphics.fill(list.right() - 3, thumbY, list.right() - 1, thumbY + thumb2, 0xffe0e0e0);
      }
    }
  }

  private Item ghostIcon(int index) {
    var view = menu.gridView();
    if (view.fluid(index)) {
      var fluid = BuiltInRegistries.FLUID.get(ResourceLocation.parse(view.cells().get(index)));
      return fluid.getBucket() == Items.AIR ? Items.BUCKET : fluid.getBucket();
    }
    var item = icon(view.cells().get(index));
    return item == null ? Items.BARRIER : item;
  }

  private Component ghostAmount(int index) {
    var view = menu.gridView();
    return Component.translatable(
        view.fluid(index)
            ? "gui.factorycore.terminal.grid.amount.fluid"
            : view.amount(index) == 1
                ? "gui.factorycore.terminal.grid.amount.item.one"
                : "gui.factorycore.terminal.grid.amount.item",
        String.format(java.util.Locale.ROOT, "%,d", view.amount(index)));
  }

  private void renderCraftingPanel(GuiGraphics graphics) {
    var view = menu.gridView();
    for (int cell = 0; cell < 9; cell++) {
      int index = cellIndex(cell);
      if (index < 0) continue;
      int cellX = leftPos + gridX() + 18 * (cell % 3), cellY = topPos + gridY() + 18 * (cell / 3);
      Panels.slot(graphics, cellX, cellY);
      if (index == target) graphics.renderOutline(cellX, cellY, 18, 18, 0xffb5683c);
      if (view.cells().get(index).isEmpty()) continue;
      var shown = ghostIcon(index).getDefaultInstance();
      graphics.renderFakeItem(shown, cellX + 1, cellY + 1);
      if (view.processing())
        renderCount(graphics, shown, cellX, cellY, compact(Integer.toString(view.amount(index))));
    }
    if (!view.processing()) {
      graphics.drawString(
          font, "→", leftPos + gridX() + 54 + 3, topPos + resultY() + 7, Panels.TEXT, false);
      Panels.inset(graphics, new Rect(leftPos + resultX(), topPos + resultY(), 22, 22));
      var output = icon(view.result());
      if (output != null) {
        var shown = output.getDefaultInstance();
        shown.setCount(Math.max(1, view.resultCount()));
        graphics.renderFakeItem(shown, leftPos + resultX() + 3, topPos + resultY() + 3);
        graphics.renderItemDecorations(
            font, shown, leftPos + resultX() + 3, topPos + resultY() + 3);
      }
    }
  }

  /**
   * Draws the count in the slot's bottom-right corner above the icon (vanilla's overlay depth keeps
   * 3D block icons from hiding it). From GUI scale 2 the text is half size, as in AE2/RS, so it
   * covers only the corner; at scale 1 half-size glyphs would be under one pixel, so it stays full
   * size and shrinks only to fit the cell.
   */
  private void renderCount(
      GuiGraphics graphics, net.minecraft.world.item.ItemStack shown, int x, int y, String count) {
    double guiScale = minecraft == null ? 1 : minecraft.getWindow().getGuiScale();
    float fit = 16f / Math.max(1, font.width(count));
    float countScale = Math.min(guiScale >= 2 ? 0.5f : 1f, fit);
    graphics.pose().pushPose();
    // renderItemDecorations draws the text ending at (17, 17); pin that corner to the cell's.
    graphics.pose().translate(x + 17 - 17 * countScale, y + 17 - 17 * countScale, 0);
    graphics.pose().scale(countScale, countScale, 1);
    graphics.renderItemDecorations(font, shown, 0, 0, count);
    graphics.pose().popPose();
  }

  /** Text symbol accompanying color so job state is readable without color vision. */
  private static String glyph(String state) {
    return switch (state) {
      case "running" -> "▶";
      case "waiting" -> "⏸";
      case "delivering" -> "⇩";
      case "cancelling" -> "✕";
      default -> "?";
    };
  }

  /** Items show themselves; energy shows redstone; a fluid's bucket is the fallback icon. */
  private Item rowIcon(String id) {
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

  private static Item icon(String id) {
    if (id.isEmpty()) return null;
    var location = ResourceLocation.tryParse(id);
    return location == null || !BuiltInRegistries.ITEM.containsKey(location)
        ? Items.BARRIER
        : BuiltInRegistries.ITEM.get(location);
  }

  private static String compact(String decimal) {
    return CountText.compact(decimal);
  }

  /** The status lamp sits at the right end of the tab strip; its tooltip has the full status. */
  private Rect lamp() {
    return new Rect(MAIN - PanelLayout.EDGE - 8, 4, 8, 8);
  }

  private boolean online() {
    return menu.deviceStatus().network().equals("valid");
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    var lamp = lamp();
    graphics.fill(lamp.x(), lamp.y(), lamp.right(), lamp.bottom(), 0xff181a1d);
    graphics.fill(
        lamp.x() + 1,
        lamp.y() + 1,
        lamp.right() - 1,
        lamp.bottom() - 1,
        online() ? 0xff3fbf4f : 0xffe0a030);
    if (jobsMode) {
      renderJobLabels(graphics);
      return;
    }
    var view = menu.view();
    // Problems show over the grid, where the player is looking.
    if (!online() && !menu.deviceStatus().network().isEmpty())
      Panels.wrapped(
          graphics,
          font,
          StatusStrip.text(menu.deviceStatus()),
          new Rect(GRID_X + 4, GRID_Y + 4, COLUMNS * 18 - 8, 3 * PanelLayout.LINE),
          Panels.BAD);
    if (craftingOpen()) renderCraftingLabels(graphics);
    int textY = infoY() + 2;
    if (!view.status().equals("ready")) {
      graphics.drawString(
          font,
          font.plainSubstrByWidth(
              Component.translatable("gui.factorycore.terminal.status." + view.status())
                  .getString(),
              Math.max(10, infoTextWidth())),
          GRID_X + 1,
          textY + 3,
          Panels.WARN,
          false);
      return;
    }
    if (kind != 0) return;
    var capacityText =
        view.external()
            ? Component.translatable(
                "gui.factorycore.terminal.capacity.unknown", compact(view.used()))
            : view.infinite()
                ? Component.translatable(
                    "gui.factorycore.terminal.capacity.infinite", compact(view.used()))
                : Component.translatable(
                    "gui.factorycore.terminal.capacity",
                    compact(view.used()),
                    compact(view.capacity()));
    graphics.drawString(
        font,
        font.plainSubstrByWidth(capacityText.getString(), Math.max(10, infoTextWidth())),
        GRID_X + 1,
        textY,
        Panels.TEXT,
        false);
  }

  private void renderCraftingLabels(GuiGraphics graphics) {
    var view = menu.gridView();
    int x = panelX() + SIDE_GRID_X, width = SIDE - 14;
    int y = SIDE_STATUS_Y;
    if (gridPage != 0 && !view.cells().get(target).isEmpty()) {
      graphics.drawString(font, ghostAmount(target), x, SIDE_GRID_Y + 54 + 6, Panels.TEXT, false);
    }
    if (!view.status().equals("empty") && !view.status().equals("ready")) {
      var status =
          Component.translatable(
              "gui.factorycore.terminal.grid.status." + view.status(), view.crafted());
      Panels.wrapped(
          graphics, font, status, new Rect(x, y, width, SIDE_CRAFT_Y - 4 - y), Panels.WARN);
    }
  }

  private void renderJobLabels(GuiGraphics graphics) {
    var jobs = menu.jobView();
    if (planShown() || detailShown()) return;
    int width = MAIN - 2 * GRID_X;
    Component first;
    if (!jobs.preview().icon().isEmpty())
      first =
          Component.translatable(
              "gui.factorycore.terminal.job.preview",
              jobs.preview().amount(),
              jobs.preview().name());
    else if (craftSelected < jobs.craftables().size())
      first =
          Component.translatable(
              "gui.factorycore.terminal.job.selected", jobs.craftables().get(craftSelected).name());
    else first = Component.translatable("gui.factorycore.terminal.job.none");
    graphics.drawString(
        font,
        font.plainSubstrByWidth(first.getString(), width),
        GRID_X,
        TEXT_Y,
        Panels.TEXT,
        false);
    Component second = Component.empty();
    boolean missing = !jobs.missing().isEmpty();
    if (missing) {
      var shortage = jobs.missing().getFirst();
      second =
          Component.translatable(
              "gui.factorycore.terminal.job.missing",
              shortage.amount(),
              shortage.name(),
              jobs.missing().size());
    } else if (!jobs.preview().icon().isEmpty())
      second =
          Component.translatable(
              "gui.factorycore.terminal.job.plan", jobs.previewSteps(), jobs.previewCrafts());
    graphics.drawString(
        font,
        font.plainSubstrByWidth(second.getString(), width),
        GRID_X,
        TEXT_Y + 10,
        missing ? Panels.BAD : Panels.MUTED,
        false);
    graphics.drawString(
        font,
        font.plainSubstrByWidth(
            (amountError.isEmpty()
                    ? Component.translatable("gui.factorycore.terminal.job.status." + jobs.status())
                    : Component.translatable(
                        "gui.factorycore.terminal.job.amount." + amountError,
                        TerminalJobPackets.MAX_AMOUNT))
                .getString(),
            width),
        GRID_X,
        TEXT_Y + 20,
        amountError.isEmpty() ? Panels.WARN : Panels.BAD,
        false);
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
    super.render(graphics, mouseX, mouseY, partial);
    renderTooltip(graphics, mouseX, mouseY);
    var lamp = lamp().offset(leftPos, topPos);
    if (mouseX >= lamp.x() - 2
        && mouseX < lamp.right() + 2
        && mouseY >= lamp.y() - 2
        && mouseY < lamp.bottom() + 2)
      graphics.renderTooltip(font, StatusStrip.text(menu.deviceStatus()), mouseX, mouseY);
    if (jobsMode && (planShown() || detailShown())) {
      if (!planShown() || !overOverlay(mouseX, mouseY)) return;
      int index = planOffset + (mouseY - topPos - overlay().y() - 13) / 9;
      var plan = menu.jobView().plan();
      if (mouseY >= topPos + overlay().y() + 13 && index >= 0 && index < plan.size()) {
        var value = plan.get(index);
        graphics.renderTooltip(
            font,
            Component.translatable(
                "gui.factorycore.terminal.job.plan.row",
                value.item().name(),
                value.stored(),
                value.crafted(),
                value.missing()),
            mouseX,
            mouseY);
      }
      return;
    }
    int jobRow = jobRowAt(mouseX, mouseY);
    int strip = stripIndexAt(mouseX, mouseY);
    int row = jobsMode ? -1 : rowAt(mouseX, mouseY);
    if (jobsMode && jobRow >= 0) {
      var value = menu.jobView().jobs().get(jobRow);
      graphics.renderTooltip(
          font,
          List.of(
              Component.translatable(
                  "gui.factorycore.terminal.job.preview",
                  value.output().amount(),
                  value.output().name()),
              Component.translatable(
                  "gui.factorycore.terminal.job.progress", value.done(), value.total()),
              Component.translatable("gui.factorycore.terminal.job.state." + value.state()),
              value.reason().isEmpty()
                  ? Component.empty()
                  : Component.translatable("gui.factorycore.terminal.job.reason." + value.reason()),
              value.owner().isEmpty()
                  ? Component.translatable("gui.factorycore.terminal.job.detail.help")
                  : Component.translatable(
                      "gui.factorycore.terminal.job.detail.owner", value.owner())),
          Optional.empty(),
          mouseX,
          mouseY);
    } else if (jobsMode && strip >= 0 && strip < menu.jobView().craftables().size()) {
      var craftable = menu.jobView().craftables().get(strip);
      graphics.renderTooltip(
          font,
          List.of(
              Component.literal(craftable.name()),
              craftable.note().isEmpty()
                  ? Component.translatable("gui.factorycore.terminal.job.select.help")
                  : Component.translatable(
                      "gui.factorycore.terminal.job.keep.waiting",
                      Component.translatable(
                          "gui.factorycore.terminal.job.status." + craftable.note()))),
          Optional.empty(),
          mouseX,
          mouseY);
    } else if (!jobsMode && row >= 0 && row < menu.view().rows().size()) {
      var value = menu.view().rows().get(row);
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
                      ? value.total().equals("0")
                          ? "gui.factorycore.terminal.craftable.ghost"
                          : "gui.factorycore.terminal.craftable.stocked"
                      : "gui.factorycore.terminal.select.help" + (kind == 0 ? "" : "." + kind))),
          Optional.empty(),
          mouseX,
          mouseY);
    } else if (!jobsMode
        && kind == 0
        && mouseX >= leftPos + GRID_X
        && mouseX < leftPos + GRID_X + infoTextWidth()
        && mouseY >= topPos + infoY()
        && mouseY < topPos + infoY() + PanelLayout.ROW) {
      var view = menu.view();
      graphics.renderTooltip(
          font,
          List.of(
              view.status().equals("ready")
                  ? Component.translatable(
                      view.infinite()
                          ? "gui.factorycore.terminal.capacity.infinite"
                          : "gui.factorycore.terminal.capacity",
                      view.used(),
                      view.capacity())
                  : Component.translatable("gui.factorycore.terminal.status." + view.status()),
              Component.translatable(
                  view.external()
                      ? "gui.factorycore.terminal.capacity.external"
                      : "gui.factorycore.terminal.capacity.cells")),
          Optional.empty(),
          mouseX,
          mouseY);
    } else if (cellAt(mouseX, mouseY) >= 0) {
      int cell = cellIndex(cellAt(mouseX, mouseY));
      var ghost = menu.gridView();
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
    } else if (craftingOpen()
        && !menu.gridView().processing()
        && mouseX >= leftPos + resultX()
        && mouseX < leftPos + resultX() + 22
        && mouseY >= topPos + resultY()
        && mouseY < topPos + resultY() + 22
        && !menu.gridView().result().isEmpty()) {
      graphics.renderTooltip(
          font,
          List.of(
              Component.literal(menu.gridView().resultName()),
              Component.translatable(
                  "gui.factorycore.terminal.grid.status." + menu.gridView().status(),
                  menu.gridView().crafted()),
              Component.translatable("gui.factorycore.terminal.grid.result.help")),
          Optional.empty(),
          mouseX,
          mouseY);
    } else if (!jobsMode
        && hoveredSlot != null
        && hoveredSlot.container == minecraft.player.getInventory()
        && !hoveredSlot.hasItem())
      graphics.renderTooltip(
          font, Component.translatable("gui.factorycore.terminal.deposit.help"), mouseX, mouseY);
  }

  // ---------------------------------------------------------------- input

  private boolean overGridScrollbar(double mouseX, double mouseY) {
    return mouseY >= topPos + GRID_Y
        && mouseY < topPos + GRID_Y + rows * 18
        && mouseX >= leftPos + SCROLL_X
        && mouseX < leftPos + SCROLL_X + SCROLL_WIDTH;
  }

  /** The visible job row under the mouse as an index into the job list, or -1. */
  private int jobRowAt(double mouseX, double mouseY) {
    if (!jobsMode || planShown() || detailShown()) return -1;
    if (mouseX < leftPos + GRID_X || mouseX >= leftPos + MAIN - GRID_X) return -1;
    int line = (int) Math.floor((mouseY - topPos - JOBS_Y) / 18);
    if (mouseY < topPos + JOBS_Y || line >= jobRows()) return -1;
    int index = jobScroll + line;
    return index < menu.jobView().jobs().size() ? index : -1;
  }

  /** The craftable strip cell under the mouse, or -1. */
  private int stripIndexAt(double mouseX, double mouseY) {
    if (!jobsMode || planShown() || detailShown()) return -1;
    if (mouseY < topPos + STRIP_Y || mouseY >= topPos + STRIP_Y + 18) return -1;
    int index = (int) Math.floor((mouseX - leftPos - GRID_X) / 18);
    return mouseX < leftPos + GRID_X || index >= TerminalJobPackets.ROWS ? -1 : index;
  }

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    if (!jobsMode && overGridScrollbar(mouseX, mouseY)) {
      dragging = true;
      scrollTo(mouseY);
      return true;
    }
    int row = jobsMode ? -1 : rowAt(mouseX, mouseY);
    // A3: middle-click any craftable, or click a zero-count craftable, to request it.
    if (row >= 0 && kind == 0 && row < menu.view().rows().size()) {
      var value = menu.view().rows().get(row);
      boolean ghost = value.craftable() && value.total().equals("0");
      if (value.craftable() && (button == 2 || ghost && menu.getCarried().isEmpty())) {
        request(row);
        return true;
      }
    }
    if (row >= 0 && (button == 0 || button == 1)) {
      clickRow(row, button);
      return true;
    }

    // The plan breakdown and job steps cover the strip and list; clicks there do nothing.
    if (jobsMode && (planShown() || detailShown()) && overOverlay(mouseX, mouseY)) return true;
    if (jobsMode
        && button == 0
        && mouseY >= topPos + STRIP_Y + 18
        && mouseY < topPos + STRIP_Y + 24
        && mouseX >= leftPos + GRID_X
        && mouseX < leftPos + GRID_X + TerminalJobPackets.ROWS * 18) {
      // Clicking the scrollbar track jumps there, as on the Items grid.
      int range = Math.max(0, menu.jobView().total() - TerminalJobPackets.ROWS);
      scrollCraftables(
          (int)
              Math.round(
                  (mouseX - leftPos - GRID_X) / (double) (TerminalJobPackets.ROWS * 18) * range));
      return true;
    }
    int strip = stripIndexAt(mouseX, mouseY);
    if (button == 0 && strip >= 0) {
      if (strip < menu.jobView().craftables().size()) craftSelected = strip;
      return true;
    }
    int jobRow = jobRowAt(mouseX, mouseY);
    if (button == 0 && jobRow >= 0) {
      jobSelected = jobRow;
      // A4: clicking a job opens its steps; the server keeps it open while it is listed.
      showDetail = true;
      showPlan = false;
      detailOffset = 0;
      job(TerminalJobPackets.DETAIL, jobRow, 1);
      return true;
    }
    int cell = cellAt(mouseX, mouseY);
    if (cell >= 0) {
      int index = cellIndex(cell);
      if (index < 0) return true;
      if (button == 1) grid(TerminalCraftingPackets.CLEAR, index, 0, 1, new byte[32]);
      else {
        target = index;
        updateCraftingControls();
      }
      return true;
    }
    var slot = getSlotUnderMouse();
    if (craftingOpen()
        && button == 0
        && hasControlDown()
        && slot != null
        && slot.container == minecraft.player.getInventory()
        && slot.hasItem()) {
      try {
        var key =
            new PlatformResourceCodec(minecraft.level.registryAccess()).itemKey(slot.getItem());
        grid(
            TerminalCraftingPackets.SET_OWN,
            target,
            slot.getContainerSlot(),
            1,
            TerminalPackets.fingerprint(key));
        target = gridPage == 2 ? 9 + (target - 8) % 3 : (target + 1) % 9;
      } catch (IllegalArgumentException unsupported) {
        minecraft.player.displayClientMessage(
            Component.translatable("gui.factorycore.terminal.status.unsupported"), true);
      }
      return true;
    }
    if (button == 0
        && hasShiftDown()
        && slot != null
        && slot.container == minecraft.player.getInventory()
        && slot.hasItem()
        && mouseX >= leftPos + slot.x
        && mouseX < leftPos + slot.x + 16
        && mouseY >= topPos + slot.y
        && mouseY < topPos + slot.y + 16) {
      if (kind == 1) {
        send(TerminalPackets.EMPTY, slot.getContainerSlot(), 1, new byte[32]);
        return true;
      }
      if (kind == 2) {
        send(TerminalPackets.DISCHARGE, slot.getContainerSlot(), 1, new byte[32]);
        return true;
      }
      try {
        var key =
            new PlatformResourceCodec(minecraft.level.registryAccess()).itemKey(slot.getItem());
        send(
            TerminalPackets.DEPOSIT,
            slot.getContainerSlot(),
            Math.min(64, slot.getItem().getCount()),
            TerminalPackets.fingerprint(key));
      } catch (IllegalArgumentException unsupported) {
        minecraft.player.displayClientMessage(
            Component.translatable("gui.factorycore.terminal.status.unsupported"), true);
      }
      return true;
    }
    return super.mouseClicked(mouseX, mouseY, button);
  }

  /**
   * Scrolls the craftable strip to {@code offset}, keeping the same craftable selected while it
   * stays visible; the server clamps the offset to the last full window.
   */
  private void scrollCraftables(int offset) {
    int from = craftOffset();
    int to = Math.max(0, Math.min(offset, menu.jobView().total() - TerminalJobPackets.ROWS));
    if (to == from) return;
    craftSelected = Math.max(0, Math.min(TerminalJobPackets.ROWS - 1, craftSelected - (to - from)));
    pendingCraftOffset = Math.min(to, TerminalJobPackets.MAX_OFFSET);
    pendingCraftTicks = 20;
    job(TerminalJobPackets.WINDOW, pendingCraftOffset, 1);
  }

  private int craftOffset() {
    return pendingCraftOffset >= 0 ? pendingCraftOffset : menu.jobView().offset();
  }

  private boolean planShown() {
    return showPlan && !detailShown() && !menu.jobView().preview().icon().isEmpty();
  }

  private boolean detailShown() {
    return showDetail && !menu.jobView().detail().job().output().icon().isEmpty();
  }

  private int detailLines() {
    var detail = menu.jobView().detail();
    return detail.steps().size() + (detail.moreSteps() > 0 ? 1 : 0);
  }

  /**
   * A4 crafting monitor: the opened job's steps with crafts done/total and whether each is done,
   * running, waiting (with the job's reason) or queued. Cancel acts on this job when allowed.
   */
  private void renderDetail(GuiGraphics graphics) {
    var detail = menu.jobView().detail();
    var job = detail.job();
    var area = overlay().offset(leftPos, topPos);
    Panels.inset(graphics, area);
    int x = area.x(), y = area.y(), width = area.width() - 6;
    String header =
        Component.translatable(
                    "gui.factorycore.terminal.job.preview",
                    job.output().amount(),
                    job.output().name())
                .getString()
            + " · "
            + Component.translatable("gui.factorycore.terminal.job.state." + job.state())
                .getString()
            + (job.owner().isEmpty()
                ? ""
                : " · "
                    + Component.translatable(
                            "gui.factorycore.terminal.job.detail.owner", job.owner())
                        .getString());
    graphics.drawString(font, font.plainSubstrByWidth(header, width), x + 3, y + 3, 0xffffff, true);
    // Why the job waits gets a full line; the waiting step's row then just says "Waiting".
    int top = 13;
    if (!job.reason().isEmpty()) {
      graphics.drawString(
          font,
          font.plainSubstrByWidth(
              Component.translatable("gui.factorycore.terminal.job.reason." + job.reason())
                  .getString(),
              width),
          x + 3,
          y + 13,
          0xffd27f,
          true);
      top = 23;
    }
    int nameWidth = width / 2 - 6, progressRight = x + width / 2 + 28;
    for (int line = 0; line < overlayLines() - (top - 13) / 9; line++) {
      int index = detailOffset + line, rowY = y + top + 9 * line;
      if (index < detail.steps().size()) {
        var step = detail.steps().get(index);
        int color =
            switch (step.status()) {
              case "done" -> 0xb4f0a8;
              case "waiting" -> 0xffd27f;
              case "running" -> 0xffffff;
              default -> 0xe0e0e0;
            };
        String status =
            Component.translatable("gui.factorycore.terminal.job.step." + step.status())
                .getString();
        graphics.drawString(
            font,
            font.plainSubstrByWidth(step.output().name(), nameWidth),
            x + 3,
            rowY,
            color,
            true);
        String progress =
            compact(Long.toString(step.done())) + "/" + compact(Long.toString(step.total()));
        graphics.drawString(
            font, progress, progressRight - font.width(progress), rowY, color, true);
        graphics.drawString(
            font,
            font.plainSubstrByWidth(status, x + width - progressRight - 4),
            progressRight + 4,
            rowY,
            color,
            true);
      } else if (index == detail.steps().size() && detail.moreSteps() > 0)
        graphics.drawString(
            font,
            Component.translatable("gui.factorycore.terminal.job.detail.more", detail.moreSteps()),
            x + 3,
            rowY,
            0xe0e0e0,
            true);
    }
  }

  /** Breakdown lines: one per row, "and N more" if any, then one per cycle explanation. */
  private int planLines() {
    var jobs = menu.jobView();
    return jobs.plan().size() + (jobs.planMore() > 0 ? 1 : 0) + jobs.cycles().size();
  }

  /** Right edges of the Stored, Craft and Missing columns, from the overlay's left. */
  private int[] planColumns() {
    int width = overlay().width() - 4;
    return new int[] {width - 96, width - 50, width};
  }

  /**
   * A2: per resource, units taken from stock, crafted and missing. Missing rows are red and come
   * first; Start stays disabled only while something is missing. Exact counts are in the tooltip.
   */
  private void renderPlan(GuiGraphics graphics) {
    var jobs = menu.jobView();
    var area = overlay().offset(leftPos, topPos);
    Panels.inset(graphics, area);
    int x = area.x(), y = area.y();
    var columns = planColumns();
    graphics.drawString(
        font,
        font.plainSubstrByWidth(
            Component.translatable(
                    "gui.factorycore.terminal.job.preview",
                    jobs.preview().amount(),
                    jobs.preview().name())
                .getString(),
            columns[0] - 30),
        x + 3,
        y + 3,
        0xffffff,
        true);
    for (var column : new int[] {0, 1, 2}) {
      var label = Component.translatable("gui.factorycore.terminal.job.plan.column." + column);
      graphics.drawString(
          font, label, x + columns[column] - font.width(label), y + 3, 0xe0e0e0, true);
    }
    for (int line = 0; line < overlayLines(); line++) {
      int index = planOffset + line, rowY = y + 13 + 9 * line;
      if (index < jobs.plan().size()) {
        var row = jobs.plan().get(index);
        boolean short_ = row.missing() > 0;
        if (short_) graphics.fill(x + 1, rowY - 1, area.right() - 1, rowY + 8, 0xff8a3a3a);
        graphics.drawString(
            font,
            font.plainSubstrByWidth(row.item().name(), columns[0] - 40),
            x + 3,
            rowY,
            short_ ? 0xffd0d0 : 0xffffff,
            true);
        long[] values = {row.stored(), row.crafted(), row.missing()};
        for (int column = 0; column < 3; column++) {
          String text = values[column] == 0 ? "·" : compact(Long.toString(values[column]));
          graphics.drawString(
              font,
              text,
              x + columns[column] - font.width(text),
              rowY,
              column == 2 && short_ ? 0xffb0b0 : 0xffffff,
              true);
        }
        continue;
      }
      int extra = index - jobs.plan().size();
      if (jobs.planMore() > 0 && extra == 0) {
        graphics.drawString(
            font,
            Component.translatable("gui.factorycore.terminal.job.plan.more", jobs.planMore()),
            x + 3,
            rowY,
            0xe0e0e0,
            true);
        continue;
      }
      int cycle = extra - (jobs.planMore() > 0 ? 1 : 0);
      if (cycle >= 0 && cycle < jobs.cycles().size())
        graphics.drawString(
            font,
            font.plainSubstrByWidth(
                Component.translatable(
                        "gui.factorycore.terminal.job.plan.cycle", jobs.cycles().get(cycle))
                    .getString(),
                area.width() - 6),
            x + 3,
            rowY,
            0xffd27f,
            true);
    }
  }

  private boolean overOverlay(double mouseX, double mouseY) {
    var area = overlay().offset(leftPos, topPos);
    return mouseX >= area.x()
        && mouseX < area.right()
        && mouseY >= area.y()
        && mouseY < area.bottom();
  }

  private boolean overCraftables(double mouseX, double mouseY) {
    return mouseX >= leftPos + GRID_X
        && mouseX < leftPos + GRID_X + TerminalJobPackets.ROWS * 18
        && mouseY >= topPos + STRIP_Y
        && mouseY < topPos + STRIP_Y + 24;
  }

  private boolean overJobList(double mouseX, double mouseY) {
    return mouseX >= leftPos + GRID_X
        && mouseX < leftPos + MAIN - GRID_X
        && mouseY >= topPos + JOBS_Y
        && mouseY < topPos + JOBS_Y + jobRows() * 18;
  }

  /**
   * The Items grid and the Jobs craftable strip scroll (Shift: a full strip of eight in Jobs); in
   * processing mode, scrolling over a cell changes its per-craft count (Shift: by 8).
   */
  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    if (jobsMode && detailShown() && overOverlay(mouseX, mouseY) && scrollY != 0) {
      detailOffset =
          Math.max(
              0,
              Math.min(
                  Math.max(0, detailLines() - overlayLines()),
                  detailOffset + (scrollY > 0 ? -1 : 1)));
      return true;
    }
    if (jobsMode && planShown() && overOverlay(mouseX, mouseY) && scrollY != 0) {
      planOffset =
          Math.max(
              0,
              Math.min(
                  Math.max(0, planLines() - overlayLines()), planOffset + (scrollY > 0 ? -1 : 1)));
      return true;
    }
    if (jobsMode && overJobList(mouseX, mouseY) && scrollY != 0) {
      jobScroll =
          Math.max(
              0,
              Math.min(
                  menu.jobView().jobs().size() - jobRows(), jobScroll + (scrollY > 0 ? -1 : 1)));
      return true;
    }
    if (jobsMode && overCraftables(mouseX, mouseY) && scrollY != 0) {
      int step = hasShiftDown() ? TerminalJobPackets.ROWS : 1;
      scrollCraftables(craftOffset() + (scrollY > 0 ? -step : step));
      return true;
    }
    if (!jobsMode
        && (rowAt(mouseX, mouseY) >= 0 || overGridScrollbar(mouseX, mouseY))
        && scrollY != 0) {
      desiredOffset =
          Math.max(0, Math.min(maxOffset(), desiredOffset + (scrollY > 0 ? -COLUMNS : COLUMNS)));
      return true;
    }
    int cell = cellAt(mouseX, mouseY);
    var grid = menu.gridView();
    if (cell >= 0 && grid.processing() && scrollY != 0) {
      int index = cellIndex(cell);
      if (index < 0 || grid.cells().get(index).isEmpty()) return true;
      target = index;
      changeCount((hasShiftDown() ? 8 : 1) * (scrollY > 0 ? 1 : -1));
      return true;
    }
    return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
  }

  private int rowAt(double mouseX, double mouseY) {
    int x = (int) Math.floor((mouseX - leftPos - GRID_X) / 18);
    int y = (int) Math.floor((mouseY - topPos - GRID_Y) / 18);
    return x < 0 || x >= COLUMNS || y < 0 || y >= rows ? -1 : x + COLUMNS * y;
  }

  private void clickRow(int index, int button) {
    var view = menu.view();
    if (view.busy() || view.kind() != kind) return;
    if (!menu.getCarried().isEmpty() && kind == 0) {
      try {
        var key =
            new PlatformResourceCodec(minecraft.level.registryAccess()).itemKey(menu.getCarried());
        send(
            TerminalPackets.DEPOSIT_CURSOR,
            0,
            Math.min(64, menu.getCarried().getCount()),
            TerminalPackets.fingerprint(key));
      } catch (IllegalArgumentException unsupported) {
        minecraft.player.displayClientMessage(
            Component.translatable("gui.factorycore.terminal.status.unsupported"), true);
      }
    } else if (index < view.rows().size()) {
      selected = index;
      if (kind == 0 && !hasControlDown())
        send(
            hasShiftDown()
                ? TerminalPackets.WITHDRAW
                : button == 1 ? TerminalPackets.HALF_CURSOR : TerminalPackets.TAKE_CURSOR,
            index,
            64,
            new byte[32]);
    }
  }

  private int maxOffset() {
    return Math.max(0, ((menu.view().total() + COLUMNS - 1) / COLUMNS - rows) * COLUMNS);
  }

  private void scrollTo(double y) {
    double fraction = Math.max(0, Math.min(1, (y - topPos - GRID_Y) / (rows * 18)));
    desiredOffset = (int) Math.round(fraction * maxOffset() / COLUMNS) * COLUMNS;
  }

  @Override
  public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
    if (dragging) {
      scrollTo(y);
      return true;
    }
    return super.mouseDragged(x, y, button, dx, dy);
  }

  @Override
  public boolean mouseReleased(double x, double y, int button) {
    dragging = false;
    return super.mouseReleased(x, y, button);
  }

  @Override
  public boolean keyPressed(int key, int scan, int modifiers) {
    if (search.isFocused() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
      searchDelay = 0;
      return true;
    }
    if (amountBox.isFocused() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
      previewTyped();
      return true;
    }
    // Digits and C are typed into the amount box, not taken as shortcuts.
    if (amountBox.isFocused() && key != GLFW.GLFW_KEY_ESCAPE)
      return amountBox.keyPressed(key, scan, modifiers) || amountBox.canConsumeInput();
    if (!search.isFocused() && key == GLFW.GLFW_KEY_C && craftOne.active) {
      grid(TerminalCraftingPackets.CRAFT, -1, 0, hasShiftDown() ? 64 : 1, new byte[32]);
      return true;
    }
    if (!search.isFocused() && key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_8) {
      int index = key - GLFW.GLFW_KEY_1;
      if (jobsMode) {
        if (index < menu.jobView().craftables().size()) craftSelected = index;
      } else if (index < menu.view().rows().size()) selected = index;
      return true;
    }
    return super.keyPressed(key, scan, modifiers);
  }
}
