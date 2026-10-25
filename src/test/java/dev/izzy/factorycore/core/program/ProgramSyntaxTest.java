package dev.izzy.factorycore.core.program;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ProgramSyntaxTest {
  @Test
  void colorsKeywordsCallsEscapedStringsNumbersAndCommentsWithoutChangingSource() {
    var source = "every 10 ticks { log \"escaped \\\" # string\" # comment\nmove item(\"iron\") }";
    var spans = ProgramSyntax.spans(source);
    assertEquals(
        source,
        spans.stream()
            .map(span -> source.substring(span.start(), span.end()))
            .collect(Collectors.joining()));
    var expected =
        Map.of(
            "every",
            ProgramSyntax.Kind.KEYWORD,
            "10",
            ProgramSyntax.Kind.NUMBER,
            "item",
            ProgramSyntax.Kind.CALL,
            "# comment",
            ProgramSyntax.Kind.COMMENT,
            "\"escaped \\\" # string\"",
            ProgramSyntax.Kind.STRING);
    for (var entry : expected.entrySet())
      assertTrue(
          spans.stream()
              .anyMatch(
                  span ->
                      source.substring(span.start(), span.end()).equals(entry.getKey())
                          && span.kind() == entry.getValue()));
    assertEquals(
        ProgramSyntax.Kind.STRING, ProgramSyntax.spans("\"unfinished\\").getFirst().kind());
  }

  @Test
  void cursorCompletionReplacesWholeTokenAndOnlyTheInsideOfQuotedLiterals() {
    var source = "move item(\"minecraft:iron_ingot\") from machines(\"smelter input\") to storage";
    int cursor = source.indexOf("iron_") + 2;
    var item = ProgramCompletion.at(source, cursor);
    assertEquals("item", item.call());
    assertTrue(item.quoted());
    assertEquals("minecraft:iron_ingot", source.substring(item.start(), item.end()));
    var label = ProgramCompletion.at(source, source.indexOf("input") + 2);
    assertEquals("machines", label.call());
    assertEquals("smelter input", source.substring(label.start(), label.end()));
    assertEquals("smelter in", label.prefix());
    var keyword = ProgramCompletion.at(source, source.lastIndexOf("storage") + 3);
    assertFalse(keyword.quoted());
    assertEquals("storage", source.substring(keyword.start(), keyword.end()));
    assertFalse(ProgramCompletion.at("item(\"iron\") ", 13).quoted());
    assertEquals("minecraft:wat", ProgramCompletion.at("fluid(\"minecraft:wat", 20).prefix());
    assertThrows(IllegalArgumentException.class, () -> ProgramCompletion.at("", 1));
  }

  @org.junit.jupiter.api.Test
  void languageV2KeywordsAreHighlighted() {
    var source =
        "if f has < 8 of item(\"coal\") and not redstone(m) { emit redstone 1 on machines(\"a\") } else { move energy from f side up slots 0..1 to storage }";
    var keywords = new java.util.HashSet<String>();
    for (var span : ProgramSyntax.spans(source))
      if (span.kind() == ProgramSyntax.Kind.KEYWORD)
        keywords.add(source.substring(span.start(), span.end()));
    for (var word :
        java.util.List.of(
            "if", "has", "and", "not", "redstone", "emit", "on", "else", "side", "up", "slots"))
      org.junit.jupiter.api.Assertions.assertTrue(keywords.contains(word), word);
  }
}
