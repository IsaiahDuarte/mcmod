package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.core.resource.SearchQuery;
import java.util.ArrayList;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * Q61: colours a terminal search query by token (prefix terms, counts, operators) and marks a
 * malformed query red from its fault on, as an {@code EditBox} formatter.
 */
final class SearchHighlight {
  private String parsedText = "";
  private SearchQuery parsed = SearchQuery.parse("");

  SearchQuery query(String text) {
    if (!text.equals(parsedText)) {
      parsedText = text;
      parsed = SearchQuery.parse(text);
    }
    return parsed;
  }

  static int colour(SearchQuery.TokenKind kind) {
    return switch (kind) {
      case MOD -> 0xC890FF;
      case TAG -> 0xFFD060;
      case TOOLTIP -> 0x70D8FF;
      case ID -> 0xFFA850;
      case COUNT -> 0x88F088;
      case NOT, OR, OPEN, CLOSE -> 0xA8A8A8;
      case ERROR -> 0xFF5555;
      default -> 0xE0E0E0;
    };
  }

  /** The visible part {@code visible}, starting at {@code offset} in {@code full}, coloured. */
  FormattedCharSequence format(String full, String visible, int offset) {
    var query = query(full);
    int[] colours = new int[visible.length()];
    java.util.Arrays.fill(colours, 0xE0E0E0);
    boolean faulty = query.error() != null;
    for (var token : query.tokens())
      for (int at = Math.max(token.start(), offset);
          at < Math.min(token.end(), offset + visible.length());
          at++) colours[at - offset] = colour(token.kind());
    // Text after the fault that did not become a token (e.g. past an unclosed quote) is red too.
    if (faulty)
      for (int at = Math.max(query.errorAt(), offset); at < offset + visible.length(); at++)
        colours[at - offset] = colour(SearchQuery.TokenKind.ERROR);
    var parts = new ArrayList<FormattedCharSequence>();
    int from = 0;
    for (int at = 1; at <= visible.length(); at++)
      if (at == visible.length() || colours[at] != colours[from]) {
        var style = Style.EMPTY.withColor(colours[from]);
        if (colours[from] == colour(SearchQuery.TokenKind.ERROR))
          style = style.withUnderlined(true);
        parts.add(FormattedCharSequence.forward(visible.substring(from, at), style));
        from = at;
      }
    return FormattedCharSequence.composite(parts);
  }
}
