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
      return Path.of(SentenceCompilerTest.class.getResource("/program/sentences").toURI());
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

  /**
   * One fixture per form, pinned to its IR in {@code program/sentences-ir.txt}. The pin was first
   * written while the brace language still existed, from programs whose IR equalled their brace
   * spelling. Regenerate it only for a decided language change, by setting {@code
   * FACTORYCORE_UPDATE_PIN} to that file's absolute path.
   */
  @Test
  void everyFixtureCompilesToItsPinnedIr() throws IOException {
    List<Path> fixtures;
    try (Stream<Path> files = Files.list(FIXTURES)) {
      fixtures = files.filter(file -> file.toString().endsWith(".fc")).sorted().toList();
    }
    assertTrue(fixtures.size() >= 14, "one fixture per form");
    var lines = new StringBuilder();
    for (var fixture : fixtures)
      lines
          .append(fixture.getFileName())
          .append('\t')
          .append(ok(Files.readString(fixture, StandardCharsets.UTF_8)))
          .append('\n');
    var update = System.getenv("FACTORYCORE_UPDATE_PIN");
    if (update != null && !update.isEmpty()) Files.writeString(Path.of(update), lines);
    var pin = FIXTURES.resolveSibling("sentences-ir.txt");
    assertTrue(Files.isRegularFile(pin), "Pinned IR exists");
    assertEquals(Files.readString(pin, StandardCharsets.UTF_8), lines.toString(), "IR changed");
  }

  @Test
  void limitsAndBracesGiveClearErrors() {
    assertEquals("Programs don't use braces", error("every 20 ticks { say \"x\" }").message());
    var rules = new StringBuilder();
    for (int i = 0; i <= ProgramIr.MAX_RULES; i++)
      rules.append("every second called r").append(i).append(": say \"x\"\n");
    assertEquals("A program has at most 64 rules", error(rules.toString()).message());
    assertEquals(
        "Another rule is already called \"a\"",
        error("every second called a: say \"x\"\nevery second called a: say \"y\"\n").message());
    var deep = new StringBuilder("every second:\n");
    for (int depth = 1; depth <= FriendlyCompiler.MAX_DEPTH; depth++)
      deep.append("    ".repeat(depth)).append("if storage has no coal:\n");
    deep.append("    ".repeat(FriendlyCompiler.MAX_DEPTH + 1)).append("say \"x\"\n");
    assertEquals("Blocks nest at most 8 deep", error(deep.toString()).message());
    assertEquals(
        "The amount must be from 1 to 4,096",
        error("every second: move 4097 coal from storage to a").message());
    assertEquals(
        "Machine labels are 1–64 characters",
        error("every second: move coal from storage to \"" + "x".repeat(65) + "\"").message());
    assertEquals(
        "Text is longer than 128 characters",
        error("every second: say \"" + "x".repeat(129) + "\"").message());
    assertEquals(
        "Program is longer than 16,384 characters",
        error("#".repeat(FriendlyCompiler.MAX_SOURCE + 1)).message());
    var many = new StringBuilder("every second:\n");
    for (int i = 0; i < 40; i++) many.append("    fetch coal\n");
    assertEquals(FriendlyCompiler.MAX_DIAGNOSTICS, compile(many.toString()).diagnostics().size());
    var statements = new StringBuilder("every second:\n");
    for (int i = 0; i <= FriendlyCompiler.MAX_STATEMENTS; i++) statements.append("    say \"x\"\n");
    assertEquals("A program has at most 512 statements", error(statements.toString()).message());
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
  void aFluidWrittenAsAnItemSaysToWriteFluid() {
    var error = error("every second:\n    if tank has less than 4000 water: say \"low\"\n");
    assertEquals("'water' is a fluid", error.message());
    assertEquals("Write fluid water.", error.hint());
    ok("every second:\n    if tank has less than 4000 fluid water: say \"low\"\n");
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
