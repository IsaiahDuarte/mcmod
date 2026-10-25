package dev.izzy.factorycore.platform.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SlotSelectionTest {
  @Test
  void onlySmallContainersUseThePicker() {
    assertFalse(SlotSelection.pickable(0));
    assertTrue(SlotSelection.pickable(1));
    assertTrue(SlotSelection.pickable(64));
    assertFalse(SlotSelection.pickable(65));
  }

  @Test
  void clickSelectsOneAndShiftExtendsEitherWay() {
    assertArrayEquals(new int[] {5, 1}, SlotSelection.click(2, 5, false));
    assertArrayEquals(new int[] {2, 4}, SlotSelection.click(2, 5, true));
    assertArrayEquals(new int[] {0, 3}, SlotSelection.click(2, 0, true));
    assertArrayEquals(new int[] {2, 1}, SlotSelection.click(2, 2, true));
    assertTrue(SlotSelection.selected(2, 4, 5));
    assertFalse(SlotSelection.selected(2, 4, 6));
    assertFalse(SlotSelection.selected(2, 4, 1));
  }

  @Test
  void cellsMapToSlotsAndWrapAfterThirtyTwo() {
    // A 27-slot chest: one row of 27 cells in a 270×16 strip.
    assertEquals(0, SlotSelection.at(27, 270, 16, 0, 0));
    assertEquals(26, SlotSelection.at(27, 270, 16, 269, 15));
    assertEquals(-1, SlotSelection.at(27, 270, 16, 270, 0));
    // 54 slots: two rows of 32 cells; the second row's last ten cells are past the end.
    assertEquals(2, SlotSelection.rows(54));
    assertEquals(32, SlotSelection.at(54, 320, 16, 0, 8));
    assertEquals(53, SlotSelection.at(54, 320, 16, 215, 8));
    assertEquals(-1, SlotSelection.at(54, 320, 16, 225, 8));
    assertEquals(-1, SlotSelection.at(54, 320, 16, -1, 0));
  }
}
