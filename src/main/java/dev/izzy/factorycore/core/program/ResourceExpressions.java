package dev.izzy.factorycore.core.program;

/** Sentence-program (ADR 0068) names and quoted text for editor insertion. */
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
    literal(id);
    return ProgramPrinter.name(dev.izzy.factorycore.core.resource.ResourceKind.ITEM, id);
  }

  public static String fluid(String id) {
    literal(id);
    return ProgramPrinter.name(dev.izzy.factorycore.core.resource.ResourceKind.FLUID, id);
  }

  /** A machine label as a program names it: bare when it is one plain word, else quoted. */
  public static String label(String label) {
    literal(label);
    return ProgramPrinter.label(label);
  }
}
