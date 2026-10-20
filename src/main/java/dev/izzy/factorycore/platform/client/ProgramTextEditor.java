package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.core.program.DraftHistory;
import dev.izzy.factorycore.core.program.FriendlyCompiler;
import dev.izzy.factorycore.core.program.ProgramSyntax;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractScrollWidget;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;
import org.lwjgl.glfw.GLFW;

/**
 * Vanilla multiline editing semantics with bounded undo, token colors and positioned diagnostics.
 */
public final class ProgramTextEditor extends AbstractScrollWidget {
  private record Line(int start, int end) {}

  /** Exposes value ranges without reflection or changes to vanilla widget internals. */
  private static final class Field extends MultilineTextField {
    Field(Font font, int width) {
      super(font, width);
    }

    List<Line> lines() {
      var result = new java.util.ArrayList<Line>();
      for (var line : iterateLines()) result.add(new Line(line.beginIndex(), line.endIndex()));
      return result;
    }

    Line selection() {
      return new Line(getSelected().beginIndex(), getSelected().endIndex());
    }
  }

  private final Font font;
  private final Field field;
  private final DraftHistory history;
  private List<ProgramSyntax.Span> spans = List.of();
  private List<FriendlyCompiler.Diagnostic> diagnostics = List.of();
  private boolean restoring;

  public ProgramTextEditor(
      Font font, int x, int y, int width, int height, String initial, DraftHistory history) {
    super(x, y, width, height, Component.translatable("gui.factorycore.program.editor"));
    this.font = font;
    this.history = history;
    field = new Field(font, width - totalInnerPadding());
    field.setCharacterLimit(16_384);
    field.setValueListener(
        value -> {
          spans = ProgramSyntax.spans(value);
          diagnostics = FriendlyCompiler.compile(value).diagnostics();
          if (!restoring) history.edit(value);
        });
    field.setCursorListener(this::scrollToCursor);
    restore(initial);
  }

  public String getValue() {
    return field.value();
  }

  public int cursor() {
    return field.cursor();
  }

  public void load(String value) {
    history.load(value);
    restore(value);
  }

  public void setValue(String value) {
    field.setValue(value);
  }

  private void restore(String value) {
    restoring = true;
    field.setValue(value);
    restoring = false;
  }

  public void undo() {
    restore(history.undo());
  }

  public void redo() {
    restore(history.redo());
  }

  /** Atomic insertion, preserving multiline text and making one undo step. */
  public void insert(String text) {
    field.insertText(text);
  }

  /** Replaces just the identifier prefix before the cursor for explicit completion choices. */
  public void complete(String text) {
    var context =
        dev.izzy.factorycore.core.program.ProgramCompletion.at(field.value(), field.cursor());
    field.seekCursor(Whence.ABSOLUTE, context.start());
    field.setSelecting(true);
    field.seekCursor(Whence.ABSOLUTE, context.end());
    field.setSelecting(false);
    field.insertText(text);
  }

  public dev.izzy.factorycore.core.program.ProgramCompletion.Context completion() {
    return dev.izzy.factorycore.core.program.ProgramCompletion.at(field.value(), field.cursor());
  }

  @Override
  public boolean keyPressed(int key, int scan, int modifiers) {
    boolean control =
        Screen.hasControlDown() || (modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
    if (control && key == GLFW.GLFW_KEY_Z) {
      if (Screen.hasShiftDown()) redo();
      else undo();
      return true;
    }
    if (control && key == GLFW.GLFW_KEY_Y) {
      redo();
      return true;
    }
    if (key == GLFW.GLFW_KEY_TAB) {
      insert("    ");
      return true;
    }
    return field.keyPressed(key);
  }

  @Override
  public boolean charTyped(char character, int modifiers) {
    if (!isFocused() || !StringUtil.isAllowedChatCharacter(character)) return false;
    field.insertText(Character.toString(character));
    return true;
  }

  @Override
  public boolean mouseClicked(double x, double y, int button) {
    // Hidden behind the picker or preview, the editor must not swallow clicks meant for them.
    if (!visible) return false;
    if (button == 0 && withinContentAreaPoint(x, y)) {
      field.setSelecting(Screen.hasShiftDown());
      field.seekCursorToPoint(
          x - getX() - innerPadding(), y - getY() - innerPadding() + scrollAmount());
      return true;
    }
    return super.mouseClicked(x, y, button);
  }

  @Override
  public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
    if (super.mouseDragged(x, y, button, dx, dy)) return true;
    if (button == 0 && withinContentAreaPoint(x, y)) {
      field.setSelecting(true);
      field.seekCursorToPoint(
          x - getX() - innerPadding(), y - getY() - innerPadding() + scrollAmount());
      field.setSelecting(Screen.hasShiftDown());
      return true;
    }
    return false;
  }

  private void scrollToCursor() {
    int cursorY = field.getLineAtCursor() * 9;
    if (cursorY < scrollAmount()) setScrollAmount(cursorY);
    else if (cursorY + 9 > scrollAmount() + height - totalInnerPadding())
      setScrollAmount(cursorY + 9 - height + totalInnerPadding());
  }

  @Override
  public int getInnerHeight() {
    return field.getLineCount() * 9;
  }

  @Override
  protected double scrollRate() {
    return 9;
  }

  @Override
  protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
    int y = getY() + innerPadding();
    var source = field.value();
    var selected = field.selection();
    int firstSpan = 0;
    for (var line : field.lines()) {
      if (withinContentAreaTopBottom(y, y + 9)) {
        int x = getX() + innerPadding();
        while (firstSpan < spans.size() && spans.get(firstSpan).end() <= line.start()) firstSpan++;
        for (int index = firstSpan; index < spans.size(); index++) {
          var span = spans.get(index);
          if (span.start() >= line.end()) break;
          int start = Math.max(line.start(), span.start());
          int end = Math.min(line.end(), span.end());
          if (start >= end) continue;
          graphics.drawString(
              font,
              source.substring(start, end),
              x + font.width(source.substring(line.start(), start)),
              y,
              color(span.kind()),
              false);
        }
        if (selected.start() < line.end() && selected.end() > line.start()) {
          int start = Math.max(selected.start(), line.start());
          int end = Math.min(selected.end(), line.end());
          graphics.fill(
              RenderType.guiTextHighlight(),
              x + font.width(source.substring(line.start(), start)),
              y,
              x + font.width(source.substring(line.start(), end)),
              y + 9,
              0xff0000ff);
        }
        if (isFocused() && field.cursor() >= line.start() && field.cursor() <= line.end()) {
          int cursorX = x + font.width(source.substring(line.start(), field.cursor()));
          graphics.fill(cursorX, y - 1, cursorX + 1, y + 9, 0xffeeeeee);
        }
        for (var diagnostic : diagnostics) {
          int at = offset(source, diagnostic.line(), diagnostic.column());
          if (at >= line.start() && at <= line.end()) {
            int errorX = x + font.width(source.substring(line.start(), at));
            graphics.fill(errorX, y + 8, errorX + 5, y + 9, 0xffff8a80);
          }
        }
      }
      y += 9;
    }
  }

  @Override
  public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
    super.renderWidget(graphics, mouseX, mouseY, partial);
    if (visible && isMouseOver(mouseX, mouseY) && !diagnostics.isEmpty()) {
      var first = diagnostics.getFirst();
      graphics.renderTooltip(
          font,
          Component.literal(first.line() + ":" + first.column() + " " + first.message()),
          mouseX,
          mouseY);
    }
  }

  private static int offset(String source, int line, int column) {
    int at = 0;
    for (int current = 1; current < line && at < source.length(); current++) {
      int newline = source.indexOf('\n', at);
      if (newline < 0) return source.length();
      at = newline + 1;
    }
    return Math.min(source.length(), at + column - 1);
  }

  private static int color(ProgramSyntax.Kind kind) {
    return switch (kind) {
      case KEYWORD -> 0xc792ea;
      case CALL -> 0x82aaff;
      case STRING -> 0xc3e88d;
      case NUMBER -> 0xf7bb80;
      case COMMENT -> 0x80918b;
      case TEXT -> 0xeeeeee;
    };
  }

  @Override
  protected void renderDecorations(GuiGraphics graphics) {
    super.renderDecorations(graphics);
    var count =
        Component.translatable(
            "gui.multiLineEditBox.character_limit", field.value().length(), 16_384);
    graphics.drawString(
        font, count, getX() + width - font.width(count), getY() + height + 4, 0xa0a0a0, false);
  }

  @Override
  protected void updateWidgetNarration(NarrationElementOutput output) {
    output.add(
        NarratedElementType.TITLE,
        Component.translatable("gui.narrate.editBox", getMessage(), field.value()));
    if (!diagnostics.isEmpty()) {
      var first = diagnostics.getFirst();
      output.add(
          NarratedElementType.HINT,
          Component.literal(first.line() + ":" + first.column() + " " + first.message()));
    }
  }
}
