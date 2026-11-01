package dev.izzy.factorycore.platform.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Q135: a tooltip box is never placed past any screen edge. */
class TooltipsTest {
  @Test
  void opensRightOfTheCursorWhenItFits() {
    assertArrayEquals(new int[] {22, 8}, Tooltips.place(500, 300, 10, 20, 100, 30));
  }

  @Test
  void flipsLeftNearTheRightEdge() {
    assertArrayEquals(new int[] {288, 8}, Tooltips.place(500, 300, 400, 20, 100, 30));
  }

  @Test
  void neverCrossesTheLeftEdgeEvenWhenTooWideForEitherSide() {
    // The playthrough case: cursor mid-screen, box wider than the space on either side.
    for (int mouseX = 0; mouseX <= 500; mouseX += 25)
      for (int mouseY = 0; mouseY <= 300; mouseY += 25) {
        int[] at = Tooltips.place(500, 300, mouseX, mouseY, 300, 60);
        assertTrue(
            at[0] >= 4 && at[0] + 300 <= 496 && at[1] >= 4 && at[1] + 60 <= 296,
            mouseX + "," + mouseY + " -> " + at[0] + "," + at[1]);
      }
  }
}
