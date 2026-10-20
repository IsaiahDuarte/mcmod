package dev.izzy.factorycore.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class NamespacedIdTest {
  /** The grammar the hand-written validator replaced; kept here as the oracle. */
  private static final Pattern ORACLE = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

  @Test
  void acceptsAndRejectsKnownIdentifiers() {
    assertTrue(NamespacedId.isValid("minecraft:overworld"));
    assertTrue(NamespacedId.isValid("factorycore:items/cell_1.v2-x"));
    assertFalse(NamespacedId.isValid("minecraft:"));
    assertFalse(NamespacedId.isValid(":stone"));
    assertFalse(NamespacedId.isValid("Minecraft:stone"));
    assertFalse(NamespacedId.isValid("mine/craft:stone"));
    assertFalse(NamespacedId.isValid(null));
    assertFalse(NamespacedId.isValid("a:" + "b".repeat(255)));
  }

  @Test
  void matchesTheRegexOracleOnGeneratedStrings() {
    var random = new Random(0x1D5L);
    var alphabet = "abz09_.-/:A ~é";
    for (int run = 0; run < 200_000; run++) {
      var id = new StringBuilder();
      int length = random.nextInt(12);
      for (int i = 0; i < length; i++)
        id.append(alphabet.charAt(random.nextInt(alphabet.length())));
      var text = id.toString();
      assertEquals(ORACLE.matcher(text).matches(), NamespacedId.isValid(text), text);
    }
  }
}
