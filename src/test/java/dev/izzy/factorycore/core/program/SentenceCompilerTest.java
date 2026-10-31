package dev.izzy.factorycore.core.program;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** ADR 0068: sentence programs compile to the same IR as their brace spelling. */
class SentenceCompilerTest {
  static final Vocabulary WORDS =
      new Vocabulary(
          Set.of(
              "minecraft:coal",
              "minecraft:cobblestone",
              "minecraft:dirt",
              "minecraft:iron_ingot",
              "minecraft:gold_ingot",
              "minecraft:iron_nugget",
              "minecraft:iron_block",
              "minecraft:raw_iron",
              "minecraft:oak_planks",
              "create:brass_ingot",
              "othermod:brass_ingot",
              "othermod:steel_ingot"),
          Set.of("minecraft:water", "minecraft:lava"),
          Set.of("c:raw_materials", "c:ingots", "minecraft:logs"),
          Map.of("block of iron", "minecraft:iron_block"));

  private static final Path FIXTURES = fixtures();

  private static Path fixtures() {
    try {
      return Path.of(SentenceCompilerTest.class.getResource("/program/v3").toURI());
    } catch (java.net.URISyntaxException invalid) {
      throw new IllegalStateException(invalid);
    }
  }

  private static FriendlyCompiler.Result compile(String source) {
    return FriendlyCompiler.compile(source, WORDS);
  }

  private static ProgramIr.Program ok(String source) {
    var result = compile(source);
    assertTrue(result.ok(), () -> "Expected success: " + result.diagnostics());
    return result.program();
  }

  private static FriendlyCompiler.Diagnostic error(String source) {
    var result = compile(source);
    assertFalse(result.ok(), "Expected a diagnostic");
    return result.diagnostics().getFirst();
  }

  @Test
  void everyFixtureCompilesAndMatchesItsBraceSpelling() throws IOException {
    List<Path> fixtures;
    try (Stream<Path> files = Files.list(FIXTURES)) {
      fixtures = files.filter(file -> file.toString().endsWith(".fc")).sorted().toList();
    }
    assertTrue(fixtures.size() >= 13, "one fixture per form");
    for (var fixture : fixtures) {
      var source = Files.readString(fixture, StandardCharsets.UTF_8);
      assertTrue(SentenceCompiler.sentenceStyle(source), fixture + " is sentence style");
      var program = ok(source);
      var brace = Path.of(fixture.toString().replace(".fc", ".brace"));
      if (Files.exists(brace)) {
        var expected = FriendlyCompiler.compile(Files.readString(brace, StandardCharsets.UTF_8));
        assertTrue(expected.ok(), () -> brace + ": " + expected.diagnostics());
        assertEquals(expected.program(), program, fixture.getFileName().toString());
      }
    }
  }

  @Test
  void keepWithASideCountsThroughThatFace() {
    var keep = (ProgramIr.KeepAt) ok(read("keep-side.fc")).rules().getFirst().body().getFirst();
    assertEquals(
        new ProgramIr.MachineAt(new ProgramIr.Label("furnace"), ProgramIr.Side.NORTH, -1, -1),
        keep.place());
    assertEquals(8, keep.amount());
    assertEquals(
        new ResourceFilter(
            ResourceKind.ITEM,
            ResourceFilter.Mode.INCLUDE,
            List.of(new ResourceFilter.Registry("minecraft:coal"))),
        keep.filter());
  }

  @Test
  void braceProgramsAreNotSentenceStyle() {
    assertFalse(
        SentenceCompiler.sentenceStyle("# a: comment\nevery 20 ticks as \"a:b\" { log \"x: y\" }"));
    assertTrue(SentenceCompiler.sentenceStyle("\n# note\nevery second: say \"hi\""));
  }

  @Test
  void aMisspelledItemSuggestsTheClosestNames() {
    var error = error("every second:\n    move iron ingit from storage to furnace\n");
    assertEquals(2, error.line());
    assertEquals(10, error.column());
    assertEquals("I don't know an item called 'iron ingit'", error.message());
    assertEquals("Did you mean iron ingot?", error.hint());
  }

  @Test
  void aMissingFromSaysWhatWasExpectedWithAnExample() {
    var error = error("every second:\n    move coal storage to furnace\n");
    assertTrue(
        error.message().startsWith("I don't know an item called 'coal storage'"), error.message());
    error = error("every second:\n    move coal to furnace\n");
    assertEquals("I expected 'from' but found 'to'", error.message());
    assertEquals("move coal from storage to furnace", error.example());
  }

  @Test
  void indentationMistakesGiveOneErrorEach() {
    var error =
        error("every second:\n    move coal from storage to a\n  move coal from storage to b\n");
    assertEquals(3, error.line());
    assertEquals(
        "This line's indentation does not line up with the lines above it", error.message());
    assertEquals(
        1,
        compile("every second:\n    move coal from storage to a\n  x\n    y\n")
            .diagnostics()
            .size());

    error =
        error("every second:\n    move coal from storage to a\n\tmove coal from storage to b\n");
    assertEquals("Use only spaces or only tabs to indent.", error.hint());

    error = error("every second: move coal from storage to a\n    move coal from storage to b\n");
    assertEquals("This line has a sentence after ':' and indented lines below it", error.message());

    error = error("every second:\n    say \"a\"\n        say \"b\"\n");
    assertEquals(
        "This line is indented, but the line above it does not end with ':'", error.message());

    error = error("    every second:\n        say \"a\"\n");
    assertEquals("A rule starts at the left edge, with no spaces before it", error.message());

    error = error("every second:\n");
    assertEquals("Nothing comes after this ':'", error.message());
  }

  @Test
  void sentencesThatCannotMeanAnythingAreRefused() {
    assertEquals(
        "'keep' fills a machine, not storage",
        error("every second:\n    keep 8 coal in storage\n").message());
    assertEquals(
        "A move needs a machine on at least one side, not storage to storage",
        error("every second:\n    move coal from storage to storage\n").message());
    assertEquals(
        "'otherwise' must come right after an 'if' block",
        error("every second:\n    say \"a\"\n    otherwise:\n        say \"b\"\n").message());
    assertEquals(
        "A 'has' check counts the whole machine; it cannot name a side or slots",
        error("every second:\n    if furnace side has no coal: say \"a\"\n").message());
    assertTrue(
        error("every second:\n    fetch coal\n")
            .message()
            .startsWith("I don't know a sentence that starts with 'fetch'"));
    assertEquals(
        "That interval is not a whole number of ticks",
        error("every 0.33 seconds:\n    say \"a\"\n").message());
    assertEquals(
        "Rules run every half second (10 ticks) to every hour",
        error("every 2 ticks:\n    say \"a\"\n").message());
    assertEquals(
        "'craft' needs one item, not a group of items",
        error("every second:\n    craft 4 raw materials\n").message());
    assertEquals(
        "Storage has no sides or slots; only a machine does",
        error("every second:\n    keep 8 coal in storage top\n").message());
    // Labels sit in fixed places, so even language words work as labels.
    var move =
        (ProgramIr.Move)
            ok("every second:\n    move coal from storage to top top\n")
                .rules()
                .getFirst()
                .body()
                .getFirst();
    assertEquals(
        new ProgramIr.MachineAt(new ProgramIr.Label("top"), ProgramIr.Side.UP, -1, -1), move.to());
  }

  @Test
  void anAmbiguousNameAsksForTheMod() {
    var error = error("every second:\n    move brass ingot from storage to vault\n");
    assertEquals("More than one mod has 'brass ingot'", error.message());
    assertNotNull(error.hint());
    assertTrue(error.hint().contains("create:brass_ingot"), error.hint());
    // A name only one mod has needs no mod name.
    ok("every second:\n    move steel ingots from storage to vault\n");
  }

  @Test
  void eachBrokenLineIsReportedAndTheRestStillChecked() {
    var result =
        compile(
            "every second:\n    move ironn from storage to a\n    move coal from storage to b\n"
                + "    move dirtt from storage to c\n");
    assertEquals(List.of(2, 4), result.diagnostics().stream().map(d -> d.line()).toList());
  }

  @Test
  void withoutAVocabularyNamesAreTakenAsWritten() {
    var program = FriendlyCompiler.compile("every second: move raw iron from storage to furnace");
    assertTrue(program.ok(), () -> program.diagnostics().toString());
    var move = (ProgramIr.Move) program.program().rules().getFirst().body().getFirst();
    assertEquals(
        new ResourceFilter.Registry("minecraft:raw_iron"), move.filter().terms().getFirst());
  }

  private static String read(String name) {
    try {
      return Files.readString(FIXTURES.resolve(name), StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new IllegalStateException(failure);
    }
  }
}
