package dev.izzy.factorycore.platform.power;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RateLimitedEnergyInputTest {
  private long tick;
  private boolean accepting = true;
  private int dirty;

  private RateLimitedEnergyInput input(long capacity, long rate) {
    return new RateLimitedEnergyInput(capacity, rate, () -> tick, () -> accepting, () -> dirty++);
  }

  @Test
  void acceptsUpToTheRateEachTickAndUpToCapacity() {
    var input = input(2_500, 1_000);
    assertEquals(600, input.receiveEnergy(600, false));
    assertEquals(400, input.receiveEnergy(600, false), "The rest of this tick's 1,000");
    assertEquals(0, input.receiveEnergy(600, false), "Nothing more this tick");
    tick++;
    assertEquals(1_000, input.receiveEnergy(5_000, true), "Simulation reports a new tick's rate");
    assertEquals(1_000, input.receiveEnergy(5_000, false));
    tick++;
    assertEquals(500, input.receiveEnergy(5_000, false), "Capacity bounds the last fill");
    assertEquals(2_500, input.energy());
    assertEquals(2_500, input.getEnergyStored());
    assertEquals(2_500, input.getMaxEnergyStored());
    assertEquals(4, dirty, "Each of the four fills marks the owner dirty; simulation does not");
  }

  @Test
  void refusedInputOffServerAndNonPositiveAmountsChangeNothing() {
    var input = input(1_000, 100);
    accepting = false;
    assertFalse(input.canReceive());
    assertEquals(0, input.receiveEnergy(50, false));
    accepting = true;
    tick = -1;
    assertEquals(0, input.receiveEnergy(50, false), "No input without a server level");
    tick = 0;
    assertEquals(0, input.receiveEnergy(0, false));
    assertEquals(0, input.receiveEnergy(-5, false));
    assertEquals(0, input.energy());
    assertEquals(0, dirty);
    assertFalse(input.canExtract());
    assertEquals(0, input.extractEnergy(10, false));
  }

  @Test
  void spendNeverGoesBelowZeroAndRestoreClamps() {
    var input = input(1_000, 1_000);
    input.receiveEnergy(300, false);
    assertFalse(input.spend(301));
    assertFalse(input.spend(-1));
    assertEquals(300, input.energy());
    assertTrue(input.spend(300));
    assertEquals(0, input.energy());
    input.restore(5_000);
    assertEquals(1_000, input.energy());
    input.restore(-7);
    assertEquals(0, input.energy());
    // Stored energy above capacity (an old save) accepts nothing until spent below it.
    input.restore(1_000);
    assertEquals(0, input.receiveEnergy(10, false));
  }

  @Test
  void rejectsImpossibleBounds() {
    assertThrows(IllegalArgumentException.class, () -> input(0, 1));
    assertThrows(IllegalArgumentException.class, () -> input(1L << 31, 1));
    assertThrows(IllegalArgumentException.class, () -> input(10, 0));
  }
}
