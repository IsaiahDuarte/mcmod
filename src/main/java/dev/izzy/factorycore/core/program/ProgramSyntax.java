package dev.izzy.factorycore.core.program;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Display-only lexer. Invalid and unfinished source stays editable; compilation owns validity. */
public final class ProgramSyntax {
  /** The words of the language (ADR 0068), offered by the editor's word picker and highlighted. */
  public static final List<String> KEYWORD_WORDS =
      SentenceCompiler.KEYWORDS.stream().sorted().toList();

  public enum Kind {
    TEXT,
    KEYWORD,
    STRING,
    NUMBER,
    COMMENT
  }

  public record Span(int start, int end, Kind kind) {}

  private ProgramSyntax() {}

  /** Nonoverlapping UTF-16 ranges covering the source exactly, including escaped strings. */
  public static List<Span> spans(String source) {
    if (source.length() > 16_384) throw new IllegalArgumentException("Draft too long");
    var spans = new ArrayList<Span>();
    int cursor = 0;
    while (cursor < source.length()) {
      int start = cursor;
      char first = source.charAt(cursor++);
      Kind kind = Kind.TEXT;
      if (first == '"') {
        kind = Kind.STRING;
        while (cursor < source.length()) {
          char next = source.charAt(cursor++);
          if (next == '\\' && cursor < source.length()) cursor++;
          else if (next == '"' || next == '\n') break;
        }
      } else if (first == '#') {
        kind = Kind.COMMENT;
        while (cursor < source.length() && source.charAt(cursor) != '\n') cursor++;
      } else if (Character.isDigit(first)) {
        kind = Kind.NUMBER;
        while (cursor < source.length()
            && (Character.isDigit(source.charAt(cursor)) || source.charAt(cursor) == '.')) cursor++;
      } else if (Character.isLetter(first) || first == '_') {
        while (cursor < source.length()
            && (Character.isLetterOrDigit(source.charAt(cursor)) || source.charAt(cursor) == '_'))
          cursor++;
        var word = source.substring(start, cursor).toLowerCase(Locale.ROOT);
        if (SentenceCompiler.KEYWORDS.contains(word)) kind = Kind.KEYWORD;
      }
      spans.add(new Span(start, cursor, kind));
    }
    return List.copyOf(spans);
  }
}
