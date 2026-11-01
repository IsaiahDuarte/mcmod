package dev.izzy.factorycore.platform;

/**
 * Loads an optional integration's entry class by name (ADR 0069), so mandatory classes never refer
 * to an integration or its mod's API and load without that mod installed.
 */
public final class Integrations {
  private Integrations() {}

  /** Calls {@code className}'s public static {@code install()}; a failure is an error at load. */
  public static void install(String className) {
    try {
      Class.forName(className).getMethod("install").invoke(null);
    } catch (ReflectiveOperationException | LinkageError failure) {
      throw new IllegalStateException("Optional integration failed to load: " + className, failure);
    }
  }
}
