package dev.izzy.factorycore.core.program;

/** Friendly-language resource expressions with escaped string literals, for editor insertion. */
public final class ResourceExpressions {
  private ResourceExpressions() {}

  /** Escapes {@code \} and {@code "}; the compiler accepts exactly these two escapes. */
  public static String literal(String text) {
    if (text.length() > FriendlyCompiler.MAX_STRING
        || text.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Unsupported resource identifier");
    return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
  }

  public static String item(String id) {
    return "item(" + literal(id) + ")";
  }

  public static String fluid(String id) {
    return "fluid(" + literal(id) + ")";
  }
}
