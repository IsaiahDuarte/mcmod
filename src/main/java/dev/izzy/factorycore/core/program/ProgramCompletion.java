package dev.izzy.factorycore.core.program;

/** Cursor-aware replacement ranges for the native resource/label/keyword picker. */
public final class ProgramCompletion {
  public record Context(int start, int end, boolean quoted, String call, String prefix) {}

  private ProgramCompletion() {}

  public static Context at(String source, int cursor) {
    if (cursor < 0 || cursor > source.length())
      throw new IllegalArgumentException("Cursor outside draft");
    for (var span : ProgramSyntax.spans(source)) {
      if (span.kind() != ProgramSyntax.Kind.STRING || cursor <= span.start() || cursor > span.end())
        continue;
      boolean closed = span.end() > span.start() + 1 && source.charAt(span.end() - 1) == '"';
      if (closed && cursor == span.end()) continue;
      int before = span.start();
      while (before > 0 && Character.isWhitespace(source.charAt(before - 1))) before--;
      String call = "";
      if (before > 0 && source.charAt(before - 1) == '(') {
        int end = --before;
        while (before > 0 && Character.isJavaIdentifierPart(source.charAt(before - 1))) before--;
        call = source.substring(before, end);
      }
      return new Context(
          span.start() + 1,
          span.end() - (closed ? 1 : 0),
          true,
          call,
          source.substring(span.start() + 1, cursor));
    }
    int start = cursor, end = cursor;
    while (start > 0 && token(source.charAt(start - 1))) start--;
    while (end < source.length() && token(source.charAt(end))) end++;
    return new Context(start, end, false, "", source.substring(start, cursor));
  }

  private static boolean token(char character) {
    return Character.isJavaIdentifierPart(character)
        || character == ':'
        || character == '/'
        || character == '.'
        || character == '-';
  }
}
