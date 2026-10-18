package dev.izzy.factorycore.core.storage;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FuelGeneratorTest {
  @Test
  void stickBurnsForItsDurationAndConvertsExactlyTwentyFePerTick() {
    var generator = new FuelGenerator();
    assertEquals(FuelGenerator.Status.NO_FUEL, generator.status());
    assertEquals(0, generator.burn());
    generator.ignite(100);
    assertThrows(IllegalStateException.class, () -> generator.ignite(100));
    long produced = 0;
    for (int tick = 0; tick < 150; tick++) produced += generator.burn();
    assertEquals(2_000, produced);
    assertEquals(2_000, generator.energy());
    assertEquals(new FuelGenerator.Snapshot(2_000, 0, 0), generator.snapshot());
    assertTrue(generator.needsFuel());
    assertThrows(IllegalArgumentException.class, () -> generator.ignite(0));
  }

  @Test
  void fullBufferPausesAndKeepsRemainingBurnInsteadOfDiscardingEnergy() {
    var generator = new FuelGenerator(new FuelGenerator.Snapshot(99_960, 0, 0));
    generator.ignite(1_600);
    assertEquals(20, generator.burn());
    assertEquals(20, generator.burn());
    assertEquals(FuelGenerator.Status.FULL, generator.status());
    assertEquals(0, generator.burn());
    assertEquals(new FuelGenerator.Snapshot(100_000, 1_598, 1_600), generator.snapshot());
    assertFalse(generator.needsFuel());
    assertEquals(20, generator.extract(20, false, 5));
    assertEquals(FuelGenerator.Status.BURNING, generator.status());
    assertEquals(20, generator.burn());
    assertEquals(1_597, generator.burnRemaining());
    var partial = new FuelGenerator(new FuelGenerator.Snapshot(99_990, 0, 0));
    assertFalse(partial.needsFuel(), "Ten FE of space cannot hold one burn tick");
  }

  @Test
  void outputBudgetIsEightyFePerTickAcrossCallersAndSimulationDoesNotSpend() {
    var generator = new FuelGenerator(new FuelGenerator.Snapshot(1_000, 0, 0));
    assertEquals(80, generator.extract(500, true, 10));
    assertEquals(80, generator.extract(500, true, 10));
    assertEquals(50, generator.extract(50, false, 10));
    assertEquals(30, generator.extract(50, true, 10));
    assertEquals(30, generator.extract(50, false, 10));
    assertEquals(0, generator.extract(50, false, 10));
    assertEquals(80, generator.extract(500, true, 11), "A later tick has a fresh budget");
    assertEquals(920, generator.energy());
    assertEquals(80, generator.extract(500, false, 11));
    assertThrows(IllegalStateException.class, () -> generator.extract(1, false, 10));
    assertThrows(IllegalArgumentException.class, () -> generator.extract(-1, false, 11));
    var low = new FuelGenerator(new FuelGenerator.Snapshot(7, 0, 0));
    assertEquals(7, low.extract(80, false, 0));
    assertEquals(0, low.energy());
  }

  @Test
  void invalidSnapshotsReject() {
    assertThrows(IllegalArgumentException.class, () -> new FuelGenerator.Snapshot(-1, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FuelGenerator.Snapshot(FuelGenerator.CAPACITY + 1, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new FuelGenerator.Snapshot(0, 5, 4));
    assertThrows(IllegalArgumentException.class, () -> new FuelGenerator.Snapshot(0, -1, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FuelGenerator.Snapshot(0, 0, (long) Integer.MAX_VALUE + 1));
    assertEquals(
        new FuelGenerator.Snapshot(5, 3, 9),
        new FuelGenerator(new FuelGenerator.Snapshot(5, 3, 9)).snapshot());
  }
}
