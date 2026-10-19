package dev.izzy.factorycore.core.program;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Bounded, transient program log. It keeps the newest {@link #MAX_LINES} lines of at most {@link
 * #MAX_LENGTH} characters. A line identical to the newest one increments a visible repeat count
 * instead of evicting older diagnostics, so a failure repeated every tick cannot flush the log.
 * Guest {@code log} commands are additionally limited to {@link #MAX_PER_INVOCATION} lines per rule
 * invocation; the excess is counted and reported once as a suppression line.
 */
public final class ProgramLog {
  public static final int MAX_LINES = 16;
  public static final int MAX_LENGTH = 160;
  public static final int MAX_PER_INVOCATION = 8;

  private final ArrayDeque<String> lines = new ArrayDeque<>();
  private String last;
  private long repeats;

  /** Appends a runtime line, coalescing an immediate repeat into {@code "line (×n)"}. */
  public void add(String line) {
    String bounded = line.length() > MAX_LENGTH ? line.substring(0, MAX_LENGTH) : line;
    if (bounded.equals(last) && !lines.isEmpty()) {
      repeats = Math.min(repeats + 1, 999_999);
      lines.removeLast();
      String suffix = " (×" + repeats + ")";
      lines.addLast(
          bounded.substring(0, Math.min(bounded.length(), MAX_LENGTH - suffix.length())) + suffix);
      return;
    }
    if (lines.size() == MAX_LINES) lines.removeFirst();
    lines.addLast(bounded);
    last = bounded;
    repeats = 1;
  }

  /**
   * Appends one invocation's guest lines under {@code rule}: at most {@link #MAX_PER_INVOCATION},
   * then a single line counting the suppressed remainder. Returns the number suppressed.
   */
  public int addInvocation(String rule, List<String> guestLines) {
    int kept = Math.min(MAX_PER_INVOCATION, guestLines.size());
    for (int index = 0; index < kept; index++) add(rule + ": " + guestLines.get(index));
    int suppressed = guestLines.size() - kept;
    if (suppressed > 0)
      add(
          rule
              + ": "
              + suppressed
              + " more log lines suppressed (limit "
              + MAX_PER_INVOCATION
              + ")");
    return suppressed;
  }

  public List<String> lines() {
    return List.copyOf(lines);
  }

  public void clear() {
    lines.clear();
    last = null;
    repeats = 0;
  }
}
