package dev.izzy.factorycore.core.program;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** ADR 0068: every program prints to sentences that compile back to equal IR. */
class ProgramPrinterTest {
  private static List<Path> fixtures(String folder, String suffix) throws IOException {
    try (Stream<Path> files =
        Files.list(Path.of(ProgramPrinterTest.class.getResource(folder).toURI()))) {
      return files.filter(file -> file.toString().endsWith(suffix)).sorted().toList();
    } catch (URISyntaxException invalid) {
      throw new IllegalStateException(invalid);
    }
  }

  private static void roundTrip(String name, String source, Vocabulary vocabulary) {
    var compiled = FriendlyCompiler.compile(source, vocabulary);
    assertTrue(compiled.ok(), () -> name + ": " + compiled.diagnostics());
    var printed = ProgramPrinter.print(compiled.program(), vocabulary);
    assertTrue(SentenceCompiler.sentenceStyle(printed), name);
    var again = FriendlyCompiler.compile(printed, vocabulary);
    assertTrue(again.ok(), () -> name + " printed as\n" + printed + "\n" + again.diagnostics());
    assertEquals(compiled.program(), again.program(), name + " printed as\n" + printed);
  }

  @Test
  void everyFixtureAndTemplatePrintsToEqualIr() throws IOException {
    var sources = new ArrayList<Path>();
    sources.addAll(fixtures("/program/v1", ".fc"));
    sources.addAll(fixtures("/program/v2", ".fc"));
    sources.addAll(fixtures("/program/v3", ".fc"));
    sources.addAll(fixtures("/program/v3", ".brace"));
    for (var source : sources) {
      var text = Files.readString(source, StandardCharsets.UTF_8);
      // v3 fixtures use the test vocabulary's names; the others name items it does not know.
      var vocabulary =
          source.toString().contains("v3") ? SentenceCompilerTest.WORDS : Vocabulary.NONE;
      roundTrip(source.getFileName().toString(), text, vocabulary);
    }
    for (var template : ProgramTemplates.ALL)
      roundTrip(template.name(), template.source(), Vocabulary.NONE);
  }

  @Test
  void namesPrintAsWordsWhenTheyReadBack() {
    var program =
        FriendlyCompiler.compile(
                """
                every 30 ticks as "fuel" {
                  move tag("c:raw_materials") except item("create:brass_ingot") from storage to machines("my furnace") side up slots 0..2 limit 8
                  if not redstone(machines("top")) or stock(item("iron_block")) >= 3 { emit redstone 15 on machines("lamp") }
                }
                """)
            .program();
    assertEquals(
        """
        every 1.5 seconds called fuel:
            move 8 raw materials except create:brass_ingot from storage to "my furnace" top slots 0 to 2
            if "top" is unpowered or storage has at least 3 iron block:
                turn on lamp
        """,
        ProgramPrinter.print(program, SentenceCompilerTest.WORDS));
    assertEquals(
        "every 1.5 seconds called fuel:\n"
            + "    move 8 tag c:raw_materials except create:brass_ingot from storage to \"my furnace\" top slots 0 to 2\n"
            + "    if \"top\" is unpowered or storage has at least 3 iron block:\n"
            + "        turn on lamp\n",
        ProgramPrinter.print(program, Vocabulary.NONE));
  }

  @Test
  void anEmptyBlockHasNoSentenceSpelling() {
    var program = FriendlyCompiler.compile("every 20 ticks { }").program();
    assertThrows(
        IllegalArgumentException.class, () -> ProgramPrinter.print(program, Vocabulary.NONE));
  }
}
