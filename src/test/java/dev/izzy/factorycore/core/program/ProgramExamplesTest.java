package dev.izzy.factorycore.core.program;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Every program in the player docs compiles with the game's names (ADR 0068). */
class ProgramExamplesTest {
  private static final Pattern BLOCK = Pattern.compile("```text\\n(.*?)```", Pattern.DOTALL);

  private static List<String> programs(String page) throws IOException {
    String text;
    try (var stream = ProgramExamplesTest.class.getResourceAsStream("/" + page)) {
      if (stream == null) throw new IOException("Missing test resource " + page);
      text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    var found = new ArrayList<String>();
    var matcher = BLOCK.matcher(text);
    while (matcher.find()) found.add(matcher.group(1));
    return found;
  }

  private static void allCompile(String page, int atLeast) throws IOException {
    var programs = programs(page);
    assertTrue(programs.size() >= atLeast, page + " has " + programs.size() + " examples");
    for (var program : programs) {
      // The language page also shows an editor error message, which is not a program.
      if (program.startsWith("2:10 ")) continue;
      var result = FriendlyCompiler.compile(program, GeneratedVocabulary.get());
      assertTrue(result.ok(), page + ":\n" + program + "\n" + result.diagnostics());
      assertTrue(
          FriendlyCompiler.compile(FriendlyCompiler.canonical(result.program())).ok(),
          "Pinned form of:\n" + program);
    }
  }

  @Test
  void everyExampleOnTheExamplesPageCompiles() throws IOException {
    allCompile("PROGRAM_EXAMPLES.md", 14);
  }

  @Test
  void everyExampleInTheLanguageReferenceCompiles() throws IOException {
    allCompile("PROGRAM_LANGUAGE.md", 3);
  }
}
