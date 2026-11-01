package dev.izzy.factorycore.platform.client;

import static dev.izzy.factorycore.platform.client.TerminalScreen.GRID_X;
import static dev.izzy.factorycore.platform.client.TerminalScreen.GRID_Y;

import dev.izzy.factorycore.core.crafting.CraftingJob;
import dev.izzy.factorycore.platform.client.ui.Panels;
import dev.izzy.factorycore.platform.client.ui.Rect;
import dev.izzy.factorycore.platform.storage.TerminalJobPackets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Q146 (playthrough n4): the viewer's running jobs as a strip over the Items grid, so following a
 * job does not need the Jobs tab. Each chip is three grid columns wide: the output's icon, crafts
 * done/total over a progress bar, and ✕. Clicking a chip opens that job's steps in Jobs; ✕ cancels
 * it on a second click within {@link #ARM_TICKS}. When more jobs run than chips fit, the last chip
 * says how many more and opens Jobs. The strip sends the Jobs tab's own DETAIL and CANCEL commands,
 * so the server's checks are unchanged. Coordinates are panel-relative unless a name says absolute.
 */
final class TerminalJobStrip {
  static final int CHIP_COLUMNS = 3, CHIP = CHIP_COLUMNS * 18, CANCEL = 9;

  /** How long a first click on ✕ stays armed, in client ticks. */
  static final int ARM_TICKS = 60;

  private final TerminalScreen screen;
  private int armed = -1, armedTicks;

  TerminalJobStrip(TerminalScreen screen) {
    this.screen = screen;
  }

  /** The viewer's own unfinished jobs, in the server's order (own jobs are listed first). */
  private List<TerminalJobPackets.Job> own() {
    var own = new ArrayList<TerminalJobPackets.Job>();
    for (var job : screen.getMenu().jobView().jobs()) if (job.owner().isEmpty()) own.add(job);
    return own;
  }

  /** How many chips fit across the grid. */
  int chips() {
    return Math.max(1, screen.columns() / CHIP_COLUMNS);
  }

  /** Chip {@code index}'s area, panel-relative. */
  Rect chip(int index) {
    return new Rect(GRID_X + index * CHIP, GRID_Y, CHIP, 18);
  }

  /** Chip {@code index}'s ✕ area, panel-relative. */
  Rect cancel(int index) {
    var chip = chip(index);
    return new Rect(chip.right() - CANCEL - 1, chip.y() + 1, CANCEL, 16);
  }

  /** Whether chip {@code index} is the "+N more" chip rather than a job. */
  private boolean overflow(int index, int jobs) {
    return jobs > chips() && index == chips() - 1;
  }

  void tick() {
    if (armedTicks > 0 && --armedTicks == 0) armed = -1;
  }

  void render(GuiGraphics graphics, int mouseX, int mouseY) {
    var font = screen.font();
    var jobs = own();
    int left = screen.left(), top = screen.top();
    for (int index = 0; index < Math.min(chips(), jobs.size()); index++) {
      var area = chip(index).offset(left, top);
      Panels.inset(graphics, area);
      if (area.contains(mouseX, mouseY)) Panels.hovered(graphics, area);
      if (overflow(index, jobs.size())) {
        String more =
            Component.translatable("gui.factorycore.terminal.job.strip.more", jobs.size() - index)
                .getString();
        graphics.drawString(
            font,
            more,
            area.x() + (area.width() - font.width(more)) / 2,
            area.y() + 5,
            Panels.LIST_TEXT,
            true);
        continue;
      }
      var job = jobs.get(index);
      var item = ResourceIcons.item(job.output().icon());
      if (item != null) graphics.renderItem(item.getDefaultInstance(), area.x() + 1, area.y() + 1);
      int textX = area.x() + 19, barWidth = area.width() - 19 - CANCEL - 3;
      String progress =
          CountText.compact(Long.toString(job.done()))
              + "/"
              + CountText.compact(Long.toString(job.total()));
      graphics.drawString(
          font,
          font.plainSubstrByWidth(progress, barWidth + CANCEL),
          textX,
          area.y() + 2,
          job.reason() == null ? Panels.LIST_TEXT : Panels.LIST_WARN,
          true);
      var track = new Rect(textX, area.y() + 12, barWidth, 3);
      Panels.track(graphics, track);
      if (job.total() > 0 && job.done() > 0)
        Panels.jobMeter(
            graphics,
            new Rect(
                track.x(),
                track.y(),
                (int) Math.max(1, track.width() * job.done() / job.total()),
                track.height()),
            job.reason() != null || job.state() != CraftingJob.State.RUNNING);
      var cross = cancel(index).offset(left, top);
      boolean cancelling = job.state() == CraftingJob.State.CANCELLING;
      if (!cancelling && cross.contains(mouseX, mouseY)) Panels.hovered(graphics, cross);
      graphics.drawString(
          font,
          "✕",
          cross.x() + 1,
          cross.y() + 4,
          cancelling ? Panels.LIST_MUTED : armed == index ? Panels.LIST_MISSING : Panels.LIST_TEXT,
          true);
    }
  }

  /** A chip's tooltip: the job, its state or reason, and what a click does. */
  boolean renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
    int index = chipAt(mouseX, mouseY);
    var jobs = own();
    if (index < 0 || index >= jobs.size()) return false;
    var lines = new ArrayList<Component>();
    if (overflow(index, jobs.size())) {
      lines.add(Component.translatable("gui.factorycore.terminal.job.strip.more.help"));
    } else {
      var job = jobs.get(index);
      lines.add(
          Component.translatable(
              "gui.factorycore.terminal.job.preview", job.output().amount(), job.output().name()));
      lines.add(
          Component.translatable(
              "gui.factorycore.terminal.job.strip.progress",
              Long.toString(job.done()),
              Long.toString(job.total()),
              Component.translatable(
                  "gui.factorycore.terminal.job.state."
                      + dev.izzy.factorycore.core.EnumKeys.key(job.state()))));
      if (job.reason() != null)
        lines.add(
            Component.translatable("gui.factorycore.terminal.job.reason." + job.reason().key())
                .withStyle(net.minecraft.ChatFormatting.GOLD));
      lines.add(
          Component.translatable(
                  cancel(index).offset(screen.left(), screen.top()).contains(mouseX, mouseY)
                      ? armed == index
                          ? "gui.factorycore.terminal.job.strip.cancel.armed"
                          : "gui.factorycore.terminal.job.strip.cancel"
                      : "gui.factorycore.terminal.job.strip.open")
              .withStyle(net.minecraft.ChatFormatting.GRAY));
    }
    graphics.renderComponentTooltip(screen.font(), lines, mouseX, mouseY);
    return true;
  }

  boolean mouseClicked(double mouseX, double mouseY, int button) {
    int index = chipAt(mouseX, mouseY);
    var jobs = own();
    if (index < 0 || index >= jobs.size()) return false;
    if (button != 0) return true;
    if (overflow(index, jobs.size())) {
      screen.showJob(-1);
      return true;
    }
    if (cancel(index).offset(screen.left(), screen.top()).contains(mouseX, mouseY)) {
      if (jobs.get(index).state() == CraftingJob.State.CANCELLING) return true;
      if (armed == index) {
        armed = -1;
        armedTicks = 0;
        screen.cancelJob(index);
      } else {
        armed = index;
        armedTicks = ARM_TICKS;
      }
      return true;
    }
    screen.showJob(index);
    return true;
  }

  /** The chip under the mouse (absolute coordinates), or -1. */
  int chipAt(double mouseX, double mouseY) {
    for (int index = 0; index < chips(); index++)
      if (chip(index).offset(screen.left(), screen.top()).contains(mouseX, mouseY)) return index;
    return -1;
  }
}
