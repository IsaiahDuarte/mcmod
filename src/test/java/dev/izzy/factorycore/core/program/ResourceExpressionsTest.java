package dev.izzy.factorycore.core.program;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ResourceExpressionsTest {
  @Test
  void insertedExpressionsCompile() {
    var source =
        "every second:\n    move "
            + ResourceExpressions.item("minecraft:iron_ingot")
            + " except "
            + ResourceExpressions.item("factorycore:blank_pattern")
            + " from "
            + ResourceExpressions.label("smelter out")
            + " to storage\n    move "
            + ResourceExpressions.fluid("minecraft:water")
            + " from "
            + ResourceExpressions.label("pump")
            + " to storage\n";
    assertEquals(
        "every second:\n    move iron ingot except factorycore:blank_pattern from \"smelter out\" to"
            + " storage\n    move fluid water from pump to storage\n",
        source);
    var result = FriendlyCompiler.compile(source, GeneratedVocabulary.get());
    assertTrue(result.diagnostics().isEmpty(), () -> "Diagnostics: " + result.diagnostics());
  }

  @Test
  void quotesAndBackslashesAreEscaped() {
    assertEquals("\"a\\\"b\\\\c\"", ResourceExpressions.literal("a\"b\\c"));
    assertThrows(IllegalArgumentException.class, () -> ResourceExpressions.literal("bad\nline"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ResourceExpressions.literal("x".repeat(FriendlyCompiler.MAX_STRING + 1)));
  }
}
