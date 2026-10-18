package dev.izzy.factorycore.core.program;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ResourceExpressionsTest {
  @Test
  void insertedExpressionsCompile() {
    var source =
        "every 20 ticks {\n  move "
            + ResourceExpressions.item("minecraft:iron_ingot")
            + " from machines(\"out\") to storage limit 64\n}\n";
    var result = FriendlyCompiler.compile(source);
    assertTrue(result.diagnostics().isEmpty(), () -> "Diagnostics: " + result.diagnostics());
  }

  @Test
  void quotesAndBackslashesAreEscaped() {
    assertEquals("item(\"a\\\"b\\\\c\")", ResourceExpressions.item("a\"b\\c"));
    assertEquals("fluid(\"minecraft:water\")", ResourceExpressions.fluid("minecraft:water"));
    assertThrows(IllegalArgumentException.class, () -> ResourceExpressions.literal("bad\nline"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ResourceExpressions.literal("x".repeat(FriendlyCompiler.MAX_STRING + 1)));
  }
}
