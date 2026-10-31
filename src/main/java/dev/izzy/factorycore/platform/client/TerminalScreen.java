package dev.izzy.factorycore.platform.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.izzy.factorycore.platform.client.ui.PanelLayout;
import dev.izzy.factorycore.platform.client.ui.Panels;
import dev.izzy.factorycore.platform.client.ui.Rect;
import dev.izzy.factorycore.platform.client.ui.TabButton;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
import dev.izzy.factorycore.platform.storage.TerminalPackets;
import dev.izzy.factorycore.platform.storage.TerminalSearchSupport;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
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
 *
 * <p>This class is the shell: tabs, search row, status lamp, mode switches and the terminal command
 * packet. {@link TerminalStorageView} draws the Items/Fluids/Energy grid and info row, {@link
 * TerminalCraftingPanel} the crafting panel, and {@link TerminalJobsView} the Jobs view. Each part
 * adds its widgets in {@link #init} and is asked in turn for drawing, tooltips and input, in the
 * order the single-class screen used.
 */
public final class TerminalScreen extends AbstractContainerScreen<TerminalMenu> {
  static final int MAIN = 192, SIDE = 180, COLUMNS = 9;
  static final int SEARCH_Y = TabButton.STRIP + 6;
  static final int GRID_X = PanelLayout.EDGE, GRID_Y = SEARCH_Y + PanelLayout.ROW + 4;

  /** Everything but the grid rows: search, info row, inventory, hotbar and edges. */
  private static final int FIXED =
      GRID_Y + 4 + PanelLayout.ROW + 4 + 54 + 4 + 18 + PanelLayout.EDGE;

  private static final int NORMAL_ROWS = 6, MIN_ROWS = 3;

  private final TerminalStorageView storage = new TerminalStorageView(this);
  private final TerminalCraftingPanel crafting = new TerminalCraftingPanel(this);
  private final TerminalJobsView jobs = new TerminalJobsView(this);
  private final TerminalPatternsView patterns = new TerminalPatternsView(this);

  private EditBox search;
  private boolean jobsMode;

  /** Q3: the Patterns view replaces the grid; the inventory stays, to carry patterns. */
  private boolean patternsMode;

  /** The tabs: Items, Fluids, Energy (storage kinds 0–2), Jobs, then Patterns. */
  private static final int JOBS_TAB = 3, PATTERNS_TAB = 4;

  /** Client-selected storage kind (0 items, 1 fluids, 2 energy); the server switches its search. */
  private int kind;

  private Button sortButton;
  private Button directionButton;
  private Button tallButton;

  /** Q17: the recipe-viewer sync tool (null without a viewer) and the texts last mirrored. */
  private Button syncButton;

  private String viewerOurs, viewerTheirs;

  /** Q61: the search box's token colours, and recent searches (newest first, this session). */
  private final SearchHighlight highlight = new SearchHighlight();

  private static final java.util.ArrayDeque<String> HISTORY = new java.util.ArrayDeque<>();
  private static final int HISTORY_SIZE = 16;
  private int historyAt = -1;

  /** Q61: remembers a finished search at the front of the history. */
  private static void remember(String text) {
    if (text.isBlank()) return;
    HISTORY.remove(text);
    HISTORY.addFirst(text);
    while (HISTORY.size() > HISTORY_SIZE) HISTORY.removeLast();
  }

  private boolean holdOrder;

  /** Q18: whether the auto-focus preference was applied for this opening. */
  private boolean focusChecked;

  /** Whether the grid was laid out tall; a synced preference change rebuilds the layout. */
  private boolean laidOutTall;

  /** A3: the "Show craftable" toggle. */
  private Button craftablesButton;

  /** Q67: ★ swaps the search box and sort tools for the five saved-view buttons. */
  private Button viewsButton;

  private final List<Button> savedViewButtons = new ArrayList<>();
  private boolean viewsOpen;

  /** A5: a recipe viewer's request, picked up when its own terminal is shown again. */
  private static final ViewerRequests VIEWER = new ViewerRequests();

  private final List<TabButton> tabs = new ArrayList<>();
  private int clock, lastCommand = -20, searchDelay;
  private String syncedSearch;
  private boolean searchDirty, syncingSearch;
  private boolean craftingOpen;

  public TerminalScreen(TerminalMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = MAIN;
    imageHeight = FIXED + NORMAL_ROWS * 18;
  }

  // ---------------------------------------------------------------- verification API

  /** Whether the Jobs view is shown (for verification). */
  public boolean jobsMode() {
    return jobsMode;
  }

  /** Whether the Patterns view is shown (for verification). */
  public boolean patternsMode() {
    return patternsMode;
  }

  /** Absolute centre of pattern slot {@code slot} of visible Crafter {@code row}. */
  public int patternSlotX(int slot) {
    return patterns.slotCenterX(slot);
  }

  public int patternSlotY(int row) {
    return patterns.slotCenterY(row);
  }

  /** The storage grid, its tools and the crafting panel are hidden behind Jobs or Patterns. */
  private boolean storageHidden() {
    return jobsMode || patternsMode;
  }

  /** Called by an optional recipe-viewer integration after it sent the request packet. */
  public static void requestFromViewer(TerminalMenu menu, String itemId) {
    VIEWER.offer(menu, itemId);
  }

  public int columns() {
    return COLUMNS;
  }

  public int rows() {
    return storage.rows();
  }

  /**
   * True once the server view matches the requested window. Row indices sent before that refer to a
   * stale view revision, which the server ignores.
   */
  public boolean windowSettled() {
    return storage.windowSettled();
  }

  /** The crafting grid's top, relative to the panel. */
  public int gridY() {
    return TerminalCraftingPanel.GRID_Y;
  }

  public int resultY() {
    return TerminalCraftingPanel.GRID_Y + 16;
  }

  public int itemX(int index) {
    return storage.itemX(index);
  }

  public int itemY(int index) {
    return storage.itemY(index);
  }

  public boolean craftingOpen() {
    return craftingOpen && !storageHidden();
  }

  public int gridX() {
    return crafting.gridX();
  }

  public int resultX() {
    return crafting.resultX();
  }

  /** Absolute x of the job list's rows, for verification clicks. */
  public int jobRowX() {
    return jobs.jobRowX();
  }

  /** Absolute y of the middle of visible job row {@code line}. */
  public int jobRowY(int line) {
    return jobs.jobRowY(line);
  }

  /** Absolute centre of the craftable strip, for verification scrolls. */
  public int craftableStripX() {
    return jobs.craftableStripX();
  }

  public int craftableStripY() {
    return jobs.craftableStripY();
  }

  // ---------------------------------------------------------------- shared with the parts

  int left() {
    return leftPos;
  }

  int top() {
    return topPos;
  }

  int panelHeight() {
    return imageHeight;
  }

  Font font() {
    return font;
  }

  Minecraft client() {
    return minecraft;
  }

  int kind() {
    return kind;
  }

  /** The selected Items/Fluids/Energy entry, which the crafting panel's Use row takes. */
  int selectedEntry() {
    return storage.selected();
  }

  Slot hovered() {
    return hoveredSlot;
  }

  <T extends GuiEventListener & Renderable & NarratableEntry> T add(T widget) {
    return addRenderableWidget(widget);
  }

  void rebuild() {
    rebuildWidgets();
  }

  /** Switches to Jobs; {@code request} (or null) is a craftable to select once it is listed. */
  void showJobs(String request) {
    jobs.request(request);
    boolean previousJobs = jobsMode;
    jobsMode = true;
    patternsMode = false;
    if (!previousJobs) rebuildWidgets();
  }

  /** The Crafting button: opens the panel from Jobs, otherwise opens or closes it. */
  void toggleCrafting() {
    if (storageHidden()) {
      jobsMode = false;
      patternsMode = false;
      craftingOpen = true;
    } else craftingOpen = !craftingOpen;
    rebuildWidgets();
  }

  /** Shows the crafting panel, leaving Jobs (a recipe-viewer fill must reveal its ghosts). */
  void openCrafting() {
    craftingOpen = true;
    jobsMode = false;
    patternsMode = false;
    rebuildWidgets();
  }

  /** Sends a terminal command for the current view revision and search. */
  void send(int action, int selection, int amount, byte[] fingerprint) {
    lastCommand = clock;
    if (action == TerminalPackets.SORT) storage.resetOffset();
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

  /**
   * Draws the count in the slot's bottom-right corner above the icon (vanilla's overlay depth keeps
   * 3D block icons from hiding it). From GUI scale 2 the text is half size, as in AE2/RS, so it
   * covers only the corner; at scale 1 half-size glyphs would be under one pixel, so it stays full
   * size and shrinks only to fit the cell.
   */
  void drawCount(GuiGraphics graphics, ItemStack shown, int x, int y, String count) {
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

  // ---------------------------------------------------------------- layout

  @Override
  protected void init() {
    String previousSearch = search == null ? menu.view().search() : search.getValue();
    laidOutTall = TerminalSearchSupport.tall(menu.view().sort());
    int fit = (height - 6 - FIXED) / 18;
    storage.layout(Math.max(MIN_ROWS, Math.min(laidOutTall ? 256 / COLUMNS : NORMAL_ROWS, fit)));
    imageWidth = craftingOpen() ? MAIN + SIDE : MAIN;
    imageHeight = FIXED + storage.rows() * 18;
    menu.layoutInventory(
        GRID_X + 1, storage.inventoryTop() + 1, storage.hotbarTop() + 1, () -> !jobsMode);
    super.init();
    tabs.clear();

    // Tabs on the top edge, across the panel's width (Q3 added a fifth).
    int tabX = PanelLayout.EDGE - 3;
    var labels = new ArrayList<Component>();
    for (int tab = 0; tab <= PATTERNS_TAB; tab++)
      labels.add(Component.translatable("gui.factorycore.terminal.tab." + tab));
    int[] widths = TabButton.widths(font, labels, MAIN - 2 * tabX, 1);
    for (int tab = 0; tab <= PATTERNS_TAB; tab++) {
      int choice = tab;
      var label = labels.get(tab);
      int width = widths[tab];
      tabs.add(
          addRenderableWidget(
              new TabButton(
                  leftPos + tabX,
                  topPos,
                  width,
                  label,
                  button -> {
                    boolean previousJobs = jobsMode, previousPatterns = patternsMode;
                    jobsMode = choice == JOBS_TAB;
                    patternsMode = choice == PATTERNS_TAB;
                    if (!storageHidden()) {
                      kind = choice;
                      storage.resetOffset();
                    }
                    if (patternsMode && !previousPatterns) patterns.show();
                    if (previousJobs != jobsMode || previousPatterns != patternsMode)
                      rebuildWidgets();
                  })));
      tabX += width + 1;
    }

    // Search row: search, then show-craftable, sort, direction and tall; the status lamp ends it.
    var searchRow =
        new Rect(GRID_X, SEARCH_Y, MAIN - 2 * GRID_X - LAMP - PanelLayout.GAP, PanelLayout.ROW);
    // Q17: with a recipe viewer installed, a fifth tool syncs the search with it.
    boolean viewer = RecipeViewerSearch.bridge() != null;
    // Q67: ★ (saved views) comes last, so the saved views can cover what is left of it.
    var tools =
        PanelLayout.alignRight(
            searchRow,
            viewer ? new int[] {16, 30, 14, 14, 14, 14} : new int[] {16, 30, 14, 14, 14},
            2);
    var star = tools.removeLast();
    viewsButton =
        addRenderableWidget(
            tool(
                star,
                Component.literal("★"),
                "gui.factorycore.terminal.views.help",
                () -> viewsOpen = !viewsOpen));
    savedViewButtons.clear();
    // The saved views cover the search box and the view, sort and direction tools.
    int viewsLeft = searchRow.x(), viewsRight = tools.get(2).right();
    int viewWidth =
        (viewsRight - viewsLeft - 2 * (TerminalPackets.SAVED_VIEWS - 1))
            / TerminalPackets.SAVED_VIEWS;
    for (int index = 0; index < TerminalPackets.SAVED_VIEWS; index++)
      savedViewButtons.add(
          addRenderableWidget(
              new SavedViewButton(
                  leftPos + viewsLeft + index * (viewWidth + 2),
                  topPos + searchRow.y(),
                  viewWidth,
                  searchRow.height(),
                  index)));
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
    search.setFormatter((visible, offset) -> highlight.format(search.getValue(), visible, offset));
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
                // Q62: Stored → All → Craftable.
                () ->
                    send(
                        TerminalPackets.CRAFTABLES,
                        (menu.view().view() + 1) % 3,
                        1,
                        new byte[32])));
    sortButton =
        addRenderableWidget(
            tool(
                tools.get(1),
                sortLabel(),
                "gui.factorycore.terminal.sort.help",
                () -> {
                  send(
                      TerminalPackets.SORT,
                      TerminalSearchSupport.nextMode(menu.view().sort()),
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
    syncButton =
        viewer
            ? addRenderableWidget(
                tool(
                    tools.get(4),
                    syncLabel(),
                    "gui.factorycore.terminal.viewer_sync.help",
                    () ->
                        send(
                            TerminalPackets.SORT,
                            menu.view().sort() ^ TerminalSearchSupport.SYNC,
                            1,
                            new byte[32])))
            : null;

    storage.init();
    crafting.init();
    jobs.init();
    crafting.update();
    // Widgets start visible; the saved views must not take clicks before the first tick.
    showViews();
    craftablesButton.visible &= !viewsOpen;
  }

  /** Q67: the saved-view row or the search box and sort tools, whichever ★ chose. */
  private void showViews() {
    var saved = menu.view().saved();
    search.visible = !viewsOpen;
    sortButton.visible = !viewsOpen;
    directionButton.visible = !viewsOpen;
    for (int index = 0; index < savedViewButtons.size(); index++) {
      var button = (SavedViewButton) savedViewButtons.get(index);
      button.visible = viewsOpen;
      button.update(saved.isEmpty() ? TerminalPackets.SavedView.NONE : saved.get(index));
    }
  }

  /**
   * Q67: one saved view. Click applies it (or, when empty, saves the current search, sort and view
   * into it); Shift-click saves over it; right-click empties it.
   */
  private final class SavedViewButton extends Button {
    private final int index;
    private TerminalPackets.SavedView shown;

    SavedViewButton(int x, int y, int width, int height, int index) {
      super(
          x,
          y,
          width,
          height,
          Component.literal(Integer.toString(index + 1)),
          button -> {},
          DEFAULT_NARRATION);
      this.index = index;
    }

    void update(TerminalPackets.SavedView saved) {
      if (saved.equals(shown)) return;
      shown = saved;
      var number = Component.literal(Integer.toString(index + 1));
      setMessage(saved.set() ? number : number.withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
      setTooltip(
          Tooltip.create(
              saved.set()
                  ? Component.translatable(
                      "gui.factorycore.terminal.views.saved",
                      index + 1,
                      saved.search().isEmpty()
                          ? Component.translatable("gui.factorycore.terminal.views.no_search")
                          : Component.literal(saved.search()),
                      Component.translatable(
                          "gui.factorycore.terminal.sort."
                              + TerminalSearchSupport.mode(saved.sort())),
                      TerminalSearchSupport.reversed(saved.sort()) ? "↑" : "↓",
                      Component.translatable("gui.factorycore.terminal.views.view." + saved.view()))
                  : Component.translatable("gui.factorycore.terminal.views.empty", index + 1)));
    }

    @Override
    protected boolean isValidClickButton(int button) {
      return button == 0 || button == 1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
      if (!active || !visible || !isValidClickButton(button) || !clicked(mouseX, mouseY))
        return false;
      playDownSound(Minecraft.getInstance().getSoundManager());
      clickView(index, button == 1, hasShiftDown());
      return true;
    }
  }

  /** Q67: what a click on saved view {@code index} does (public as a test hook). */
  public void clickView(int index, boolean right, boolean shift) {
    var saved = menu.view().saved();
    boolean set = !saved.isEmpty() && saved.get(index).set();
    int what =
        right
            ? TerminalPackets.VIEW_CLEAR
            : shift || !set ? TerminalPackets.VIEW_SAVE : TerminalPackets.VIEW_APPLY;
    if (what == TerminalPackets.VIEW_APPLY) {
      // The saved search arrives with the view; drop any typing still waiting to be sent.
      searchDirty = false;
      searchDelay = 0;
      syncingSearch = true;
      search.setValue(saved.get(index).search());
      syncingSearch = false;
      viewsOpen = false;
    }
    send(TerminalPackets.VIEWS, index, what, new byte[32]);
  }

  /** Test hook (Q67): opens or closes the saved-view row. */
  public void viewsOpen(boolean open) {
    viewsOpen = open;
  }

  private Button tool(Rect cell, Component label, String help, Runnable action) {
    return Button.builder(label, button -> action.run())
        .bounds(leftPos + cell.x(), topPos + cell.y(), cell.width(), cell.height())
        .tooltip(Tooltip.create(Component.translatable(help)))
        .build();
  }

  private Component sortLabel() {
    return Component.translatable(
        "gui.factorycore.terminal.sort." + TerminalSearchSupport.mode(menu.view().sort()));
  }

  /** The view tool: grey for Stored, gold for All, aqua for Craftable only (Q62). */
  private Component craftablesLabel() {
    return Component.literal("⚒")
        .withStyle(
            switch (menu.view().view()) {
              case TerminalPackets.VIEW_ALL -> net.minecraft.ChatFormatting.GOLD;
              case TerminalPackets.VIEW_CRAFTABLE -> net.minecraft.ChatFormatting.AQUA;
              default -> net.minecraft.ChatFormatting.GRAY;
            });
  }

  /** The arrow shows the visible order: down is the mode's natural order, up is reversed. */
  private Component directionLabel() {
    return Component.literal(TerminalSearchSupport.reversed(menu.view().sort()) ? "↑" : "↓");
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
    storage.clampOffset();
    syncViewer(view.sort());
    // Q18: once the saved preferences arrive, auto-focus puts the cursor in the search box.
    if (!focusChecked && menu.viewReceived()) {
      focusChecked = true;
      if (TerminalSearchSupport.focus(view.sort()) && !storageHidden()) {
        setFocused(search);
        search.setFocused(true);
      }
    }
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
      if (!storageHidden() && view.kind() != kind) {
        send(TerminalPackets.KIND, kind, 1, new byte[32]);
        lastCommand = clock;
      } else if (searchDelay == 0 && !search.getValue().equals(view.search())) {
        send(TerminalPackets.SEARCH, 0, 1, new byte[32]);
        storage.resetOffset();
        lastCommand = clock;
      } else if (!storageHidden() && search.getValue().equals(view.search()) && !windowSettled()) {
        storage.requestWindow();
        lastCommand = clock;
      }
    }
    syncedSearch = view.search();
    int shown = jobsMode ? JOBS_TAB : patternsMode ? PATTERNS_TAB : kind;
    for (int i = 0; i < tabs.size(); i++) tabs.get(i).setSelected(shown == i);
    storage.tick(storageHidden());
    showViews();
    viewsButton.setMessage(
        Component.literal("★")
            .withStyle(
                viewsOpen
                    ? net.minecraft.ChatFormatting.YELLOW
                    : net.minecraft.ChatFormatting.GRAY));
    sortButton.setMessage(sortLabel());
    sortButton.active = !view.busy();
    directionButton.setMessage(directionLabel());
    craftablesButton.setMessage(craftablesLabel());
    craftablesButton.visible = kind == 0 && !storageHidden() && !viewsOpen;
    directionButton.active = !view.busy();
    tallButton.active = !view.busy();
    storage.tickActions();
    crafting.tick();
    jobs.tickOffset();
    var request = VIEWER.take(menu);
    if (request != null) showJobs(request);
    jobs.tick(jobsMode);
    storage.tickVisibility(storageHidden());
    jobs.tickButtons(jobsMode);
  }

  /**
   * Opening the recipe viewer also removes this screen, while the menu stays open; a request is
   * dropped only when the terminal itself closes.
   */
  @Override
  public void removed() {
    super.removed();
    if (search != null) remember(search.getValue());
    if (minecraft == null || minecraft.player == null || minecraft.player.containerMenu != menu)
      VIEWER.drop(menu);
  }

  // ---------------------------------------------------------------- drawing

  @Override
  protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
    int body = TabButton.STRIP;
    Panels.panel(graphics, new Rect(leftPos, topPos + body, MAIN, imageHeight - body));
    if (craftingOpen()) crafting.renderBg(graphics);
    if (jobsMode) {
      jobs.renderBg(graphics);
      return;
    }
    for (var slot : menu.slots) Panels.slot(graphics, leftPos + slot.x - 1, topPos + slot.y - 1);
    if (patternsMode) {
      patterns.renderBg(graphics);
      return;
    }
    storage.renderBg(graphics);
  }

  /**
   * The status lamp ends the search row (the tab strip is full since Q3); its tooltip has the full
   * status.
   */
  private Rect lamp() {
    return new Rect(MAIN - GRID_X - LAMP, SEARCH_Y + (PanelLayout.ROW - LAMP) / 2, LAMP, LAMP);
  }

  private static final int LAMP = 8;

  boolean online() {
    return menu.deviceStatus().online();
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    Panels.lamp(graphics, lamp(), online());
    if (jobsMode) {
      jobs.renderLabels(graphics);
      return;
    }
    if (patternsMode) {
      patterns.renderLabels(graphics);
      return;
    }
    storage.renderProblems(graphics);
    if (craftingOpen()) crafting.renderLabels(graphics);
    storage.renderInfo(graphics);
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
    if (jobsMode) {
      jobs.renderTooltip(graphics, mouseX, mouseY);
      return;
    }
    if (patternsMode) {
      patterns.renderTooltip(graphics, mouseX, mouseY);
      return;
    }
    if (storage.renderTooltip(graphics, mouseX, mouseY)) return;
    if (crafting.renderTooltip(graphics, mouseX, mouseY)) return;
    // Q61: a malformed query says what is wrong when the search box is hovered.
    var query = highlight.query(search.getValue());
    if (search.isMouseOver(mouseX, mouseY) && query.error() != null) {
      graphics.renderTooltip(
          font,
          Component.translatable("gui.factorycore.terminal.search.error." + query.error()),
          mouseX,
          mouseY);
      return;
    }
    if (hoveredSlot != null
        && hoveredSlot.container == minecraft.player.getInventory()
        && !hoveredSlot.hasItem())
      graphics.renderTooltip(
          font, Component.translatable("gui.factorycore.terminal.deposit.help"), mouseX, mouseY);
  }

  // ---------------------------------------------------------------- input

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    // Q61: right-clicking the search box clears it.
    if (button == 1 && search.isMouseOver(mouseX, mouseY) && !hasShiftDown()) {
      remember(search.getValue());
      search.setValue("");
      setFocused(search);
      search.setFocused(true);
      return true;
    }
    // Q18: Shift-right-clicking the search box turns search auto-focus on or off.
    if (button == 1 && search.isMouseOver(mouseX, mouseY)) {
      boolean on = !TerminalSearchSupport.focus(menu.view().sort());
      send(TerminalPackets.SORT, menu.view().sort() ^ TerminalSearchSupport.FOCUS, 1, new byte[32]);
      client()
          .player
          .displayClientMessage(
              Component.translatable(
                  "gui.factorycore.terminal.search.focus." + (on ? "on" : "off")),
              true);
      return true;
    }
    if (!storageHidden() && storage.mouseClicked(mouseX, mouseY, button)) return true;
    if (jobsMode && jobs.mouseClicked(mouseX, mouseY, button)) return true;
    if (patternsMode && patterns.mouseClicked(mouseX, mouseY, button)) return true;
    if (crafting.mouseClicked(mouseX, mouseY, button)) return true;
    // Shift-clicking the inventory deposits into storage, except while carrying patterns around.
    if (!patternsMode && storage.depositClicked(mouseX, mouseY, button)) return true;
    return super.mouseClicked(mouseX, mouseY, button);
  }

  /**
   * The Items grid and the Jobs craftable strip scroll (Shift: a full strip of eight in Jobs); in
   * processing mode, scrolling over a cell changes its per-craft count (Shift: by 8).
   */
  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    if (jobsMode && jobs.mouseScrolled(mouseX, mouseY, scrollY)) return true;
    if (patternsMode && patterns.mouseScrolled(mouseX, mouseY, scrollY)) return true;
    if (!storageHidden() && storage.mouseScrolled(mouseX, mouseY, scrollY)) return true;
    if (crafting.mouseScrolled(mouseX, mouseY, scrollY)) return true;
    return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
  }

  @Override
  public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
    if (storage.mouseDragged(y)) return true;
    return super.mouseDragged(x, y, button, dx, dy);
  }

  @Override
  public boolean mouseReleased(double x, double y, int button) {
    storage.mouseReleased();
    return super.mouseReleased(x, y, button);
  }

  @Override
  public boolean keyPressed(int key, int scan, int modifiers) {
    if (search.isFocused() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
      searchDelay = 0;
      remember(search.getValue());
      historyAt = -1;
      return true;
    }
    // Q61: Up/Down walk the search history; the focus key puts the cursor in the search box.
    if (search.isFocused() && (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN)) {
      if (historyAt < 0 && key == GLFW.GLFW_KEY_DOWN) return true;
      var entries = new ArrayList<>(HISTORY);
      if (historyAt < 0 && !search.getValue().isBlank()) {
        remember(search.getValue());
        entries = new ArrayList<>(HISTORY);
        historyAt = 0;
      }
      historyAt =
          Math.max(
              -1, Math.min(entries.size() - 1, historyAt + (key == GLFW.GLFW_KEY_UP ? 1 : -1)));
      search.setValue(historyAt < 0 ? "" : entries.get(historyAt));
      return true;
    }
    if (!search.isFocused()
        && FactoryCoreKeys.FOCUS_SEARCH.isActiveAndMatches(InputConstants.getKey(key, scan))) {
      setFocused(search);
      search.setFocused(true);
      return true;
    }
    // Q14: the open key closes the terminal again (not while typing in the search box).
    if (!search.isFocused()
        && FactoryCoreKeys.OPEN_WIRELESS.matches(key, scan)
        && !FactoryCoreKeys.OPEN_WIRELESS.isUnbound()) {
      onClose();
      return true;
    }
    var amount = jobs.amountKey(key, scan, modifiers);
    if (amount != null) return amount;
    if (!search.isFocused() && crafting.craftKey(key)) return true;
    // Q63: a hotbar key over a grid entry swaps that entry into the hotbar slot.
    if (!search.isFocused() && kind() == 0 && !storageHidden() && !jobsMode && !patternsMode) {
      var window = minecraft.getWindow();
      double mouseX =
          minecraft.mouseHandler.xpos() * window.getGuiScaledWidth() / window.getScreenWidth();
      double mouseY =
          minecraft.mouseHandler.ypos() * window.getGuiScaledHeight() / window.getScreenHeight();
      for (int hotbar = 0; hotbar < 9; hotbar++)
        if (minecraft.options.keyHotbarSlots[hotbar].matches(key, scan)
            && storage.swap(mouseX, mouseY, hotbar)) return true;
    }
    if (!search.isFocused() && key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_8) {
      int index = key - GLFW.GLFW_KEY_1;
      if (jobsMode) jobs.selectCraftable(index);
      else if (!patternsMode) storage.select(index);
      return true;
    }
    return super.keyPressed(key, scan, modifiers);
  }

  /** Q17: the sync tool's label: lit while the search follows the recipe viewer. */
  private Component syncLabel() {
    return Component.translatable("gui.factorycore.terminal.viewer_sync")
        .withStyle(
            TerminalSearchSupport.sync(menu.view().sort())
                ? net.minecraft.ChatFormatting.GREEN
                : net.minecraft.ChatFormatting.GRAY);
  }

  /**
   * Q17: while sync is on, a change typed here is copied into the recipe viewer's search, and a
   * change typed there is copied here (and so searched). Turning sync on copies this search there.
   */
  private void syncViewer(int sort) {
    if (syncButton != null) syncButton.setMessage(syncLabel());
    var bridge = RecipeViewerSearch.bridge();
    if (bridge == null || !TerminalSearchSupport.sync(sort)) {
      viewerOurs = viewerTheirs = null;
      return;
    }
    String ours = search.getValue(), theirs = bridge.text();
    if (!ours.equals(viewerOurs)) {
      if (!ours.equals(theirs)) bridge.text(ours);
      viewerOurs = viewerTheirs = ours;
    } else if (!theirs.equals(viewerTheirs)) {
      viewerOurs = viewerTheirs = theirs;
      search.setValue(theirs);
    }
  }

  /** Test hook: the search text, as typed. */
  /** Test hook (Q65): the Plan tree's zoom (½ to 1). */
  public void treeZoom(float zoom) {
    jobs.treeZoom(zoom);
  }

  /** Test hook (Q64): types in the Jobs amount box and previews it, as Enter does. */
  public void typeAmount(String text) {
    jobs.typeAmount(text);
  }

  /** Test hook (Q63): holds the grid order as if Shift were down. */
  public void holdOrder(boolean hold) {
    holdOrder = hold;
  }

  boolean holdOrder() {
    return holdOrder;
  }

  /** Test hook (Q63): the Items grid entries as shown. */
  public java.util.List<TerminalPackets.Row> shownEntries() {
    return storage.entries();
  }

  public String searchText() {
    return search.getValue();
  }

  /** Test hook: types {@code text} into the search box. */
  public void typeSearch(String text) {
    search.setValue(text);
  }
}
