package dev.izzy.factorycore.core.resource;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.storage.ConnectorSettings;
import org.junit.jupiter.api.Test;

class ThroughputTierTest {
  @Test
  void paidStepsCannotBeSkippedRepeatedOrAppliedToTheWrongDevice() {
    assertEquals(
        ThroughputTier.ADVANCED, ThroughputTier.BASE.upgradeWith(ThroughputTier.ADVANCED, false));
    assertEquals(
        ThroughputTier.ENDGAME, ThroughputTier.ADVANCED.upgradeWith(ThroughputTier.ENDGAME, false));
    assertNull(ThroughputTier.BASE.upgradeWith(ThroughputTier.ENDGAME, false));
    assertNull(ThroughputTier.ADVANCED.upgradeWith(ThroughputTier.ADVANCED, false));
    assertNull(ThroughputTier.ENDGAME.upgradeWith(ThroughputTier.ENDGAME, false));
    assertNull(ThroughputTier.BASE.upgradeWith(ThroughputTier.ADVANCED, true));
    assertEquals(
        ThroughputTier.ENDGAME, ThroughputTier.BASE.upgradeWith(ThroughputTier.ENDGAME, true));
    assertThrows(IllegalArgumentException.class, () -> ThroughputTier.parse("FUTURE"));
  }

  @Test
  void largerOperationsSpendProportionalSharedCreditsAndRespectAbsoluteBounds() {
    assertEquals(16, ThroughputTier.credits(ResourceKind.ITEM, 128));
    assertEquals(32, ThroughputTier.credits(ResourceKind.ITEM, 129));
    assertEquals(128, ThroughputTier.credits(ResourceKind.ITEM, 1024));
    assertEquals(256, ThroughputTier.credits(ResourceKind.FLUID, 16000));
    assertEquals(512, ThroughputTier.credits(ResourceKind.ENERGY, 32000));
    for (var kind : ResourceKind.values()) {
      assertThrows(IllegalArgumentException.class, () -> ThroughputTier.credits(kind, -1));
      assertThrows(
          IllegalArgumentException.class,
          () -> ThroughputTier.credits(kind, ThroughputTier.maxQuantum(kind) + 1));
    }
    var raised =
        new ConnectorSettings(
            ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 0, 0, 64, 256);
    assertFalse(raised.fits(ThroughputTier.BASE));
    assertTrue(raised.fits(ThroughputTier.ADVANCED));
  }

  /** Q19: removal takes the top step; a link drops its single Overdrive; base has nothing. */
  @Test
  void removalTakesTheTopStep() {
    assertEquals(ThroughputTier.ADVANCED, ThroughputTier.ENDGAME.removeStep(false));
    assertEquals(ThroughputTier.BASE, ThroughputTier.ADVANCED.removeStep(false));
    assertEquals(ThroughputTier.BASE, ThroughputTier.ENDGAME.removeStep(true));
    assertNull(ThroughputTier.BASE.removeStep(false));
  }
}
