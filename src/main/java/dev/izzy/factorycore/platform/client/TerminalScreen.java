package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.TerminalCraftingPackets;
import dev.izzy.factorycore.platform.storage.TerminalJobPackets;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
import dev.izzy.factorycore.platform.storage.TerminalPackets;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphics;
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

/** Native GUI; projected icons never act as container stacks or carry hidden item components. */
public final class TerminalScreen extends AbstractContainerScreen<TerminalMenu> {
  private EditBox search;
  private int selected;
  private long shownRevision;
  private Button one, stack, home, next, returning, useRow, craftOne, craftMany, clearGrid, encode;
  private Button mode, previewOne, previewEight, previewMany, startJob, keepJob, jobHome, jobNext;
  private Button cancelJob;
  private boolean jobsMode;

  /** Client-selected storage kind (0 items, 1 fluids, 2 energy); the server switches its search. */
  private int kind;

  private Button kindButton, sortButton;
  private String syncedSearch;
  private Button gridMode;

  /** 0 = crafting grid, 1 = processing inputs, 2 = processing outputs (first row = 3 outputs). */
  private int gridPage;

  private int target, craftSelected, jobSelected;
  public static final int JOBS_X = 276;
  public static final int GRID_X = 236, GRID_Y = 146, RESULT_X = 314, RESULT_Y = 170;

  public TerminalScreen(TerminalMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = 344;
    imageHeight = 232;
    inventoryLabelX = 59;
    inventoryLabelY = 136;
  }

  @Override
  protected void init() {
    String previousSearch = search == null ? menu.view().search() : search.getValue();
    super.init();
    search =
        addRenderableWidget(
            new EditBox(
                font,
                leftPos + 10,
                topPos + 20,
                110,
                18,
                Component.translatable("gui.factorycore.terminal.search")));
    search.setMaxLength(128);
    search.setValue(previousSearch);
    search.setHint(Component.translatable("gui.factorycore.terminal.search"));
    sortButton =
        addRenderableWidget(
            Button.builder(
                    sortLabel(),
                    button ->
                        send(TerminalPackets.SORT, (menu.view().sort() + 1) % 2, 1, new byte[32]))
                .bounds(leftPos + 124, topPos + 20, 44, 18)
                .tooltip(
                    Tooltip.create(Component.translatable("gui.factorycore.terminal.sort.help")))
                .build());
    addRenderableWidget(
        Button.builder(
                Component.translatable("gui.factorycore.terminal.search"),
                button -> send(TerminalPackets.SEARCH, 0, 1, new byte[32]))
            .bounds(leftPos + 218, topPos + 20, 52, 18)
            .build());
    one =
        button(
            "withdraw_one",
            10,
            48,
            () -> {
              if (kind == 0) send(TerminalPackets.WITHDRAW, selected, 1, new byte[32]);
              else if (kind == 1) send(TerminalPackets.FILL, selected, 1, new byte[32]);
              else
                send(
                    TerminalPackets.CHARGE,
                    minecraft.player.getInventory().selected,
                    1,
                    new byte[32]);
            });
    stack =
        button(
            "withdraw_stack",
            62,
            48,
            () -> {
              if (kind == 0) send(TerminalPackets.WITHDRAW, selected, 64, new byte[32]);
              else if (kind == 2)
                send(
                    TerminalPackets.DISCHARGE,
                    minecraft.player.getInventory().selected,
                    1,
                    new byte[32]);
            });
    home = button("home", 114, 48, () -> send(TerminalPackets.HOME, 0, 1, new byte[32]));
    next = button("next", 166, 48, () -> send(TerminalPackets.NEXT, 0, 1, new byte[32]));
    returning = button("return", 218, 52, () -> send(TerminalPackets.RETURN, 0, 1, new byte[32]));
    useRow =
        gridButton(
            "use_row",
            296,
            GRID_Y,
            44,
            () -> grid(TerminalCraftingPackets.SET_ROW, target, selected, 1, new byte[32]));
    craftOne =
        gridButton(
            "craft_one",
            GRID_X,
            210,
            34,
            () -> grid(TerminalCraftingPackets.CRAFT, -1, 0, 1, new byte[32]));
    craftMany =
        gridButton(
            "craft_stack",
            272,
            210,
            34,
            () -> grid(TerminalCraftingPackets.CRAFT, -1, 0, 64, new byte[32]));
    clearGrid =
        gridButton(
            "clear",
            308,
            210,
            32,
            () -> grid(TerminalCraftingPackets.CLEAR, -1, 0, 1, new byte[32]));
    addEncode();
    addJobControls();
    kindButton =
        addRenderableWidget(
            Button.builder(
                    kindLabel(),
                    button -> {
                      kind = (kind + 1) % 3;
                      button.setMessage(kindLabel());
                      send(TerminalPackets.KIND, kind, 1, new byte[32]);
                    })
                .bounds(leftPos + 172, topPos + 20, 42, 18)
                .tooltip(
                    Tooltip.create(Component.translatable("gui.factorycore.terminal.kind.help")))
                .build());
    gridMode =
        addRenderableWidget(
            Button.builder(gridModeLabel(), button -> cycleGrid())
                .bounds(leftPos + 8, topPos + GRID_Y, 46, 18)
                .tooltip(
                    Tooltip.create(
                        Component.translatable("gui.factorycore.terminal.grid.mode.help")))
                .build());
  }

  private Component sortLabel() {
    return Component.translatable("gui.factorycore.terminal.sort." + menu.view().sort());
  }

  private Component gridModeLabel() {
    return Component.translatable("gui.factorycore.terminal.grid.mode." + gridPage);
  }

  private void cycleGrid() {
    gridPage = (gridPage + 1) % 3;
    if (gridPage == 1) grid(TerminalCraftingPackets.MODE, 1, 0, 1, new byte[32]);
    if (gridPage == 0) grid(TerminalCraftingPackets.MODE, 0, 0, 1, new byte[32]);
    gridMode.setMessage(gridModeLabel());
  }

  /** Server cell index shown at a displayed 3×3 position, or -1 for an unused output-page cell. */
  private int cellIndex(int display) {
    if (gridPage == 2) return display < 3 ? 9 + display : -1;
    return display;
  }

  private void addJobControls() {
    mode =
        addRenderableWidget(
            Button.builder(
                    modeLabel(),
                    button -> {
                      jobsMode = !jobsMode;
                      button.setMessage(modeLabel());
                    })
                .bounds(leftPos + 274, topPos + 20, 66, 18)
                .tooltip(
                    Tooltip.create(
                        Component.translatable("gui.factorycore.terminal.job.mode.help")))
                .build());
    previewOne =
        jobButton("preview_one", 10, 34, () -> job(TerminalJobPackets.PREVIEW, craftSelected, 1));
    previewEight =
        jobButton("preview_eight", 46, 34, () -> job(TerminalJobPackets.PREVIEW, craftSelected, 8));
    previewMany =
        jobButton("preview_many", 82, 36, () -> job(TerminalJobPackets.PREVIEW, craftSelected, 64));
    startJob = jobButton("start", 120, 40, () -> job(TerminalJobPackets.START, 0, 1));
    keepJob =
        jobButton("keep", 162, 38, () -> job(TerminalJobPackets.KEEP, craftSelected, keepAmount()));
    jobHome = jobButton("home", 202, 32, () -> job(TerminalJobPackets.HOME, 0, 1));
    jobNext = jobButton("next", 238, 32, () -> job(TerminalJobPackets.NEXT, 0, 1));
    cancelJob =
        addRenderableWidget(
            Button.builder(
                    Component.translatable("gui.factorycore.terminal.job.cancel"),
                    button -> job(TerminalJobPackets.CANCEL, jobSelected, 1))
                .bounds(leftPos + 274, topPos + 118, 66, 18)
                .tooltip(
                    Tooltip.create(
                        Component.translatable("gui.factorycore.terminal.job.cancel.help")))
                .build());
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

  private Component kindLabel() {
    return Component.translatable("gui.factorycore.terminal.kind." + kind);
  }

  private Component modeLabel() {
    return Component.translatable(
        jobsMode ? "gui.factorycore.terminal.job.items" : "gui.factorycore.terminal.job.jobs");
  }

  private Button jobButton(String key, int x, int width, Runnable action) {
    return addRenderableWidget(
        Button.builder(
                Component.translatable("gui.factorycore.terminal.job." + key),
                button -> action.run())
            .bounds(leftPos + x, topPos + 118, width, 18)
            .tooltip(
                Tooltip.create(
                    Component.translatable("gui.factorycore.terminal.job." + key + ".help")))
            .build());
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

  private void addEncode() {
    encode =
        gridButton(
            "encode",
            296,
            GRID_Y + 46,
            44,
            () -> grid(TerminalCraftingPackets.ENCODE, -1, 0, 1, new byte[32]));
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
    int column = (int) Math.floor((mouseX - leftPos - GRID_X) / 18);
    int row = (int) Math.floor((mouseY - topPos - GRID_Y) / 18);
    return column < 0 || column > 2 || row < 0 || row > 2 ? -1 : row * 3 + column;
  }

  private Button button(String key, int x, int width, Runnable action) {
    return addRenderableWidget(
        Button.builder(
                Component.translatable("gui.factorycore.terminal." + key), button -> action.run())
            .bounds(leftPos + x, topPos + 118, width, 18)
            .tooltip(
                Tooltip.create(Component.translatable("gui.factorycore.terminal." + key + ".help")))
            .build());
  }

  private void send(int action, int selection, int amount, byte[] fingerprint) {
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
    if (!view.search().equals(syncedSearch)) {
      search.setValue(view.search());
      syncedSearch = view.search();
    }
    sortButton.setMessage(sortLabel());
    sortButton.active = !view.busy();
    if (shownRevision != view.revision()) {
      selected = Math.min(selected, Math.max(0, view.rows().size() - 1));
      shownRevision = view.revision();
    }
    one.active = !view.busy() && (kind == 2 || !view.rows().isEmpty());
    stack.active = kind == 0 ? one.active : kind == 2;
    one.setMessage(Component.translatable("gui.factorycore.terminal.action." + kind + ".one"));
    stack.setMessage(Component.translatable("gui.factorycore.terminal.action." + kind + ".two"));
    kindButton.visible = !jobsMode;
    next.active = !view.busy() && view.next();
    returning.active =
        !view.busy() && !view.staged().equals("0") && !view.status().equals("uncertain");
    useRow.active = !view.busy() && selected < view.rows().size();
    craftOne.active = !view.busy() && !menu.gridView().result().isEmpty();
    craftMany.active = craftOne.active;
    clearGrid.active = !view.busy();
    var gridView = menu.gridView();
    if (!gridView.processing() && gridPage != 0) gridPage = 0;
    if (gridView.processing() && gridPage == 0) gridPage = 1;
    gridMode.setMessage(gridModeLabel());
    if (gridView.processing()) {
      craftOne.active = false;
      craftMany.active = false;
    }
    encode.active = !view.busy() && !gridView.result().isEmpty();
    var jobs = menu.jobView();
    craftSelected = Math.min(craftSelected, Math.max(0, jobs.craftables().size() - 1));
    jobSelected = Math.min(jobSelected, Math.max(0, jobs.jobs().size() - 1));
    for (var item : List.of(one, stack, home, next, returning)) item.visible = !jobsMode;
    for (var item :
        List.of(previewOne, previewEight, previewMany, startJob, keepJob, jobHome, jobNext))
      item.visible = jobsMode;
    keepJob.active = craftSelected < jobs.craftables().size();
    previewOne.active = craftSelected < jobs.craftables().size();
    previewEight.active = previewOne.active;
    previewMany.active = previewOne.active;
    startJob.active = !jobs.preview().icon().isEmpty() && jobs.previewReason().isEmpty();
    jobNext.active = jobs.next();
    cancelJob.active =
        jobSelected < jobs.jobs().size()
            && !jobs.jobs().get(jobSelected).state().equals("cancelling");
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
    graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff282c30);
    graphics.fill(leftPos + 4, topPos + 4, leftPos + 340, topPos + 228, 0xff44484d);
    graphics.fill(leftPos + 8, topPos + 42, leftPos + 272, topPos + 115, 0xff171d22);
    for (var slot : menu.slots) {
      graphics.fill(
          leftPos + slot.x - 1,
          topPos + slot.y - 1,
          leftPos + slot.x + 17,
          topPos + slot.y + 17,
          0xff151b20);
      graphics.fill(
          leftPos + slot.x,
          topPos + slot.y,
          leftPos + slot.x + 16,
          topPos + slot.y + 16,
          0xff59626b);
      graphics.fill(
          leftPos + slot.x + 1,
          topPos + slot.y + 1,
          leftPos + slot.x + 15,
          topPos + slot.y + 15,
          0xff30373e);
    }
    var grid = menu.gridView();
    for (int cell = 0; cell < 9; cell++) {
      int x = leftPos + GRID_X + 18 * (cell % 3), y = topPos + GRID_Y + 18 * (cell / 3);
      int index = cellIndex(cell);
      graphics.fill(x, y, x + 18, y + 18, index == target ? 0xffbb8659 : 0xff151b20);
      graphics.fill(x + 1, y + 1, x + 17, y + 17, index < 0 ? 0xff1d2226 : 0xff30373e);
      if (index < 0) continue;
      var ghost = icon(grid.cells().get(index));
      if (ghost != null) {
        graphics.renderFakeItem(ghost.getDefaultInstance(), x + 1, y + 1);
        // Translucent veil marks a ghost selection rather than a held item.
        graphics.fill(x + 1, y + 1, x + 17, y + 17, 0x6630373e);
        int count = grid.counts().get(index);
        if (grid.processing() && count > 1)
          graphics.drawString(
              font,
              Integer.toString(count),
              x + 17 - font.width(Integer.toString(count)),
              y + 9,
              0xffffff,
              true);
      }
    }
    graphics.fill(
        leftPos + RESULT_X - 1,
        topPos + RESULT_Y - 1,
        leftPos + RESULT_X + 21,
        topPos + RESULT_Y + 21,
        0xff151b20);
    var output = icon(grid.result());
    if (output != null) {
      var shown = output.getDefaultInstance();
      shown.setCount(Math.max(1, grid.resultCount()));
      graphics.renderFakeItem(shown, leftPos + RESULT_X + 2, topPos + RESULT_Y + 2);
      graphics.renderItemDecorations(font, shown, leftPos + RESULT_X + 2, topPos + RESULT_Y + 2);
    }
    var jobRows = menu.jobView().jobs();
    graphics.fill(leftPos + JOBS_X - 2, topPos + 42, leftPos + 340, topPos + 115, 0xff171d22);
    for (int index = 0; index < jobRows.size(); index++) {
      int y = topPos + 43 + 18 * index;
      var row = jobRows.get(index);
      if (index == jobSelected)
        graphics.fill(leftPos + JOBS_X - 1, y - 1, leftPos + 339, y + 17, 0xffbb8659);
      var item = icon(row.output().icon());
      if (item != null) graphics.renderItem(item.getDefaultInstance(), leftPos + JOBS_X, y);
      graphics.drawString(
          font,
          font.plainSubstrByWidth(row.done() + "/" + row.total() + " " + glyph(row.state()), 44),
          leftPos + JOBS_X + 18,
          y + 4,
          row.reason().isEmpty() ? 0xc9dbed : 0xe4bc83,
          false);
    }
    if (jobsMode) {
      var craftables = menu.jobView().craftables();
      for (int index = 0; index < craftables.size(); index++) {
        int x = leftPos + 12 + 32 * index;
        graphics.fill(
            x, topPos + 45, x + 28, topPos + 74, index == craftSelected ? 0xffbb8659 : 0xff59626b);
        var item = icon(craftables.get(index).icon());
        if (item != null) graphics.renderItem(item.getDefaultInstance(), x + 6, topPos + 47);
        var keep = craftables.get(index).amount();
        if (!keep.isEmpty())
          graphics.drawCenteredString(font, "≥" + compact(keep), x + 14, topPos + 65, 0xb8e986);
      }
      return;
    }
    var rows = menu.view().rows();
    for (int index = 0; index < rows.size(); index++) {
      int x = leftPos + 12 + 32 * index;
      graphics.fill(
          x, topPos + 45, x + 28, topPos + 74, index == selected ? 0xffbb8659 : 0xff59626b);
      var item = rowIcon(rows.get(index).icon());
      graphics.renderItem(item.getDefaultInstance(), x + 6, topPos + 47);
      graphics.drawCenteredString(
          font, compact(rows.get(index).available()), x + 14, topPos + 65, 0xffffff);
    }
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

  /** Items show themselves; fluids show their bucket; energy shows redstone. */
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
    BigInteger number = new BigInteger(decimal);
    String[] suffix = {"", "k", "M", "G", "T", "P", "E", "Z", "Y"};
    int step = 0;
    while (number.compareTo(BigInteger.valueOf(1000)) >= 0 && step < suffix.length - 1) {
      number = number.divide(BigInteger.valueOf(1000));
      step++;
    }
    return number + suffix[step];
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    graphics.drawString(font, title, 8, 7, 0xffffff, false);
    graphics.drawString(font, playerInventoryTitle, 59, 136, 0xeeeeee, false);
    graphics.drawString(
        font,
        Component.translatable("gui.factorycore.terminal.grid.mode"),
        8,
        136,
        0xeeeeee,
        false);
    graphics.drawString(
        font,
        font.plainSubstrByWidth(
            Component.translatable(
                    "gui.factorycore.terminal.grid.status." + menu.gridView().status(),
                    menu.gridView().crafted())
                .getString(),
            58),
        282,
        136,
        0xe4bc83,
        false);
    if (jobsMode) {
      renderJobLabels(graphics);
      return;
    }
    var view = menu.view();
    if (selected < view.rows().size()) {
      var row = view.rows().get(selected);
      graphics.drawString(font, font.plainSubstrByWidth(row.name(), 254), 12, 79, 0xffffff, false);
      graphics.drawString(
          font,
          Component.translatable(
              "gui.factorycore.terminal.counts",
              compact(row.total()),
              compact(row.reserved()),
              compact(row.available())),
          12,
          91,
          0xc9dbed,
          false);
    }
    graphics.drawString(
        font,
        font.plainSubstrByWidth(
            Component.translatable(
                    "gui.factorycore.terminal.status."
                        + view.status()
                        + (view.status().equals("ready") && kind != 0 ? "." + kind : ""))
                .getString(),
            254),
        12,
        103,
        0xe4bc83,
        false);
  }

  private void renderJobLabels(GuiGraphics graphics) {
    var jobs = menu.jobView();
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
        font, font.plainSubstrByWidth(first.getString(), 254), 12, 79, 0xffffff, false);
    Component second = Component.empty();
    if (!jobs.missing().isEmpty()) {
      var missing = jobs.missing().getFirst();
      second =
          Component.translatable(
              "gui.factorycore.terminal.job.missing",
              missing.amount(),
              missing.name(),
              jobs.missing().size());
    } else if (!jobs.preview().icon().isEmpty())
      second =
          Component.translatable(
              "gui.factorycore.terminal.job.plan", jobs.previewSteps(), jobs.previewCrafts());
    graphics.drawString(
        font, font.plainSubstrByWidth(second.getString(), 254), 12, 91, 0xc9dbed, false);
    graphics.drawString(
        font,
        font.plainSubstrByWidth(
            Component.translatable("gui.factorycore.terminal.job.status." + jobs.status())
                .getString(),
            254),
        12,
        103,
        0xe4bc83,
        false);
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
    super.render(graphics, mouseX, mouseY, partial);
    renderTooltip(graphics, mouseX, mouseY);
    int row = (mouseX - leftPos - 12) / 32;
    boolean over =
        mouseX >= leftPos + 12
            && mouseX < leftPos + 268
            && mouseY >= topPos + 45
            && mouseY < topPos + 75;
    int jobRow = (mouseY - topPos - 43) / 18;
    if (mouseX >= leftPos + JOBS_X
        && mouseX < leftPos + 340
        && mouseY >= topPos + 43
        && jobRow < menu.jobView().jobs().size()) {
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
                  : Component.translatable(
                      "gui.factorycore.terminal.job.reason." + value.reason())),
          Optional.empty(),
          mouseX,
          mouseY);
    } else if (jobsMode && over && row < menu.jobView().craftables().size()) {
      graphics.renderTooltip(
          font,
          List.of(
              Component.literal(menu.jobView().craftables().get(row).name()),
              Component.translatable("gui.factorycore.terminal.job.select.help")),
          Optional.empty(),
          mouseX,
          mouseY);
    } else if (!jobsMode && over && row < menu.view().rows().size()) {
      var value = menu.view().rows().get(row);
      graphics.renderTooltip(
          font,
          List.of(
              Component.literal(value.name()),
              Component.literal(value.icon()),
              Component.translatable(
                  "gui.factorycore.terminal.counts",
                  value.total(),
                  value.reserved(),
                  value.available()),
              Component.translatable("gui.factorycore.terminal.select.help")),
          Optional.empty(),
          mouseX,
          mouseY);
    } else if (cellAt(mouseX, mouseY) >= 0) {
      graphics.renderTooltip(
          font, Component.translatable("gui.factorycore.terminal.grid.cell.help"), mouseX, mouseY);
    } else if (mouseX >= leftPos + RESULT_X
        && mouseX < leftPos + RESULT_X + 20
        && mouseY >= topPos + RESULT_Y
        && mouseY < topPos + RESULT_Y + 20
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
    } else if (mouseY >= topPos + 136 && mouseY < topPos + 228 && getSlotUnderMouse() != null)
      graphics.renderTooltip(
          font, Component.translatable("gui.factorycore.terminal.deposit.help"), mouseX, mouseY);
  }

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    if (button == 0
        && mouseY >= topPos + 45
        && mouseY < topPos + 75
        && mouseX >= leftPos + 12
        && mouseX < leftPos + 268) {
      int index = ((int) mouseX - leftPos - 12) / 32;
      if (jobsMode && index < menu.jobView().craftables().size()) {
        craftSelected = index;
        return true;
      }
      if (!jobsMode && index < menu.view().rows().size()) {
        selected = index;
        return true;
      }
    }
    if (button == 0
        && mouseX >= leftPos + JOBS_X
        && mouseX < leftPos + 340
        && mouseY >= topPos + 43
        && mouseY < topPos + 115) {
      int index = ((int) mouseY - topPos - 43) / 18;
      if (index < menu.jobView().jobs().size()) jobSelected = index;
      return true;
    }
    int cell = cellAt(mouseX, mouseY);
    if (cell >= 0) {
      int index = cellIndex(cell);
      if (index < 0) return true;
      if (button == 1) grid(TerminalCraftingPackets.CLEAR, index, 0, 1, new byte[32]);
      else target = index;
      return true;
    }
    var slot = getSlotUnderMouse();
    if (button == 0
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

  /** In processing mode, scrolling over a cell changes its per-craft count (Shift: by 8). */
  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    int cell = cellAt(mouseX, mouseY);
    var grid = menu.gridView();
    if (cell >= 0 && grid.processing() && scrollY != 0) {
      int index = cellIndex(cell);
      if (index < 0 || grid.cells().get(index).isEmpty()) return true;
      int step = (hasShiftDown() ? 8 : 1) * (scrollY > 0 ? 1 : -1);
      int count = Math.max(1, Math.min(64, grid.counts().get(index) + step));
      if (count != grid.counts().get(index))
        grid(TerminalCraftingPackets.COUNT, index, 0, count, new byte[32]);
      return true;
    }
    return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
  }

  @Override
  public boolean keyPressed(int key, int scan, int modifiers) {
    if (search.isFocused() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
      send(TerminalPackets.SEARCH, 0, 1, new byte[32]);
      return true;
    }
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
