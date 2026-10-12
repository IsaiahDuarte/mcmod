package dev.izzy.factorycore.core.resource;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class InsertSimulationTest {
  private static final ResourceKey IRON =
      new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[0]);
  private static final ResourceKey GOLD =
      new ResourceKey(ResourceKind.ITEM, "minecraft:gold_ingot", new byte[0]);

  @Test
  void admissionChecksCapacityCatalogOfflineAndLongOverflowWithoutMutation() {
    var ledger = new ResourceLedger(UUID.randomUUID(), ResourceKind.ITEM, 100, false, 1, 4);
    ledger.insert(IRON, 90);
    ledger.reserve(UUID.randomUUID(), IRON, 50);
    var before = ledger.snapshot();
    assertEquals(
        new OperationResult(10, OperationResult.Reason.FULL), ledger.simulateInsert(IRON, 20));
    assertEquals(OperationResult.Reason.CATALOG_LIMIT, ledger.simulateInsert(GOLD, 1).reason());
    assertEquals(before, ledger.snapshot());
    ledger.setLoaded(false);
    assertEquals(OperationResult.Reason.OFFLINE, ledger.simulateInsert(IRON, 1).reason());
    var infinite = new ResourceLedger(UUID.randomUUID(), ResourceKind.ITEM, 0, true, 2, 4);
    infinite.insert(IRON, Long.MAX_VALUE - 2);
    assertEquals(
        new OperationResult(2, OperationResult.Reason.TECHNICAL_LIMIT),
        infinite.simulateInsert(IRON, 10));
    assertEquals(Long.MAX_VALUE - 2, infinite.total());
  }
}
