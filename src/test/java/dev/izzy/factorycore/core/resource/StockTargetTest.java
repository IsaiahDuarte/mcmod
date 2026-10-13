package dev.izzy.factorycore.core.resource;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;

class StockTargetTest {
  @Test
  void residentAndKnownInboundPreventOverRequestingWithoutConsumingHeldStock() {
    var goal = new StockTarget(ResourceKind.ITEM, 80, 64);
    assertEquals(64, goal.missing(0, 0));
    assertEquals(25, goal.missing(35, 20));
    assertEquals(0, goal.missing(35, 45));
    assertEquals(0, goal.missing(81, 0));
    // Resident includes physical items held for another job; this planner never subtracts holds.
    assertEquals(0, new StockTarget(ResourceKind.ITEM, 20, 16).missing(20, 0));
    assertEquals(0, new StockTarget(ResourceKind.ITEM, 0, 1).missing(0, 0));
  }

  @Test
  void fullLongRangeAndResourceQuantaRejectInvalidInputsWithoutOverflow() {
    var goal = new StockTarget(ResourceKind.ITEM, Long.MAX_VALUE, 128);
    assertEquals(0, goal.missing(Long.MAX_VALUE - 1, Long.MAX_VALUE));
    assertEquals(1, goal.missing(Long.MAX_VALUE - 1, 0));
    assertEquals(128, goal.missing(0, 0));
    assertEquals(1000, new StockTarget(ResourceKind.FLUID, 4000, 1000).missing(0, 0));
    assertEquals(1000, new StockTarget(ResourceKind.ENERGY, Long.MAX_VALUE, 1000).missing(0, 0));
    assertThrows(IllegalArgumentException.class, () -> new StockTarget(ResourceKind.ITEM, -1, 1));
    assertThrows(IllegalArgumentException.class, () -> new StockTarget(ResourceKind.ITEM, 1, 129));
    assertThrows(
        IllegalArgumentException.class, () -> new StockTarget(ResourceKind.FLUID, 1, 1001));
    assertThrows(IllegalArgumentException.class, () -> new StockTarget(ResourceKind.ITEM, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> goal.missing(-1, 0));
    assertThrows(IllegalArgumentException.class, () -> goal.missing(0, -1));
  }

  @Test
  void generatedPlansAgreeWithUnboundedArithmeticAcrossOverflowCombinations() {
    var random = new Random(0x570C221);
    for (int step = 0; step < 10000; step++) {
      long target = random.nextLong() & Long.MAX_VALUE,
          resident = random.nextLong() & Long.MAX_VALUE,
          inbound = random.nextLong() & Long.MAX_VALUE;
      long quantum = 1 + random.nextInt(128);
      var expected =
          BigInteger.valueOf(target)
              .subtract(BigInteger.valueOf(resident))
              .subtract(BigInteger.valueOf(inbound))
              .max(BigInteger.ZERO)
              .min(BigInteger.valueOf(quantum));
      assertEquals(
          expected.longValueExact(),
          new StockTarget(ResourceKind.ITEM, target, quantum).missing(resident, inbound));
    }
  }
}
