package dev.izzy.factorycore.platform.client;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Shortens a " · "-separated status line to a pixel width without cutting a word: whole trailing
 * parts are dropped first, then the last kept part loses whole words; "…" marks anything removed.
 * Only a single word wider than the room is cut mid-word. The full line stays in the tooltip.
 */
final class FitText {
  static final String SEPARATOR = " · ", ELLIPSIS = "…";

  private FitText() {}

  static String fit(List<String> parts, int width, ToIntFunction<String> measure) {
    String full = String.join(SEPARATOR, parts);
    if (measure.applyAsInt(full) <= width) return full;
    for (int kept = parts.size() - 1; kept >= 1; kept--) {
      String shorter = String.join(SEPARATOR, parts.subList(0, kept)) + " " + ELLIPSIS;
      if (measure.applyAsInt(shorter) <= width) return shorter;
    }
    String first = parts.isEmpty() ? "" : parts.getFirst();
    for (int space = first.lastIndexOf(' '); space > 0; space = first.lastIndexOf(' ', space - 1)) {
      String words = first.substring(0, space) + ELLIPSIS;
      if (measure.applyAsInt(words) <= width) return words;
    }
    for (int end = first.length() - 1; end > 0; end--) {
      String cut = first.substring(0, end) + ELLIPSIS;
      if (measure.applyAsInt(cut) <= width) return cut;
    }
    return "";
  }
}
