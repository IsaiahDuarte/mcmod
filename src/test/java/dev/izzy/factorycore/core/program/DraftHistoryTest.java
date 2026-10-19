package dev.izzy.factorycore.core.program;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DraftHistoryTest {
  @Test
  void undoRedoAndNewEditsPreserveTextAndDiscardAbandonedBranch() {
    var history = new DraftHistory("start");
    history.edit("item(\"minecraft:iron_ingot\")\n");
    history.edit("a template");
    assertEquals("item(\"minecraft:iron_ingot\")\n", history.undo());
    assertEquals("start", history.undo());
    assertEquals("start", history.undo());
    assertEquals("item(\"minecraft:iron_ingot\")\n", history.redo());
    history.edit("replacement");
    assertFalse(history.canRedo());
    assertEquals("replacement", history.redo());
    assertEquals("item(\"minecraft:iron_ingot\")\n", history.undo());
  }

  @Test
  void repeatedValuesDoNotAddStepsAndOldHistoryIsBounded() {
    var history = new DraftHistory("");
    history.edit("");
    assertFalse(history.canUndo());
    for (int index = 0; index < 100; index++) history.edit(Integer.toString(index));
    for (int index = 98; index >= 35; index--)
      assertEquals(Integer.toString(index), history.undo());
    assertFalse(history.canUndo());
    for (int index = 36; index <= 99; index++)
      assertEquals(Integer.toString(index), history.redo());
    assertFalse(history.canRedo());
    assertThrows(IllegalArgumentException.class, () -> history.edit("x".repeat(16_385)));
    assertEquals("98", history.undo(), "Rejected oversized edits preserve history");
  }
}
