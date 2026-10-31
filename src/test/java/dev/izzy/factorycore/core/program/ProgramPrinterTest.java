package dev.izzy.factorycore.core.program;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    var again = FriendlyCompiler.compile(printed, vocabulary);
    assertTrue(again.ok(), () -> name + " printed as\n" + printed + "\n" + again.diagnostics());
    assertEquals(compiled.program(), again.program(), name + " printed as\n" + printed);
  }

  @Test
  void everyFixtureAndTemplatePrintsToEqualIr() throws IOException {
    for (var source : fixtures("/program/sentences", ".fc"))
      roundTrip(
          source.getFileName().toString(),
          Files.readString(source, StandardCharsets.UTF_8),
          SentenceCompilerTest.WORDS);
    for (var template : ProgramTemplates.ALL)
      roundTrip(template.name(), template.source(), GeneratedVocabulary.get());
  }

  @Test
  void namesPrintAsWordsWhenTheyReadBack() {
    var program =
        FriendlyCompiler.compile(
                """
                every 1.5 seconds called fuel:
                    move 8 tag c:raw_materials except create:brass_ingot from storage to "my furnace" top slots 0 to 2
                    if not top is powered or storage has at least 3 iron_block:
                        turn on lamp
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
    var program = new ProgramIr.Program(List.of(new ProgramIr.Rule("empty", 20, List.of())));
    assertThrows(
        IllegalArgumentException.class, () -> ProgramPrinter.print(program, Vocabulary.NONE));
  }
}
