package dev.izzy.factorycore.core.resource;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LegacySettlementTest {
  private static final ResourceKey KEY =
      new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[0]);
  private static final UUID CELL = new UUID(0, 1), OTHER_CELL = new UUID(0, 2);
  private static final UUID BUFFER = new UUID(0, 3), FOREIGN = new UUID(0, 4);

  private static StagedTransfer.Snapshot record(UUID source, UUID destination) {
    return new StagedTransfer.Snapshot(
        UUID.randomUUID(),
        KEY,
        source,
        destination,
        8,
        0,
        0,
        StagedTransfer.State.STAGED,
        OperationResult.Reason.FULL,
        "",
        StagedTransfer.PendingCall.NONE,
        0);
  }

  private static LegacySettlement classify(UUID source, UUID destination) {
    var returnable = Set.of(CELL, OTHER_CELL, BUFFER);
    var deliverable = Set.of(CELL, OTHER_CELL);
    return LegacySettlement.of(
        record(source, destination), returnable::contains, deliverable::contains);
  }

  @Test
  void aCanonicalSourceAlwaysWinsBecauseReturningNeedsNoGrant() {
    assertEquals(new LegacySettlement(true, CELL), classify(CELL, FOREIGN));
    assertEquals(new LegacySettlement(true, CELL), classify(CELL, OTHER_CELL));
    assertEquals(new LegacySettlement(true, BUFFER), classify(BUFFER, FOREIGN));
  }

  @Test
  void anUnknownSourceMayOnlyDeliverIntoACanonicalCellOrBank() {
    assertEquals(new LegacySettlement(false, OTHER_CELL), classify(FOREIGN, OTHER_CELL));
    assertNull(classify(FOREIGN, BUFFER), "Buffers are never delivery targets for legacy staging");
  }

  @Test
  void foreignOrUnknownBackingsAreNeverGuessed() {
    assertNull(classify(FOREIGN, new UUID(0, 5)));
  }
}
