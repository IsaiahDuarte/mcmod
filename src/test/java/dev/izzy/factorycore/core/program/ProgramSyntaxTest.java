package dev.izzy.factorycore.core.program;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ProgramSyntaxTest {
  @Test
  void colorsWordsTextNumbersAndCommentsWithoutChangingSource() {
    var source =
        "every 0.5 seconds:   # twice a second\n    say \"escaped \\\" # text\"\n    move 8 coal from \"my chest\" to storage";
    var spans = ProgramSyntax.spans(source);
    assertEquals(
        source,
        spans.stream()
            .map(span -> source.substring(span.start(), span.end()))
            .collect(Collectors.joining()));
    var expected =
        Map.of(
            "every", ProgramSyntax.Kind.KEYWORD,
            "0.5", ProgramSyntax.Kind.NUMBER,
            "coal", ProgramSyntax.Kind.TEXT,
            "# twice a second", ProgramSyntax.Kind.COMMENT,
            "\"escaped \\\" # text\"", ProgramSyntax.Kind.STRING,
            "\"my chest\"", ProgramSyntax.Kind.STRING);
    for (var entry : expected.entrySet())
      assertTrue(
          spans.stream()
              .anyMatch(
                  span ->
                      source.substring(span.start(), span.end()).equals(entry.getKey())
                          && span.kind() == entry.getValue()),
          entry.getKey());
    assertEquals(
        ProgramSyntax.Kind.STRING, ProgramSyntax.spans("\"unfinished\\").getFirst().kind());
  }

  @Test
  void everyWordOfTheLanguageIsHighlighted() {
    var source =
        "if furnace has less than 8 coal and not button is powered:\n"
            + "    turn on lamp\notherwise:\n    move 8 coal from storage to furnace top slots 0 to 2";
    var keywords = new HashSet<String>();
    for (var span : ProgramSyntax.spans(source))
      if (span.kind() == ProgramSyntax.Kind.KEYWORD)
        keywords.add(source.substring(span.start(), span.end()));
    for (var word :
        List.of(
            "if",
            "has",
            "less",
            "than",
            "and",
            "not",
            "is",
            "powered",
            "turn",
            "on",
            "otherwise",
            "move",
            "from",
            "storage",
            "to",
            "top",
            "slots")) assertTrue(keywords.contains(word), word);
    assertTrue(ProgramSyntax.KEYWORD_WORDS.contains("every"));
  }
}
