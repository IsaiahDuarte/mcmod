package dev.izzy.factorycore.platform.client;

import static dev.izzy.factorycore.platform.client.TerminalScreen.GRID_X;
import static dev.izzy.factorycore.platform.client.TerminalScreen.GRID_Y;
import static dev.izzy.factorycore.platform.client.TerminalScreen.MAIN;

import dev.izzy.factorycore.core.EnumKeys;
import dev.izzy.factorycore.core.crafting.CraftingJob;
import dev.izzy.factorycore.core.crafting.JobStatus;
import dev.izzy.factorycore.platform.client.ui.PanelLayout;
import dev.izzy.factorycore.platform.client.ui.Panels;
import dev.izzy.factorycore.platform.client.ui.Rect;
import dev.izzy.factorycore.platform.storage.TerminalJobPackets;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * The Terminal's Jobs view (ADR 0031, ADR 0034, playtest H2): the craftable strip and its
 * scrollbar, three text lines, the job list, the plan breakdown (A2) and job steps (A4) that cover
 * them, and two rows of buttons. Coordinates are panel-relative unless a name says absolute.
 */
final class TerminalJobsView {
  /** Craftable strip, its scrollbar, three text lines and the job list. */
  private static final int STRIP_Y = GRID_Y, TEXT_Y = STRIP_Y + 24, JOBS_Y = TEXT_Y + 32;

  private final TerminalScreen screen;
  private Button stepDown,
      stepUp,
      previewMany,
      previewMax,
      startJob,
      keepJob,
      planButton,
      treeButton,
      notifyButton,
      cancelJob;

  /** Q65: the Plan overlay draws a tree instead of the per-resource list, at this zoom. */
  private boolean treeMode;

  private float treeZoom = 1;

  /** Q7: the job-view revision whose Max result was last copied into the amount box. */
  private long maxShown;

  /** A1: the typed craft amount, and why it was last refused ("" when it parses). */
  private EditBox amountBox;

  private String amountError = "";
  private int craftSelected, jobSelected;

  /** A4: first listed job shown in the job list, and the opened job's step scroll. */
  private int jobScroll, detailOffset;

  private boolean showDetail;

  /** A2: the previewed plan's breakdown replaces the craftable strip and job list while shown. */
  private boolean showPlan;

  private int planOffset;

  /** A craftable asked for from the grid or a recipe viewer, selected once it is listed. */
  private String pendingRequest;

  /**
   * The craftable offset last requested, so fast wheel notches add up before the server replies.
   */
  private int pendingCraftOffset = -1;

  /**
   * Ticks left before an unconfirmed pending offset is dropped: the session's command rate gate may
   * discard a WINDOW request, and the strip must then scroll from what the server shows.
   */
  private int pendingCraftTicks;

  TerminalJobsView(TerminalScreen screen) {
    this.screen = screen;
  }

  private TerminalMenu menu() {
    return screen.getMenu();
  }

  /** Asks to select {@code icon} (or nothing) once listed; the strip and list show again. */
  void request(String icon) {
    pendingRequest = icon;
    showPlan = false;
  }

  void selectCraftable(int index) {
    if (index < menu().jobView().craftables().size()) craftSelected = index;
  }

  // ---------------------------------------------------------------- geometry

  /** The two button rows sit at the bottom; the job list fills the space above. */
  private int buttonsY() {
    return screen.panelHeight() - PanelLayout.EDGE - 2 * PanelLayout.ROW - 4;
  }

  private int jobsBottom() {
    return buttonsY() - 4;
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

  int jobRowX() {
    return screen.left() + GRID_X + 20;
  }

  int jobRowY(int line) {
    return screen.top() + JOBS_Y + 9 + 18 * line;
  }

  int craftableStripX() {
    return screen.left() + GRID_X + 4 * 18;
  }

  int craftableStripY() {
    return screen.top() + STRIP_Y + 9;
  }

  // ---------------------------------------------------------------- widgets

  /** Amounts, typed amount and Start on one row; Keep, Plan and Cancel on the next. */
  void init() {
    int y = buttonsY();
    var font = screen.font();
    var first =
        PanelLayout.fit(
            new Rect(GRID_X, y, MAIN - 2 * GRID_X, PanelLayout.ROW),
            new int[] {20, 20, 26, 26, 30, 34},
            3);
    // Q64: − and + step the typed amount and preview it.
    stepDown = jobButton("step_down", first.get(0), () -> step(-1));
    stepUp = jobButton("step_up", first.get(1), () -> step(1));
    previewMany =
        jobButton(
            "preview_many", first.get(2), () -> job(TerminalJobPackets.PREVIEW, craftSelected, 64));
    previewMax =
        jobButton("preview_max", first.get(3), () -> job(TerminalJobPackets.MAX, craftSelected, 1));
    String typed = amountBox == null ? "" : amountBox.getValue();
    var box = first.get(4);
    amountBox =
        screen.add(
            new EditBox(
                font,
                screen.left() + box.x() + 1,
                screen.top() + box.y() + 1,
                box.width() - 2,
                16,
                Component.translatable("gui.factorycore.terminal.job.amount")));
    amountBox.setMaxLength(CraftAmount.MAX_LENGTH);
    amountBox.setValue(typed);
    amountBox.setHint(Component.translatable("gui.factorycore.terminal.job.amount"));
    amountBox.setTooltip(
        Tooltip.create(
            Component.translatable(
                "gui.factorycore.terminal.job.amount.help", TerminalJobPackets.MAX_AMOUNT)));
    amountBox.setResponder(value -> amountError = "");
    startJob = jobButton("start", first.get(5), this::start);
    var second =
        PanelLayout.fit(
            new Rect(GRID_X, y + PanelLayout.ROW + 4, MAIN - 2 * GRID_X, PanelLayout.ROW),
            new int[] {32, 32, 32, 40, 20},
            3);
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
    // Q65: Tree switches the Plan overlay; ✉ turns the finish notice on or off for new jobs.
    treeButton =
        jobButton(
            "tree",
            second.get(2),
            () -> {
              treeMode = !treeMode;
              planOffset = 0;
            });
    cancelJob =
        jobButton("cancel", second.get(3), () -> job(TerminalJobPackets.CANCEL, jobSelected, 1));
    notifyButton =
        jobButton(
            "notify",
            second.get(4),
            () -> job(TerminalJobPackets.NOTIFY, menu().jobView().notices() ? 0 : 1, 1));
  }

  /**
   * Previews the typed amount; an empty, zero, non-numeric or over-limit entry is refused here with
   * a reason instead of being capped (the server bound is the same {@code MAX_AMOUNT}).
   */
  private void previewTyped() {
    var parsed = typed();
    if (parsed.valid() && craftSelected < menu().jobView().craftables().size())
      job(
          parsed.target() ? TerminalJobPackets.TARGET : TerminalJobPackets.PREVIEW,
          craftSelected,
          parsed.amount());
  }

  /** The typed amount, with its refusal shown. */
  private CraftAmount typed() {
    var parsed = CraftAmount.parse(amountBox.getValue(), TerminalJobPackets.MAX_AMOUNT);
    amountError = parsed.error();
    amountBox.setTextColor(parsed.valid() ? Panels.FIELD_TEXT : Panels.FIELD_ERROR);
    return parsed;
  }

  /**
   * Start: starts the previewed plan. Q64: Shift+Start previews the typed amount and starts it in
   * one step; Start with nothing previewed previews the typed amount.
   */
  private void start() {
    var jobs = menu().jobView();
    // Q65: after a failed preview Start reads Retry and previews the same amount again.
    if (retry()) {
      try {
        job(
            TerminalJobPackets.PREVIEW,
            craftSelected,
            Math.max(
                1,
                Math.min(
                    TerminalJobPackets.MAX_AMOUNT, Integer.parseInt(jobs.preview().amount()))));
      } catch (NumberFormatException unreadable) {
        previewTyped();
      }
      return;
    }
    boolean previewed = !jobs.preview().icon().isEmpty() && jobs.previewReason() == null;
    if (Screen.hasShiftDown() || !previewed) {
      var parsed = typed();
      if (!parsed.valid() || craftSelected >= jobs.craftables().size()) return;
      if (!Screen.hasShiftDown()) previewTyped();
      else
        job(
            parsed.target() ? TerminalJobPackets.QUICK_TARGET : TerminalJobPackets.QUICK,
            craftSelected,
            parsed.amount());
      return;
    }
    job(TerminalJobPackets.START, 0, 1);
  }

  /** Q65: a preview failed, so Start offers to try it again. */
  private boolean retry() {
    var jobs = menu().jobView();
    return !jobs.preview().icon().isEmpty()
        && jobs.previewReason() != null
        && craftSelected < jobs.craftables().size();
  }

  /** Q64: steps the typed amount by 1 (Shift 10, Ctrl 64) in {@code direction}, then previews. */
  private void step(int direction) {
    int size = Screen.hasControlDown() ? 64 : Screen.hasShiftDown() ? 10 : 1;
    var parsed = CraftAmount.parse(amountBox.getValue(), TerminalJobPackets.MAX_AMOUNT);
    int current = parsed.valid() && !parsed.target() ? parsed.amount() : 0;
    int next = Math.max(1, Math.min(TerminalJobPackets.MAX_AMOUNT, current + direction * size));
    amountBox.setValue(Integer.toString(next));
    previewTyped();
  }

  /** Test hook (Q64): types {@code text} in the amount box and previews it, as Enter does. */
  void typeAmount(String text) {
    amountBox.setValue(text);
    previewTyped();
  }

  /** Keep-stock threshold: the previewed amount, or 64 when nothing is previewed. */
  private int keepAmount() {
    var preview = menu().jobView().preview();
    try {
      return preview.amount().isEmpty() ? 64 : Integer.parseInt(preview.amount());
    } catch (NumberFormatException invalid) {
      return 64;
    }
  }

  private Button jobButton(String key, Rect cell, Runnable action) {
    return screen.add(
        ScreenButtons.labeled(
            "gui.factorycore.terminal.job.",
            key,
            screen.left() + cell.x(),
            screen.top() + cell.y(),
            cell.width(),
            cell.height(),
            action));
  }

  private void job(int action, int selection, int amount) {
    var menu = menu();
    PacketDistributor.sendToServer(
        new TerminalJobPackets.Command(
            menu.containerId,
            menu.session(),
            menu.jobView().revision(),
            action,
            selection,
            amount));
  }

  /** Drops a pending strip offset once the server shows it, or after its timeout. */
  void tickOffset() {
    var jobs = menu().jobView();
    if (jobs.offset() == pendingCraftOffset || --pendingCraftTicks <= 0) pendingCraftOffset = -1;
  }

  /** Selects a pending request once listed, and keeps selections and scrolls in range. */
  void tick(boolean jobsMode) {
    var jobs = menu().jobView();
    if (pendingRequest != null && jobsMode)
      for (int index = 0; index < jobs.craftables().size(); index++)
        if (jobs.craftables().get(index).icon().equals(pendingRequest)) {
          craftSelected = index;
          pendingRequest = null;
          screen.setFocused(amountBox);
          amountBox.setFocused(true);
          break;
        }
    craftSelected = Math.min(craftSelected, Math.max(0, jobs.craftables().size() - 1));
    jobSelected = Math.min(jobSelected, Math.max(0, jobs.jobs().size() - 1));
    // Q7: the amount Max found becomes the typed amount, so Start and Keep use it.
    if ((jobs.status() == JobStatus.MAXIMUM || jobs.status() == JobStatus.CAPPED)
        && jobs.revision() != maxShown) {
      maxShown = jobs.revision();
      amountBox.setValue(jobs.preview().amount());
    }
  }

  void tickButtons(boolean jobsMode) {
    var jobs = menu().jobView();
    for (var item :
        List.of(stepDown, stepUp, previewMany, previewMax, startJob, keepJob, notifyButton))
      item.visible = jobsMode;
    treeButton.visible = jobsMode;
    treeButton.active = planShown();
    treeButton.setMessage(
        Component.translatable("gui.factorycore.terminal.job." + (treeMode ? "list" : "tree")));
    notifyButton.setMessage(
        Component.literal("✉")
            .withStyle(
                jobs.notices()
                    ? net.minecraft.ChatFormatting.GREEN
                    : net.minecraft.ChatFormatting.DARK_GRAY));
    startJob.setMessage(
        Component.translatable("gui.factorycore.terminal.job." + (retry() ? "retry" : "start")));
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
    stepDown.active = craftSelected < jobs.craftables().size();
    stepUp.active = stepDown.active;
    previewMany.active = stepDown.active;
    previewMax.active = stepDown.active;
    // Q64: with nothing previewed, Start previews (or with Shift, starts) the typed amount.
    startJob.active =
        (!jobs.preview().icon().isEmpty() && jobs.previewReason() == null)
            || retry()
            || (craftSelected < jobs.craftables().size() && !amountBox.getValue().isBlank());
    cancelJob.visible = jobsMode;
    cancelJob.active =
        detailShown()
            ? jobs.detail().cancellable()
                && jobs.detail().job().state() != CraftingJob.State.CANCELLING
            : jobSelected < jobs.jobs().size()
                && jobs.jobs().get(jobSelected).owner().isEmpty()
                && jobs.jobs().get(jobSelected).state() != CraftingJob.State.CANCELLING;
  }

  // ---------------------------------------------------------------- drawing

  void renderBg(GuiGraphics graphics) {
    if (detailShown()) renderDetail(graphics);
    else if (planShown() && treeMode) renderTree(graphics);
    else if (planShown()) renderPlan(graphics);
    else renderList(graphics);
  }

  /** The craftable strip with its scrollbar, and the job list. */
  private void renderList(GuiGraphics graphics) {
    var jobs = menu().jobView();
    var font = screen.font();
    int left = screen.left(), top = screen.top();
    var craftables = jobs.craftables();
    for (int index = 0; index < TerminalJobPackets.ROWS; index++) {
      int x = left + GRID_X + 18 * index, y = top + STRIP_Y;
      Panels.slot(graphics, x, y);
      if (index >= craftables.size()) continue;
      if (index == craftSelected) Panels.selectedEdge(graphics, new Rect(x, y, 18, 18));
      var item = ResourceIcons.item(craftables.get(index).icon());
      if (item == null) continue;
      var shown = item.getDefaultInstance();
      graphics.renderItem(shown, x + 1, y + 1);
      var keep = craftables.get(index).amount();
      // "≥N" is the keep-stock amount; "!" marks one that is waiting.
      boolean waiting = craftables.get(index).note() != null;
      if (!keep.isEmpty())
        screen.drawCount(graphics, shown, x, y, (waiting ? "!" : "≥") + CountText.compact(keep));
    }
    int track = TerminalJobPackets.ROWS * 18;
    var bar = new Rect(left + GRID_X, top + STRIP_Y + 19, track, 4);
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
    Panels.thumb(graphics, new Rect(thumbX, bar.y() + 1, thumb, bar.height() - 2));
    var list = new Rect(left + GRID_X, top + JOBS_Y, MAIN - 2 * GRID_X, jobRows() * 18);
    Panels.inset(graphics, list);
    var jobRows = jobs.jobs();
    for (int line = 0; line < jobRows() && jobScroll + line < jobRows.size(); line++) {
      int index = jobScroll + line;
      int y = list.y() + 18 * line;
      var row = jobRows.get(index);
      if (index == jobSelected)
        Panels.selected(graphics, new Rect(list.x() + 1, y + 1, list.width() - 2, 16));
      var item = ResourceIcons.item(row.output().icon());
      if (item != null) graphics.renderItem(item.getDefaultInstance(), list.x() + 1, y + 1);
      String progress =
          CountText.compact(Long.toString(row.done()))
              + "/"
              + CountText.compact(Long.toString(row.total()))
              + " "
              + glyph(row.state())
              + " "
              + row.output().name();
      graphics.drawString(
          font,
          font.plainSubstrByWidth(progress, list.width() - 26),
          list.x() + 20,
          y + 5,
          !row.owner().isEmpty()
              ? Panels.LIST_OTHER
              : row.reason() == null ? Panels.LIST_TEXT : Panels.LIST_WARN,
          true);
    }
    if (jobRows.size() > jobRows()) {
      int track2 = list.height() - 2;
      int thumb2 = Math.max(8, track2 * jobRows() / jobRows.size());
      int thumbY =
          list.y() + 1 + (track2 - thumb2) * jobScroll / Math.max(1, jobRows.size() - jobRows());
      Panels.lightThumb(graphics, new Rect(list.right() - 3, thumbY, 2, thumb2));
    }
  }

  /** Text symbol accompanying color so job state is readable without color vision. */
  private static String glyph(CraftingJob.State state) {
    return switch (state) {
      case RUNNING -> "▶";
      case WAITING -> "⏸";
      case DELIVERING -> "⇩";
      case CANCELLING -> "✕";
    };
  }

  private boolean planShown() {
    return showPlan && !detailShown() && !menu().jobView().preview().icon().isEmpty();
  }

  private boolean detailShown() {
    return showDetail && !menu().jobView().detail().job().output().icon().isEmpty();
  }

  /** Q100: a Crafter's label, else the name of the block that runs the step; "" if unknown. */
  private static String machineName(TerminalJobPackets.StepRow step) {
    if (!step.label().isEmpty()) return step.label();
    var id = net.minecraft.resources.ResourceLocation.tryParse(step.machine());
    if (step.machine().isEmpty() || id == null) return "";
    return net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id).getName().getString();
  }

  private int detailLines() {
    var detail = menu().jobView().detail();
    return detail.steps().size() + (detail.moreSteps() > 0 ? 1 : 0);
  }

  /**
   * A4 crafting monitor: the opened job's steps with crafts done/total and whether each is done,
   * running, waiting (with the job's reason) or queued. Cancel acts on this job when allowed.
   */
  private void renderDetail(GuiGraphics graphics) {
    var detail = menu().jobView().detail();
    var job = detail.job();
    var font = screen.font();
    var area = overlay().offset(screen.left(), screen.top());
    Panels.inset(graphics, area);
    int x = area.x(), y = area.y(), width = area.width() - 6;
    String header =
        Component.translatable(
                    "gui.factorycore.terminal.job.preview",
                    job.output().amount(),
                    job.output().name())
                .getString()
            + " · "
            + Component.translatable(
                    "gui.factorycore.terminal.job.state." + EnumKeys.key(job.state()))
                .getString()
            + (job.owner().isEmpty()
                ? ""
                : " · "
                    + Component.translatable(
                            "gui.factorycore.terminal.job.detail.owner", job.owner())
                        .getString());
    graphics.drawString(
        font, font.plainSubstrByWidth(header, width), x + 3, y + 3, Panels.LIST_TEXT, true);
    // Why the job waits gets a full line; the waiting step's row then just says "Waiting".
    int top = 13;
    if (job.reason() != null) {
      graphics.drawString(
          font,
          font.plainSubstrByWidth(
              Component.translatable("gui.factorycore.terminal.job.reason." + job.reason().key())
                  .getString(),
              width),
          x + 3,
          y + 13,
          Panels.LIST_WARN,
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
              case DONE -> Panels.LIST_GOOD;
              case WAITING -> Panels.LIST_WARN;
              case RUNNING -> Panels.LIST_TEXT;
              case QUEUED -> Panels.LIST_MUTED;
            };
        String status =
            Component.translatable("gui.factorycore.terminal.job.step." + step.status().key())
                    .getString()
                + (machineName(step).isEmpty() ? "" : " · " + machineName(step));
        graphics.drawString(
            font,
            font.plainSubstrByWidth(step.output().name(), nameWidth),
            x + 3,
            rowY,
            color,
            true);
        String progress =
            CountText.compact(Long.toString(step.done()))
                + "/"
                + CountText.compact(Long.toString(step.total()));
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
            Panels.LIST_MUTED,
            true);
    }
  }

  /** Breakdown lines: one per row, "and N more" if any, then one per cycle explanation. */
  private int planLines() {
    var jobs = menu().jobView();
    if (treeMode) return jobs.tree().size() + (jobs.treeMore() ? 1 : 0);
    return jobs.plan().size() + (jobs.planMore() > 0 ? 1 : 0) + jobs.cycles().size();
  }

  /** Q65: tree rows that fit the overlay at the current zoom. */
  private int treeLines() {
    return Math.max(1, (int) ((overlay().height() - 13) / (18 * treeZoom)));
  }

  /**
   * Q65: the plan as a tree, depth first. Each node is an icon with its amount, indented by depth
   * and joined to its parent by a line; red is missing, amber a loop, grey taken from storage.
   */
  private void renderTree(GuiGraphics graphics) {
    var jobs = menu().jobView();
    var font = screen.font();
    var area = overlay().offset(screen.left(), screen.top());
    Panels.inset(graphics, area);
    graphics.drawString(
        font,
        Component.translatable("gui.factorycore.terminal.job.tree.title"),
        area.x() + 3,
        area.y() + 3,
        Panels.LIST_MUTED,
        true);
    var tree = jobs.tree();
    graphics.enableScissor(area.x() + 1, area.y() + 12, area.right() - 1, area.bottom() - 1);
    var pose = graphics.pose();
    pose.pushPose();
    pose.translate(area.x() + 3, area.y() + 13, 0);
    pose.scale(treeZoom, treeZoom, 1);
    // The row each depth last drew, so a child can draw its line up to its parent.
    int[] parentRow = new int[dev.izzy.factorycore.core.crafting.PlanTree.MAX_DEPTH + 2];
    for (int index = 0; index < tree.size(); index++) {
      var node = tree.get(index);
      int row = index - planOffset, x = node.depth() * 12, y = row * 18;
      parentRow[node.depth() + 1] = row;
      if (node.depth() > 0) {
        int parentY = parentRow[node.depth()] * 18 + 17, lineX = x - 7;
        int line = 0xFF000000 | Panels.LIST_MUTED;
        graphics.fill(lineX, Math.min(parentY, y + 9), lineX + 1, y + 9, line);
        graphics.fill(lineX, y + 8, x + 1, y + 9, line);
      }
      if (row < 0) continue;
      if (y > (area.height() - 13) / treeZoom) break;
      graphics.renderItem(
          new net.minecraft.world.item.ItemStack(
              net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                  net.minecraft.resources.ResourceLocation.parse(node.item().icon()))),
          x + 1,
          y + 1);
      int colour =
          switch (node.kind()) {
            case MISSING -> Panels.LIST_SHORT;
            case LOOP -> Panels.LIST_WARN;
            case STORED -> Panels.LIST_MUTED;
            default -> Panels.LIST_TEXT;
          };
      graphics.drawString(
          font,
          Component.translatable(
              "gui.factorycore.terminal.job.tree.node."
                  + node.kind().name().toLowerCase(java.util.Locale.ROOT),
              CountText.compact(Long.toString(node.amount())),
              node.item().name()),
          x + 19,
          y + 5,
          colour,
          true);
    }
    if (jobs.treeMore() && tree.size() - planOffset < treeLines())
      graphics.drawString(
          font,
          Component.translatable("gui.factorycore.terminal.job.tree.more"),
          0,
          (tree.size() - planOffset) * 18 + 5,
          Panels.LIST_MUTED,
          true);
    pose.popPose();
    graphics.disableScissor();
  }

  /** Test hook (Q65): the tree zoom. */
  void treeZoom(float zoom) {
    treeZoom = Math.max(0.5f, Math.min(1, zoom));
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
    var jobs = menu().jobView();
    var font = screen.font();
    var area = overlay().offset(screen.left(), screen.top());
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
        Panels.LIST_TEXT,
        true);
    for (var column : new int[] {0, 1, 2}) {
      var label = Component.translatable("gui.factorycore.terminal.job.plan.column." + column);
      graphics.drawString(
          font, label, x + columns[column] - font.width(label), y + 3, Panels.LIST_MUTED, true);
    }
    for (int line = 0; line < overlayLines(); line++) {
      int index = planOffset + line, rowY = y + 13 + 9 * line;
      if (index < jobs.plan().size()) {
        var row = jobs.plan().get(index);
        boolean short_ = row.missing() > 0;
        if (short_) Panels.shortRow(graphics, new Rect(x + 1, rowY - 1, area.width() - 2, 9));
        graphics.drawString(
            font,
            font.plainSubstrByWidth(row.item().name(), columns[0] - 40),
            x + 3,
            rowY,
            short_ ? Panels.LIST_SHORT : Panels.LIST_TEXT,
            true);
        long[] values = {row.stored(), row.crafted(), row.missing()};
        for (int column = 0; column < 3; column++) {
          String text =
              values[column] == 0 ? "·" : CountText.compact(Long.toString(values[column]));
          graphics.drawString(
              font,
              text,
              x + columns[column] - font.width(text),
              rowY,
              column == 2 && short_ ? Panels.LIST_MISSING : Panels.LIST_TEXT,
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
            Panels.LIST_MUTED,
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
            Panels.LIST_WARN,
            true);
    }
  }

  /** The selected craftable or preview, the plan summary or shortage, and the job status. */
  void renderLabels(GuiGraphics graphics) {
    var jobs = menu().jobView();
    var font = screen.font();
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
                    ? Component.translatable(
                        "gui.factorycore.terminal.job.status." + jobs.status().key())
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

  /** A plan row's exact counts, a job's state and reason, or a craftable's keep-stock note. */
  void renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
    var font = screen.font();
    int top = screen.top();
    if (detailShown() && overOverlay(mouseX, mouseY)) {
      // Q100: a step row's full status and the machine that runs it, which the row may cut.
      var detail = menu().jobView().detail();
      int first = top + overlay().y() + (detail.job().reason() != null ? 23 : 13);
      int index = detailOffset + (mouseY - first) / 9;
      if (mouseY >= first && index >= 0 && index < detail.steps().size()) {
        var step = detail.steps().get(index);
        var lines = new java.util.ArrayList<Component>();
        lines.add(Component.literal(step.output().name()));
        lines.add(
            Component.translatable("gui.factorycore.terminal.job.step." + step.status().key()));
        if (!machineName(step).isEmpty())
          lines.add(
              Component.translatable(
                  "gui.factorycore.terminal.job.step.machine", machineName(step)));
        graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
      }
      return;
    }
    if (planShown() || detailShown()) {
      if (!planShown() || treeMode || !overOverlay(mouseX, mouseY)) return;
      int index = planOffset + (mouseY - top - overlay().y() - 13) / 9;
      var plan = menu().jobView().plan();
      if (mouseY >= top + overlay().y() + 13 && index >= 0 && index < plan.size()) {
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
    if (jobRow >= 0) {
      var value = menu().jobView().jobs().get(jobRow);
      graphics.renderTooltip(
          font,
          List.of(
              Component.translatable(
                  "gui.factorycore.terminal.job.preview",
                  value.output().amount(),
                  value.output().name()),
              Component.translatable(
                  "gui.factorycore.terminal.job.progress", value.done(), value.total()),
              Component.translatable(
                  "gui.factorycore.terminal.job.state." + EnumKeys.key(value.state())),
              value.reason() == null
                  ? Component.empty()
                  : Component.translatable(
                      "gui.factorycore.terminal.job.reason." + value.reason().key()),
              value.owner().isEmpty()
                  ? Component.translatable("gui.factorycore.terminal.job.detail.help")
                  : Component.translatable(
                      "gui.factorycore.terminal.job.detail.owner", value.owner())),
          Optional.empty(),
          mouseX,
          mouseY);
    } else if (strip >= 0 && strip < menu().jobView().craftables().size()) {
      var craftable = menu().jobView().craftables().get(strip);
      graphics.renderTooltip(
          font,
          List.of(
              Component.literal(craftable.name()),
              craftable.note() == null
                  ? Component.translatable("gui.factorycore.terminal.job.select.help")
                  : Component.translatable(
                      "gui.factorycore.terminal.job.keep.waiting",
                      Component.translatable(
                          "gui.factorycore.terminal.job.status." + craftable.note().key()))),
          Optional.empty(),
          mouseX,
          mouseY);
    }
  }

  // ---------------------------------------------------------------- input

  /** The visible job row under the mouse as an index into the job list, or -1. */
  private int jobRowAt(double mouseX, double mouseY) {
    if (planShown() || detailShown()) return -1;
    int left = screen.left(), top = screen.top();
    if (mouseX < left + GRID_X || mouseX >= left + MAIN - GRID_X) return -1;
    int line = (int) Math.floor((mouseY - top - JOBS_Y) / 18);
    if (mouseY < top + JOBS_Y || line >= jobRows()) return -1;
    int index = jobScroll + line;
    return index < menu().jobView().jobs().size() ? index : -1;
  }

  /** The craftable strip cell under the mouse, or -1. */
  private int stripIndexAt(double mouseX, double mouseY) {
    if (planShown() || detailShown()) return -1;
    int left = screen.left(), top = screen.top();
    if (mouseY < top + STRIP_Y || mouseY >= top + STRIP_Y + 18) return -1;
    int index = (int) Math.floor((mouseX - left - GRID_X) / 18);
    return mouseX < left + GRID_X || index >= TerminalJobPackets.ROWS ? -1 : index;
  }

  private boolean overOverlay(double mouseX, double mouseY) {
    var area = overlay().offset(screen.left(), screen.top());
    return mouseX >= area.x()
        && mouseX < area.right()
        && mouseY >= area.y()
        && mouseY < area.bottom();
  }

  private boolean overCraftables(double mouseX, double mouseY) {
    int left = screen.left(), top = screen.top();
    return mouseX >= left + GRID_X
        && mouseX < left + GRID_X + TerminalJobPackets.ROWS * 18
        && mouseY >= top + STRIP_Y
        && mouseY < top + STRIP_Y + 24;
  }

  private boolean overJobList(double mouseX, double mouseY) {
    int left = screen.left(), top = screen.top();
    return mouseX >= left + GRID_X
        && mouseX < left + MAIN - GRID_X
        && mouseY >= top + JOBS_Y
        && mouseY < top + JOBS_Y + jobRows() * 18;
  }

  boolean mouseClicked(double mouseX, double mouseY, int button) {
    int left = screen.left(), top = screen.top();
    // The plan breakdown and job steps cover the strip and list; clicks there do nothing.
    if ((planShown() || detailShown()) && overOverlay(mouseX, mouseY)) return true;
    if (button == 0
        && mouseY >= top + STRIP_Y + 18
        && mouseY < top + STRIP_Y + 24
        && mouseX >= left + GRID_X
        && mouseX < left + GRID_X + TerminalJobPackets.ROWS * 18) {
      // Clicking the scrollbar track jumps there, as on the Items grid.
      int range = Math.max(0, menu().jobView().total() - TerminalJobPackets.ROWS);
      scrollCraftables(
          (int)
              Math.round(
                  (mouseX - left - GRID_X) / (double) (TerminalJobPackets.ROWS * 18) * range));
      return true;
    }
    int strip = stripIndexAt(mouseX, mouseY);
    if (button == 0 && strip >= 0) {
      if (strip < menu().jobView().craftables().size()) craftSelected = strip;
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
    return false;
  }

  /**
   * Scrolls the craftable strip to {@code offset}, keeping the same craftable selected while it
   * stays visible; the server clamps the offset to the last full window.
   */
  private void scrollCraftables(int offset) {
    int from = craftOffset();
    int to = Math.max(0, Math.min(offset, menu().jobView().total() - TerminalJobPackets.ROWS));
    if (to == from) return;
    craftSelected = Math.max(0, Math.min(TerminalJobPackets.ROWS - 1, craftSelected - (to - from)));
    pendingCraftOffset = Math.min(to, TerminalJobPackets.MAX_OFFSET);
    pendingCraftTicks = 20;
    job(TerminalJobPackets.WINDOW, pendingCraftOffset, 1);
  }

  private int craftOffset() {
    return pendingCraftOffset >= 0 ? pendingCraftOffset : menu().jobView().offset();
  }

  /** Steps, plan, job list and the craftable strip scroll (Shift: a full strip of eight). */
  boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
    if (scrollY == 0) return false;
    if (amountBox.visible && amountBox.isMouseOver(mouseX, mouseY)) {
      step(scrollY > 0 ? 1 : -1);
      return true;
    }
    if (detailShown() && overOverlay(mouseX, mouseY)) {
      detailOffset =
          Math.max(
              0,
              Math.min(
                  Math.max(0, detailLines() - overlayLines()),
                  detailOffset + (scrollY > 0 ? -1 : 1)));
      return true;
    }
    if (planShown() && treeMode && overOverlay(mouseX, mouseY)) {
      // Q65: Ctrl+wheel zooms the tree; the wheel scrolls it.
      if (Screen.hasControlDown()) treeZoom(treeZoom + (scrollY > 0 ? 0.25f : -0.25f));
      else
        planOffset =
            Math.max(
                0,
                Math.min(
                    Math.max(0, planLines() - treeLines()), planOffset + (scrollY > 0 ? -1 : 1)));
      return true;
    }
    if (planShown() && overOverlay(mouseX, mouseY)) {
      planOffset =
          Math.max(
              0,
              Math.min(
                  Math.max(0, planLines() - overlayLines()), planOffset + (scrollY > 0 ? -1 : 1)));
      return true;
    }
    if (overJobList(mouseX, mouseY)) {
      jobScroll =
          Math.max(
              0,
              Math.min(
                  menu().jobView().jobs().size() - jobRows(), jobScroll + (scrollY > 0 ? -1 : 1)));
      return true;
    }
    if (overCraftables(mouseX, mouseY)) {
      int step = Screen.hasShiftDown() ? TerminalJobPackets.ROWS : 1;
      scrollCraftables(craftOffset() + (scrollY > 0 ? -step : step));
      return true;
    }
    return false;
  }

  /**
   * Keys while the amount box has focus: Enter previews the typed amount, and digits and C are
   * typed rather than taken as shortcuts. Returns null when the box does not have focus (or for
   * Escape), so the screen handles the key.
   */
  Boolean amountKey(int key, int scan, int modifiers) {
    if (!amountBox.isFocused()) return null;
    if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
      previewTyped();
      return true;
    }
    if (key == GLFW.GLFW_KEY_ESCAPE) return null;
    if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
      step(key == GLFW.GLFW_KEY_UP ? 1 : -1);
      return true;
    }
    return amountBox.keyPressed(key, scan, modifiers) || amountBox.canConsumeInput();
  }
}
