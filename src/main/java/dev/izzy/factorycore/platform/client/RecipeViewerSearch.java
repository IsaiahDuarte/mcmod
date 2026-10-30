package dev.izzy.factorycore.platform.client;

/**
 * Ergonomics Q17: the search box of an installed recipe viewer, for the terminal's search sync. An
 * optional integration installs a bridge when its runtime is ready and removes it when it is not;
 * without one the terminal offers no sync button. Client thread only.
 */
public final class RecipeViewerSearch {
  /** A recipe viewer's search text, read and written. */
  public interface Bridge {
    String text();

    void text(String value);
  }

  private static Bridge bridge;

  private RecipeViewerSearch() {}

  public static void install(Bridge value) {
    bridge = value;
  }

  public static void clear() {
    bridge = null;
  }

  /** The installed bridge, or null without a recipe viewer. */
  public static Bridge bridge() {
    return bridge;
  }
}
