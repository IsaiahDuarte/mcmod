package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.core.program.DraftHistory;
import dev.izzy.factorycore.core.program.FriendlyCompiler;
import dev.izzy.factorycore.core.program.ProgramSuggestions;
import dev.izzy.factorycore.core.program.ProgramSyntax;
import dev.izzy.factorycore.platform.program.GameVocabulary;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;
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
 * Vanilla multiline editing semantics with bounded undo, token colors, positioned diagnostics and
 * suggestions while typing (Q123): the best match shows faintly after the cursor and a short list
 * under the line; Tab accepts, Up and Down choose.
 */
public final class ProgramTextEditor extends AbstractScrollWidget {
  private record Line(int start, int end) {}

  /** Exposes value ranges without reflection or changes to vanilla widget internals. */
  private static final class Field extends MultilineTextField {
    Field(Font font, int width) {
      super(font, width);
    }

    List<Line> lines() {
      var result = new ArrayList<Line>();
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

  /** Shown while typing; any other move of the cursor hides the suggestions. */
  private static final int SHOWN = 5;

  private final Supplier<Collection<String>> labels;
  private ProgramSuggestions.Context suggestion;
  private List<String> options = List.of();
  private int selected;
  private boolean typing;

  public ProgramTextEditor(
      Font font,
      int x,
      int y,
      int width,
      int height,
      String initial,
      DraftHistory history,
      Supplier<Collection<String>> labels) {
    super(x, y, width, height, Component.translatable("gui.factorycore.program.editor"));
    this.font = font;
    this.history = history;
    this.labels = labels;
    field = new Field(font, width - totalInnerPadding());
    field.setCharacterLimit(16_384);
    field.setValueListener(
        value -> {
          spans = ProgramSyntax.spans(value);
          diagnostics = FriendlyCompiler.compile(value, GameVocabulary.get()).diagnostics();
          if (!restoring) history.edit(value);
        });
    field.setCursorListener(this::scrollToCursor);
    restore(initial);
  }

  /** The live problems with the draft as typed, first one first. */
  public List<FriendlyCompiler.Diagnostic> diagnostics() {
    return diagnostics;
  }

  /** The suggestions shown now (empty when none are), best first. */
  public List<String> suggestions() {
    return showing() ? options : List.of();
  }

  private boolean showing() {
    if (!typing || !isFocused() || suggestion == null || options.isEmpty()) return false;
    boolean waiting =
        switch (suggestion.slot()) {
          case PLACE, AFTER_PLACE, UNIT, NONE -> true;
          default -> false;
        };
    return (waiting || !suggestion.prefix().isEmpty())
        && !(options.size() == 1 && options.getFirst().equalsIgnoreCase(suggestion.prefix()));
  }

  private void suggest() {
    suggestion = ProgramSuggestions.at(field.value(), field.cursor());
    options = ProgramSuggestions.options(suggestion, GameVocabulary.get(), labels.get(), 8);
    selected = 0;
  }

  /** Replaces the text being typed with the chosen suggestion, then a space. */
  public void accept(String option) {
    if (suggestion == null) suggest();
    var value = field.value();
    int end = suggestion.end();
    boolean spaced = end < value.length() && value.charAt(end) == ' ';
    replace(suggestion.start(), end, spaced ? option : option + " ");
    typing = true;
    suggest();
  }

  private void replace(int start, int end, String text) {
    field.seekCursor(Whence.ABSOLUTE, start);
    field.setSelecting(true);
    field.seekCursor(Whence.ABSOLUTE, end);
    field.setSelecting(false);
    field.insertText(text);
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

  /** Replaces the text being typed (a whole item name, label or word) for a picker choice. */
  public void complete(String text) {
    var context = completion();
    replace(context.start(), context.end(), text);
  }

  public ProgramSuggestions.Context completion() {
    return ProgramSuggestions.at(field.value(), field.cursor());
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
    if (showing()) {
      int shown = Math.min(SHOWN, options.size());
      if (key == GLFW.GLFW_KEY_TAB) {
        accept(options.get(selected));
        return true;
      }
      if (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_UP) {
        selected = (selected + (key == GLFW.GLFW_KEY_DOWN ? 1 : shown - 1)) % shown;
        return true;
      }
    }
    if (key == GLFW.GLFW_KEY_TAB) {
      insert("    ");
      return true;
    }
    // Deleting keeps suggesting; moving the cursor or a new line stops until the next letter.
    typing = key == GLFW.GLFW_KEY_BACKSPACE && typing;
    boolean handled = field.keyPressed(key);
    if (typing) suggest();
    return handled;
  }

  @Override
  public boolean charTyped(char character, int modifiers) {
    if (!isFocused() || !StringUtil.isAllowedChatCharacter(character)) return false;
    field.insertText(Character.toString(character));
    typing = true;
    suggest();
    return true;
  }

  @Override
  public boolean mouseClicked(double x, double y, int button) {
    // Hidden behind the picker or preview, the editor must not swallow clicks meant for them.
    if (!visible) return false;
    if (button == 0 && withinContentAreaPoint(x, y)) {
      typing = false;
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
          if (showing() && field.cursor() == suggestion.end()) {
            var ghost =
                ProgramSuggestions.ghost(suggestion.prefix(), List.of(options.get(this.selected)));
            if (!ghost.isEmpty()) graphics.drawString(font, ghost, cursorX, y, GHOST, false);
          }
          graphics.fill(cursorX, y - 1, cursorX + 1, y + 9, 0xffeeeeee);
          if (showing()) {
            int startX =
                x
                    + font.width(
                        source.substring(line.start(), Math.max(line.start(), suggestion.start())));
            list(graphics, startX, y);
          }
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

  private static final int GHOST = 0x7f8a99;

  /** The suggestion list under the line being typed (above it near the bottom), inside the box. */
  private void list(GuiGraphics graphics, int x, int lineY) {
    int shown = Math.min(SHOWN, options.size());
    int width = 0;
    for (int index = 0; index < shown; index++)
      width = Math.max(width, font.width(options.get(index)));
    width += 6;
    int height = shown * 10 + 2;
    int right = getX() + this.width - innerPadding();
    int left = Math.max(getX() + innerPadding(), Math.min(x - 3, right - width));
    int bottom = getY() + this.height + (int) scrollAmount();
    int top = lineY + 10 + height <= bottom ? lineY + 10 : lineY - height - 1;
    graphics.pose().pushPose();
    graphics.pose().translate(0, 0, 200);
    graphics.fill(left - 1, top - 1, left + width + 1, top + height + 1, 0xff5a6170);
    graphics.fill(left, top, left + width, top + height, 0xf0101318);
    for (int index = 0; index < shown; index++) {
      int rowY = top + 1 + index * 10;
      if (index == selected) graphics.fill(left, rowY, left + width, rowY + 10, 0xff2d4a6e);
      graphics.drawString(
          font,
          options.get(index),
          left + 3,
          rowY + 1,
          index == selected ? 0xffffff : 0xc0c8d0,
          false);
    }
    graphics.pose().popPose();
  }

  @Override
  public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
    super.renderWidget(graphics, mouseX, mouseY, partial);
    if (visible && isMouseOver(mouseX, mouseY) && !diagnostics.isEmpty() && !showing())
      graphics.renderComponentTooltip(font, problem(diagnostics.getFirst()), mouseX, mouseY);
  }

  /** A problem as lines: where and what, then the hint and an example when there are any. */
  public static List<Component> problem(FriendlyCompiler.Diagnostic diagnostic) {
    var lines = new java.util.ArrayList<Component>();
    lines.add(
        Component.literal(
            diagnostic.line() + ":" + diagnostic.column() + " " + diagnostic.message()));
    if (diagnostic.hint() != null) lines.add(Component.literal(diagnostic.hint()));
    if (diagnostic.example() != null)
      lines.add(Component.translatable("gui.factorycore.program.example", diagnostic.example()));
    return lines;
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
