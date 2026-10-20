package dev.izzy.factorycore.core;

/**
 * Validates {@code namespace:path} identifiers (registry ids, dimensions) without allocating.
 * Accepts exactly {@code [a-z0-9_.-]+:[a-z0-9/._-]+} up to 256 characters. Hot paths construct
 * positions and resource keys every tick, so a regex matcher per call was a top allocator.
 */
public final class NamespacedId {
  public static final int MAX_LENGTH = 256;

  private NamespacedId() {}

  public static boolean isValid(String id) {
    if (id == null || id.isEmpty() || id.length() > MAX_LENGTH) return false;
    int colon = id.indexOf(':');
    if (colon <= 0 || colon == id.length() - 1) return false;
    for (int i = 0; i < colon; i++) if (!namespaceChar(id.charAt(i))) return false;
    for (int i = colon + 1; i < id.length(); i++) if (!pathChar(id.charAt(i))) return false;
    return true;
  }

  private static boolean namespaceChar(char c) {
    return c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '.' || c == '-';
  }

  private static boolean pathChar(char c) {
    return namespaceChar(c) || c == '/';
  }
}
