package dev.izzy.factorycore.core.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GridTransferTest {
  @Test
  void prefersVisibleOptionsAndPadsEmptyCells() {
    var planks =
        List.of("minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks");
    var chosen =
        GridTransfer.choose(
            List.of(planks, List.of(), planks, List.of("minecraft:stick")),
            Set.of("minecraft:spruce_planks"));
    assertEquals(9, chosen.size());
    assertEquals("minecraft:spruce_planks", chosen.get(0));
    assertEquals("", chosen.get(1));
    assertEquals("minecraft:spruce_planks", chosen.get(2));
    assertEquals("minecraft:stick", chosen.get(3), "Unavailable options fall back to the first");
    assertEquals("", chosen.get(8));
  }

  /** Q112: the option the player has most of wins; a tie keeps the listed order. */
  @Test
  void prefersThePlentifulOption() {
    var planks =
        List.of("minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks");
    var chosen =
        GridTransfer.choose(
            List.of(planks, List.of("minecraft:oak_log", "minecraft:spruce_log")),
            java.util.Map.of(
                "minecraft:oak_planks", 2L,
                "minecraft:birch_planks", 300L,
                "minecraft:oak_log", 5L,
                "minecraft:spruce_log", 5L));
    assertEquals("minecraft:birch_planks", chosen.get(0));
    assertEquals("minecraft:oak_log", chosen.get(1), "A tie keeps the first listed");
  }

  @Test
  void rejectsMoreThanNineCells() {
    assertThrows(
        IllegalArgumentException.class,
        () -> GridTransfer.choose(Collections.nCopies(10, List.of("minecraft:stone")), Set.of()));
  }
}
