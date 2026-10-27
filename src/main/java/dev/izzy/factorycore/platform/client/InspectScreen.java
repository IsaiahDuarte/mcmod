package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.platform.client.ui.PanelLayout;
import dev.izzy.factorycore.platform.client.ui.Panels;
import dev.izzy.factorycore.platform.client.ui.Rect;
import dev.izzy.factorycore.platform.storage.MachineInspection;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Q12: the Label Tool's machine inspection on the panel kit: what the machine offers per side and
 * the move lines for each, wrapped in a scrolling inset. Copy puts every move line on the
 * clipboard. 344 px wide, like the program editor, so a move line reads in one or two rows.
 */
public final class InspectScreen extends Screen {
  private static final int WIDTH = 344, ROWS = 16;

  private final PanelLayout layout =
      new PanelLayout(WIDTH, PanelLayout.heightFor(ROWS * PanelLayout.LINE + 4));
  private final MachineInspection.View view;
  private final List<FormattedCharSequence> rows = new ArrayList<>();
  private final List<Boolean> moves = new ArrayList<>();
  private int left, top, scroll;
  private Rect list;
  private Button copy;

  private InspectScreen(MachineInspection.View view) {
    super(Component.literal(view.title()));
    this.view = view;
  }

  static void open(MachineInspection.View view) {
    Minecraft.getInstance().setScreen(new InspectScreen(view));
  }

  /** The move lines, as copied. */
  private List<String> moveLines() {
    return view.lines().stream().filter(line -> line.startsWith("move ")).toList();
  }

  @Override
  protected void init() {
    left = (width - layout.width()) / 2;
    top = (height - layout.height()) / 2;
    list = layout.body();
    rows.clear();
    moves.clear();
    for (var line : view.lines())
      for (var row : font.split(Component.literal(line), list.width() - 8)) {
        rows.add(row);
        moves.add(line.startsWith("move "));
      }
    var footer = layout.footer();
    var copyLabel = Component.translatable("gui.factorycore.inspect.copy");
    var done = net.minecraft.network.chat.CommonComponents.GUI_DONE;
    var cells =
        PanelLayout.alignRight(
            footer, new int[] {font.width(copyLabel) + 12, font.width(done) + 16}, PanelLayout.GAP);
    copy =
        addRenderableWidget(
            Button.builder(
                    copyLabel,
                    button -> {
                      minecraft.keyboardHandler.setClipboard(String.join("\n", moveLines()));
                      button.setMessage(Component.translatable("gui.factorycore.inspect.copied"));
                    })
                .bounds(
                    left + cells.get(0).x(),
                    top + footer.y(),
                    cells.get(0).width(),
                    footer.height())
                .tooltip(
                    Tooltip.create(Component.translatable("gui.factorycore.inspect.copy.help")))
                .build());
    copy.active = !moveLines().isEmpty();
    addRenderableWidget(
        Button.builder(done, button -> onClose())
            .bounds(
                left + cells.get(1).x(), top + footer.y(), cells.get(1).width(), footer.height())
            .build());
  }

  private int visibleRows() {
    return (list.height() - 4) / PanelLayout.LINE;
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    int max = Math.max(0, rows.size() - visibleRows());
    scroll = Math.max(0, Math.min(max, scroll + (scrollY > 0 ? -1 : 1)));
    return true;
  }

  @Override
  public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) {
    super.renderBackground(g, mouseX, mouseY, partial);
    Panels.panel(g, left, top, layout);
    Panels.title(g, font, title, left, top);
    var area = list.offset(left, top);
    Panels.inset(g, area);
    for (int line = 0; line < visibleRows() && scroll + line < rows.size(); line++)
      g.drawString(
          font,
          rows.get(scroll + line),
          area.x() + 4,
          area.y() + 3 + line * PanelLayout.LINE,
          moves.get(scroll + line) ? Panels.LIST_TEXT : Panels.LIST_WARN,
          true);
    if (rows.size() > visibleRows()) {
      int track = area.height() - 2;
      int thumb = Math.max(8, track * visibleRows() / rows.size());
      int y = area.y() + 1 + (track - thumb) * scroll / Math.max(1, rows.size() - visibleRows());
      Panels.lightThumb(g, new Rect(area.right() - 3, y, 2, thumb));
    }
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }
}
