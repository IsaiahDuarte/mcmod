package dev.izzy.factorycore.platform.client.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Q99 (M112): a title that does not fit scrolls between its start and its end. */
class TitleShiftTest {
  @Test
  void aTitleThatFitsStaysPut() {
    assertEquals(-1, Panels.titleShift(100, 100, 0));
    assertEquals(-1, Panels.titleShift(40, 100, 12_345));
  }

  @Test
  void aLongTitleSweepsTheWholeOverflowAndBack() {
    int overflow = 60;
    int least = Integer.MAX_VALUE, most = Integer.MIN_VALUE;
    for (long millis = 0; millis < 60_000; millis += 50) {
      int shift = Panels.titleShift(100 + overflow, 100, millis);
      assertTrue(shift >= 0 && shift <= overflow, "shift " + shift + " at " + millis);
      least = Math.min(least, shift);
      most = Math.max(most, shift);
    }
    assertEquals(0, least, "The start is shown");
    assertEquals(overflow, most, "The end is shown");
    assertEquals(0, Panels.titleShift(160, 100, 0), "It starts at the beginning");
  }
}
