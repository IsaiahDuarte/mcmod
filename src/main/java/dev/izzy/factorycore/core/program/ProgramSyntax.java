package dev.izzy.factorycore.core.program;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Display-only lexer. Invalid and unfinished source stays editable; compilation owns validity. */
public final class ProgramSyntax {
  /**
   * Highlighted and offered by the editor's keyword picker, v1 then language v2 (ADR 0062). The
   * compiler owns which words are reserved; this list is display-only.
   */
  public static final java.util.List<String> KEYWORD_WORDS =
      java.util.List.of(
          "every",
          "ticks",
          "seconds",
          "as",
          "for",
          "in",
          "keep",
          "stocked",
          "with",
          "of",
          "from",
          "to",
          "storage",
          "move",
          "limit",
          "craft",
          "if",
          "log",
          "else",
          "and",
          "or",
          "not",
          "has",
          "side",
          "slots",
          "down",
          "up",
          "north",
          "south",
          "west",
          "east",
          "emit",
          "redstone",
          "on",
          "except",
          "everything",
          "items",
          "fluids");

  public static final java.util.List<String> CALL_WORDS =
      java.util.List.of("item", "fluid", "energy", "tag", "stock", "machines");

  private static final Set<String> KEYWORDS = Set.copyOf(KEYWORD_WORDS);
  private static final Set<String> CALLS = Set.copyOf(CALL_WORDS);

  public enum Kind {
    TEXT,
    KEYWORD,
    CALL,
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
          else if (next == '"') break;
        }
      } else if (first == '#') {
        kind = Kind.COMMENT;
        while (cursor < source.length() && source.charAt(cursor) != '\n') cursor++;
      } else if (Character.isDigit(first)) {
        kind = Kind.NUMBER;
        while (cursor < source.length() && Character.isDigit(source.charAt(cursor))) cursor++;
      } else if (Character.isJavaIdentifierStart(first)) {
        while (cursor < source.length() && Character.isJavaIdentifierPart(source.charAt(cursor)))
          cursor++;
        var word = source.substring(start, cursor);
        if (KEYWORDS.contains(word)) kind = Kind.KEYWORD;
        else if (CALLS.contains(word)) kind = Kind.CALL;
      }
      spans.add(new Span(start, cursor, kind));
    }
    return List.copyOf(spans);
  }
}
