package dev.izzy.factorycore.core.crafting;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProcessingTransferTest {
  /** Q5: a recipe with five outputs keeps the first three and reports the five. */
  @Test
  void moreThanThreeOutputsKeepTheFirstThreeAndSaySo() {
    var outputs = ProcessingTransfer.outputs(List.of("a", "b", "c", "d", "e"));
    assertEquals(List.of("a", "b", "c"), outputs.kept());
    assertEquals(5, outputs.total());
    assertTrue(outputs.truncated());
    var few = ProcessingTransfer.outputs(List.of("a", "b"));
    assertEquals(List.of("a", "b"), few.kept());
    assertFalse(few.truncated());
  }

  @Test
  void amountsBecomeGridUnits() {
    assertEquals(1, ProcessingTransfer.itemUnits(0));
    assertEquals(8, ProcessingTransfer.itemUnits(8));
    assertEquals(64, ProcessingTransfer.itemUnits(200));
    assertEquals(4, ProcessingTransfer.fluidUnits(1_000));
    assertEquals(2, ProcessingTransfer.fluidUnits(300), "300 mB rounds up to two 250 mB units");
    assertEquals(1, ProcessingTransfer.fluidUnits(1));
    assertEquals(64, ProcessingTransfer.fluidUnits(100_000));
  }
}
