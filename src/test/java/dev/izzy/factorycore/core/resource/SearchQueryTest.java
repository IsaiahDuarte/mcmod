package dev.izzy.factorycore.core.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchQueryTest {
  private record Item(String name, String id, List<String> tags, List<String> tooltip)
      implements SearchQuery.Subject {}

  private static final Item OAK_LOG =
      new Item("Oak Log", "minecraft:oak_log", List.of("minecraft:logs"), List.of("Oak Log"));
  private static final Item IRON =
      new Item(
          "Iron Ingot",
          "minecraft:iron_ingot",
          List.of("c:ingots/iron", "c:ingots"),
          List.of("Iron Ingot", "Smelted from raw iron"));
  private static final Item WIDGET =
      new Item("Copper Widget", "othermod:widget", List.of(), List.of("Copper Widget"));

  private static boolean hit(String query, Item item) {
    return SearchQuery.parse(query).matches(item, BigInteger.ONE);
  }

  private static boolean hit(String query, Item item, long total) {
    return SearchQuery.parse(query).matches(item, BigInteger.valueOf(total));
  }

  @Test
  void wordsMatchNameOrIdAndAllMustMatch() {
    assertTrue(hit("oak", OAK_LOG));
    assertTrue(hit("OAK_LOG", OAK_LOG), "IDs match, case-insensitively");
    assertTrue(hit("oak log", OAK_LOG), "Separate words are ANDed");
    assertFalse(hit("oak iron", OAK_LOG));
    assertTrue(hit("", WIDGET), "A blank query matches everything");
    assertTrue(SearchQuery.parse("  ").blank());
  }

  @Test
  void prefixesMatchOnlyTheirPart() {
    assertTrue(hit("@minecraft", OAK_LOG));
    assertFalse(hit("@minecraft", WIDGET));
    assertTrue(hit("#ingots", IRON));
    assertFalse(hit("#ingots", OAK_LOG));
    assertTrue(hit("$smelted", IRON), "Tooltip lines are searched");
    assertFalse(hit("$smelted", OAK_LOG));
    assertTrue(hit("*widget", WIDGET));
    assertFalse(hit("*copper", WIDGET), "*id ignores the name");
    assertTrue(hit("copper", WIDGET), "A plain word matches the name");
  }

  @Test
  void quotedTextKeepsSpaces() {
    assertTrue(hit("\"iron ingot\"", IRON));
    assertFalse(hit("\"ingot iron\"", IRON), "A phrase is one substring");
    assertTrue(hit("$\"raw iron\"", IRON), "A prefix takes a quoted value");
    assertEquals(
        SearchQuery.TokenKind.QUOTED, SearchQuery.parse("\"a b\"").tokens().getFirst().kind());
  }

  @Test
  void orNotAndParenthesesWithPrecedence() {
    assertTrue(hit("oak | iron", IRON));
    assertTrue(hit("oak | iron", OAK_LOG));
    assertFalse(hit("oak | iron", WIDGET));
    assertTrue(hit("!oak", IRON));
    assertFalse(hit("!oak", OAK_LOG));
    assertTrue(hit("!!oak", OAK_LOG), "NOT nests");
    // AND binds tighter than OR: "copper | oak log" is copper OR (oak AND log).
    assertTrue(hit("copper | oak log", OAK_LOG));
    assertTrue(hit("copper | oak log", WIDGET));
    assertFalse(hit("(copper | oak) iron", IRON), "Parentheses group the OR first");
    assertTrue(hit("(copper | iron) ingot", IRON));
    assertFalse(hit("@minecraft !(#logs | $smelted)", IRON));
    assertFalse(hit("@minecraft !(#logs | $smelted)", OAK_LOG));
  }

  @Test
  void countFiltersCompareTheTotalAndAreUnknownWithoutIt() {
    assertTrue(hit(">64", IRON, 65));
    assertFalse(hit(">64", IRON, 64));
    assertTrue(hit(">=64", IRON, 64));
    assertTrue(hit("<10", IRON, 9));
    assertTrue(hit("<=10", IRON, 10));
    assertFalse(hit("<=10", IRON, 11));
    assertTrue(hit("=0", IRON, 0));
    assertFalse(hit("=0", IRON, 1));
    var query = SearchQuery.parse("iron >64");
    assertTrue(query.counts());
    assertTrue(query.mayMatch(IRON), "Without a total a count may still match");
    assertFalse(query.mayMatch(OAK_LOG), "A failing word rules it out without a total");
    assertTrue(SearchQuery.parse("!>5").mayMatch(IRON), "NOT of unknown stays unknown");
    assertTrue(SearchQuery.parse("oak | >5").mayMatch(IRON), "OR with unknown stays unknown");
    assertFalse(SearchQuery.parse("oak iron").counts());
  }

  @Test
  void malformedQueriesAreMarkedAndMatchNothing() {
    for (var bad :
        List.of(
            "(oak", "oak)", "oak |", "| oak", "()", "!", "\"oak", ">", ">x", ">5x", "@",
            "oak !)")) {
      var query = SearchQuery.parse(bad);
      assertTrue(query.error() != null, bad + " is malformed");
      assertTrue(query.errorAt() >= 0 && query.errorAt() <= bad.length(), bad);
      assertFalse(query.mayMatch(OAK_LOG), bad + " matches nothing");
      assertFalse(query.matches(OAK_LOG, BigInteger.ONE));
    }
    var open = SearchQuery.parse("iron (oak");
    assertEquals("missing_close", open.error());
    assertEquals(5, open.errorAt(), "Marked at the unclosed parenthesis");
    assertEquals(SearchQuery.TokenKind.TEXT, open.tokens().getFirst().kind());
    assertEquals(SearchQuery.TokenKind.ERROR, open.tokens().get(1).kind());
    assertEquals("unclosed_quote", SearchQuery.parse("\"oak").error());
    assertEquals("bad_count", SearchQuery.parse(">x").error());
    assertNull(SearchQuery.parse("oak").error());
  }

  @Test
  void boundsCapLengthTermsAndNesting() {
    assertEquals("too_long", SearchQuery.parse("a".repeat(SearchQuery.MAX_LENGTH + 1)).error());
    assertNull(SearchQuery.parse("a".repeat(SearchQuery.MAX_LENGTH)).error());
    assertNull(SearchQuery.parse("a ".repeat(SearchQuery.MAX_TERMS)).error());
    assertEquals(
        "too_many_terms", SearchQuery.parse("a ".repeat(SearchQuery.MAX_TERMS + 1)).error());
    String nested = "(".repeat(SearchQuery.MAX_DEPTH) + "a" + ")".repeat(SearchQuery.MAX_DEPTH);
    assertNull(SearchQuery.parse(nested).error());
    String deeper =
        "(".repeat(SearchQuery.MAX_DEPTH + 1) + "a" + ")".repeat(SearchQuery.MAX_DEPTH + 1);
    assertEquals("too_deep", SearchQuery.parse(deeper).error());
    assertEquals("bad_count", SearchQuery.parse(">" + "9".repeat(31)).error());
  }

  @Test
  void tokensCoverTheTextForColouring() {
    var tokens = SearchQuery.parse("@mc #logs | !$x *id >=3 \"a b\"").tokens();
    assertEquals(
        List.of(
            SearchQuery.TokenKind.MOD,
            SearchQuery.TokenKind.TAG,
            SearchQuery.TokenKind.OR,
            SearchQuery.TokenKind.NOT,
            SearchQuery.TokenKind.TOOLTIP,
            SearchQuery.TokenKind.ID,
            SearchQuery.TokenKind.COUNT,
            SearchQuery.TokenKind.QUOTED),
        tokens.stream().map(SearchQuery.Token::kind).toList());
    assertEquals(0, tokens.getFirst().start());
    assertEquals(3, tokens.getFirst().end());
  }
}
