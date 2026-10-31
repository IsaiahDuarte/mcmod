package dev.izzy.factorycore.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class ExportOrderTest {
  @Test
  void eachOrderPicksTheNextPassStart() {
    var random = new Random(7);
    assertEquals(0, ExportOrder.IN_ORDER.after(2, 3, random));
    assertEquals(0, ExportOrder.ROUND_ROBIN.after(2, 3, random), "Wraps after the last slot");
    assertEquals(2, ExportOrder.ROUND_ROBIN.after(1, 3, random));
    for (int i = 0; i < 50; i++) {
      int start = ExportOrder.RANDOM.after(0, 3, random);
      assertTrue(start >= 0 && start < 3);
    }
    assertEquals(0, ExportOrder.ROUND_ROBIN.after(4, 0, random), "No slots starts at zero");
  }

  @Test
  void distanceIsCyclicAfterTheStart() {
    assertEquals(0, ExportOrder.distance(2, 2, 4));
    assertEquals(1, ExportOrder.distance(3, 2, 4));
    assertEquals(3, ExportOrder.distance(1, 2, 4));
    assertEquals(ExportOrder.IN_ORDER, ExportOrder.RANDOM.next());
  }
}
