package dev.izzy.factorycore.platform.client;

import static dev.izzy.factorycore.platform.client.TerminalScreen.GRID_X;
import static dev.izzy.factorycore.platform.client.TerminalScreen.GRID_Y;

import dev.izzy.factorycore.platform.client.ui.PanelLayout;
import dev.izzy.factorycore.platform.client.ui.Panels;
import dev.izzy.factorycore.platform.client.ui.Rect;
import dev.izzy.factorycore.platform.storage.TerminalJobPackets;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Q138 (n4): the craft dialog. Clicking a craftable entry in the Items grid (a "Craft" ghost, or
 * middle-click on any craftable) opens it over the grid instead of switching to the Jobs tab. It
 * asks the server to focus that craftable, previews the typed amount as you type, and starts or
 * keeps stock in place; Details opens the full plan in Jobs. Its commands are the Jobs tab's, with
 * the same authority checks on the server.
 */
final class TerminalCraftDialog {
  static final int WIDTH = 180, HEIGHT = 96;

  private final TerminalScreen screen;
  private String icon = "", name = "";
  private int selection = -1, previewDelay = -1;
  private EditBox amount;
  private Button down, up, start, keep, details, close;
  private String error = "";

  TerminalCraftDialog(TerminalScreen screen) {
    this.screen = screen;
  }

  boolean open() {
    return !icon.isEmpty();
  }

  /** The item the dialog is for (for verification). */
  String item() {
    return icon;
  }

  /** The panel-relative box, centred on the grid. */
  private Rect box() {
    int width = Math.min(WIDTH, screen.main() - 2 * GRID_X);
    return new Rect((screen.main() - width) / 2, GRID_Y + 2, width, HEIGHT);
  }

  /** Widgets are listeners only: the dialog draws them itself, above the grid's items. */
  void init() {
    var box = box();
    int x = box.x() + 6, width = box.width() - 12;
    var first =
        PanelLayout.fit(
            new Rect(x, box.y() + 24, width, PanelLayout.ROW), new int[] {18, 18, 60}, 2);
    down = button("step_down", first.get(0), () -> step(-1));
    up = button("step_up", first.get(1), () -> step(1));
    var field = first.get(2);
    String typed = amount == null ? "1" : amount.getValue();
    amount =
        screen.listen(
            new EditBox(
                screen.font(),
                screen.left() + field.x() + 1,
                screen.top() + field.y() + 1,
                field.width() - 2,
                16,
                Component.translatable("gui.factorycore.terminal.job.amount")));
    amount.setMaxLength(CraftAmount.MAX_LENGTH);
    amount.setValue(typed);
    amount.setTooltip(
        Tooltip.create(
            Component.translatable(
                "gui.factorycore.terminal.job.amount.help", TerminalJobPackets.MAX_AMOUNT)));
    amount.setResponder(
        value -> {
          error = "";
          previewDelay = 6;
        });
    var second =
        PanelLayout.fit(
            new Rect(x, box.bottom() - PanelLayout.ROW - 5, width, PanelLayout.ROW),
            new int[] {40, 34, 40, 34},
            2);
    start = button("start", second.get(0), this::start);
    keep = button("keep", second.get(1), this::keep);
    details =
        screen.listen(
            ScreenButtons.labeled(
                "gui.factorycore.terminal.craft_dialog.",
                "details",
                screen.left() + second.get(2).x(),
                screen.top() + second.get(2).y(),
                second.get(2).width(),
                second.get(2).height(),
                this::details));
    close =
        screen.listen(
            ScreenButtons.labeled(
                "gui.factorycore.terminal.craft_dialog.",
                "close",
                screen.left() + second.get(3).x(),
                screen.top() + second.get(3).y(),
                second.get(3).width(),
                second.get(3).height(),
                this::close));
    show();
  }

  private Button button(String key, Rect cell, Runnable action) {
    return screen.listen(
        ScreenButtons.labeled(
            "gui.factorycore.terminal.job.",
            key,
            screen.left() + cell.x(),
            screen.top() + cell.y(),
            cell.width(),
            cell.height(),
            action));
  }

  /** Opens the dialog for a craftable row; the server focuses it in the craftable list. */
  void openFor(String item, String shownName) {
    icon = item;
    name = shownName;
    selection = -1;
    error = "";
    amount.setValue("1");
    screen.setFocused(amount);
    amount.setFocused(true);
    show();
  }

  void close() {
    icon = "";
    selection = -1;
    amount.setFocused(false);
    show();
  }

  private void details() {
    var item = icon;
    close();
    screen.showJobs(item);
  }

  private void show() {
    boolean visible = open();
    for (var widget :
        new net.minecraft.client.gui.components.AbstractWidget[] {
          amount, down, up, start, keep, details, close
        }) {
      widget.visible = visible;
      widget.active = visible;
    }
  }

  private TerminalMenu menu() {
    return screen.getMenu();
  }

  /** Finds the focused craftable once listed, and previews the typed amount after a pause. */
  void tick() {
    if (!open()) return;
    var jobs = menu().jobView();
    if (selection < 0
        || selection >= jobs.craftables().size()
        || !jobs.craftables().get(selection).icon().equals(icon)) {
      selection = -1;
      for (int index = 0; index < jobs.craftables().size(); index++)
        if (jobs.craftables().get(index).icon().equals(icon)) {
          selection = index;
          previewDelay = 0;
          break;
        }
    }
    if (previewDelay >= 0 && --previewDelay < 0) preview();
    boolean listed = selection >= 0;
    down.active = listed;
    up.active = listed;
    keep.active = listed && parsed().valid() && !parsed().target();
    start.active = listed && parsed().valid();
  }

  private CraftAmount parsed() {
    return CraftAmount.parse(amount.getValue(), TerminalJobPackets.MAX_AMOUNT);
  }

  private void preview() {
    var typed = parsed();
    error = typed.error();
    amount.setTextColor(typed.valid() ? Panels.FIELD_TEXT : Panels.FIELD_ERROR);
    if (typed.valid() && selection >= 0)
      job(
          typed.target() ? TerminalJobPackets.TARGET : TerminalJobPackets.PREVIEW,
          selection,
          typed.amount());
  }

  /** The preview shown is this item at the typed amount, and nothing is missing. */
  private boolean previewed() {
    var jobs = menu().jobView();
    var typed = parsed();
    return jobs.preview().icon().equals(icon)
        && jobs.previewReason() == null
        && typed.valid()
        && !typed.target()
        && jobs.preview().amount().equals(Integer.toString(typed.amount()));
  }

  /** Starts the shown plan, or previews and starts the typed amount in one step. */
  private void start() {
    var typed = parsed();
    if (!typed.valid() || selection < 0) return;
    if (previewed()) job(TerminalJobPackets.START, 0, 1);
    else
      job(
          typed.target() ? TerminalJobPackets.QUICK_TARGET : TerminalJobPackets.QUICK,
          selection,
          typed.amount());
    close();
  }

  private void keep() {
    var typed = parsed();
    if (!typed.valid() || typed.target() || selection < 0) return;
    job(TerminalJobPackets.KEEP, selection, typed.amount());
    close();
  }

  private void step(int direction) {
    int size =
        net.minecraft.client.gui.screens.Screen.hasControlDown()
            ? 64
            : net.minecraft.client.gui.screens.Screen.hasShiftDown() ? 10 : 1;
    var typed = parsed();
    int current = typed.valid() && !typed.target() ? typed.amount() : 0;
    amount.setValue(
        Integer.toString(
            Math.max(1, Math.min(TerminalJobPackets.MAX_AMOUNT, current + direction * size))));
  }

  /** Test hook: types {@code text} as the amount and previews it at once. */
  void typeAmount(String text) {
    amount.setValue(text);
    previewDelay = 0;
  }

  /** Test hook: presses Start. */
  void pressStart() {
    start();
  }

  private void job(int action, int index, int value) {
    var menu = menu();
    PacketDistributor.sendToServer(
        new TerminalJobPackets.Command(
            menu.containerId, menu.session(), menu.jobView().revision(), action, index, value));
  }

  // ---------------------------------------------------------------- input

  /** While open the dialog is modal: clicks outside it close it, clicks inside reach only it. */
  boolean mouseClicked(double mouseX, double mouseY, int button) {
    if (!open()) return false;
    var box = box().offset(screen.left(), screen.top());
    if (mouseX < box.x() || mouseX >= box.right() || mouseY < box.y() || mouseY >= box.bottom()) {
      close();
      return true;
    }
    for (var widget :
        new net.minecraft.client.gui.components.AbstractWidget[] {
          amount, down, up, start, keep, details, close
        })
      if (widget.visible && widget.isMouseOver(mouseX, mouseY)) {
        screen.setFocused(widget);
        widget.mouseClicked(mouseX, mouseY, button);
        return true;
      }
    amount.setFocused(false);
    return true;
  }

  /** Escape closes the dialog (not the terminal); Enter starts; Up/Down step; typing edits. */
  Boolean keyPressed(int key, int scan, int modifiers) {
    if (!open()) return null;
    if (key == GLFW.GLFW_KEY_ESCAPE) {
      close();
      return true;
    }
    if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
      start();
      return true;
    }
    if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
      step(key == GLFW.GLFW_KEY_UP ? 1 : -1);
      return true;
    }
    if (amount.isFocused()) amount.keyPressed(key, scan, modifiers);
    return true;
  }

  boolean charTyped(char c, int modifiers) {
    if (!open()) return false;
    if (amount.isFocused()) amount.charTyped(c, modifiers);
    return true;
  }

  // ---------------------------------------------------------------- drawing

  /** Drawn after the screen, raised above the grid's items; then its own widgets. */
  void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
    if (!open()) return;
    var font = screen.font();
    var box = box().offset(screen.left(), screen.top());
    graphics.pose().pushPose();
    graphics.pose().translate(0, 0, 350);
    Panels.panel(graphics, box);
    var location = ResourceLocation.tryParse(icon);
    if (location != null && BuiltInRegistries.ITEM.containsKey(location))
      graphics.renderFakeItem(
          new ItemStack(BuiltInRegistries.ITEM.get(location)), box.x() + 6, box.y() + 5);
    int textX = box.x() + 26, width = box.right() - 6 - textX;
    graphics.drawString(
        font,
        font.plainSubstrByWidth(
            Component.translatable("gui.factorycore.terminal.craft_dialog.title", name).getString(),
            width),
        textX,
        box.y() + 9,
        Panels.TEXT,
        false);
    var jobs = menu().jobView();
    Component summary;
    int color = Panels.MUTED;
    if (!error.isEmpty()) {
      summary =
          Component.translatable(
              "gui.factorycore.terminal.job.amount." + error, TerminalJobPackets.MAX_AMOUNT);
      color = Panels.BAD;
    } else if (selection < 0)
      summary = Component.translatable("gui.factorycore.terminal.craft_dialog.finding");
    else if (!jobs.preview().icon().equals(icon))
      summary = Component.translatable("gui.factorycore.terminal.craft_dialog.type");
    else if (!jobs.missing().isEmpty()) {
      var shortage = jobs.missing().getFirst();
      summary =
          Component.translatable(
              "gui.factorycore.terminal.job.missing",
              shortage.amount(),
              shortage.name(),
              jobs.missing().size());
      color = Panels.BAD;
    } else if (jobs.previewReason() != null) {
      summary =
          Component.translatable("gui.factorycore.terminal.job.status." + jobs.status().key());
      color = Panels.WARN;
    } else
      summary =
          Component.translatable(
              "gui.factorycore.terminal.job.plan", jobs.previewSteps(), jobs.previewCrafts());
    int lineY = box.y() + 24 + PanelLayout.ROW + 5;
    for (var line : font.split(summary, box.width() - 12).stream().limit(2).toList()) {
      graphics.drawString(font, line, box.x() + 6, lineY, color, false);
      lineY += PanelLayout.LINE;
    }
    for (var widget :
        new net.minecraft.client.gui.components.AbstractWidget[] {
          amount, down, up, start, keep, details, close
        }) widget.render(graphics, mouseX, mouseY, partial);
    graphics.pose().popPose();
  }

  /** Over the dialog nothing beneath shows a tooltip. */
  boolean covers(double mouseX, double mouseY) {
    if (!open()) return false;
    var box = box().offset(screen.left(), screen.top());
    return mouseX >= box.x() && mouseX < box.right() && mouseY >= box.y() && mouseY < box.bottom();
  }
}
