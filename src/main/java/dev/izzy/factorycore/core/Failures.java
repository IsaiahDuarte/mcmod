package dev.izzy.factorycore.core;

/** Short descriptions of caught failures for logs, diagnostics and player-visible errors. */
public final class Failures {
  private Failures() {}

  /** The failure's message, or its class's simple name when it has none. */
  public static String message(Throwable failure) {
    return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
  }
}
