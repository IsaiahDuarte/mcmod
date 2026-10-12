package dev.izzy.factorycore.core.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.resource.*;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ItemBufferTest {
  private static final ResourceKey IRON =
      new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[] {1});
  private static final ResourceKey OTHER_IRON =
      new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[] {2});
  private static final ResourceKey TOOL =
      new ResourceKey(ResourceKind.ITEM, "minecraft:iron_pickaxe", new byte[] {3});

  private static ItemBuffer buffer(Runnable changed) {
    return new ItemBuffer(
        ItemBuffer.empty(UUID.randomUUID()), key -> key.equals(TOOL) ? 1 : 64, changed);
  }

  @Test
  void ninePhysicalSlotsKeepExactComponentsAndIndividualStackLimits() {
    var changes = new AtomicInteger();
    var buffer = buffer(changes::incrementAndGet);
    assertEquals(64, buffer.insertSlot(0, IRON, 80, false));
    assertEquals(0, buffer.insertSlot(0, OTHER_IRON, 1, false));
    assertEquals(1, buffer.insertSlot(1, TOOL, 64, false));
    assertEquals(64, buffer.insertSlot(8, OTHER_IRON, Long.MAX_VALUE, true));
    assertEquals(0, buffer.total(OTHER_IRON));
    assertEquals(2, changes.get());
    assertEquals(IRON, buffer.slot(0).key());
    assertEquals(TOOL, buffer.slot(1).key());
    assertEquals(1, buffer.slot(1).limit());
    assertEquals(448, buffer.simulateInsert(OTHER_IRON, Long.MAX_VALUE));
    assertEquals(448, buffer.insert(OTHER_IRON, Long.MAX_VALUE));
    assertEquals(0, buffer.insert(IRON, 1));
    assertEquals(0, buffer.insert(ResourceKey.ENERGY, 1));
    assertEquals(513, buffer.total(IRON) + buffer.total(OTHER_IRON) + buffer.total(TOOL));
    assertEquals(3, changes.get());
    assertThrows(IllegalArgumentException.class, () -> buffer.slot(-1));
    assertThrows(IllegalArgumentException.class, () -> buffer.insertSlot(9, IRON, 1, false));
    assertThrows(IllegalArgumentException.class, () -> buffer.extract(IRON, -1));
  }

  @Test
  void hopperSimulationAndImmutableReloadDoNotDirtyOrDuplicateOwnedStock() {
    var changes = new AtomicInteger();
    var buffer = buffer(changes::incrementAndGet);
    buffer.insertSlot(3, IRON, 30, false);
    assertEquals(new ItemBuffer.Slot(IRON, 12, 64), buffer.extractSlot(3, 12, true));
    var snapshot = buffer.snapshot();
    assertThrows(UnsupportedOperationException.class, () -> snapshot.slots().clear());
    assertEquals(30, buffer.slot(3).count());
    assertEquals(1, changes.get());
    var restored = new ItemBuffer(snapshot, key -> 64, changes::incrementAndGet);
    assertEquals(snapshot, restored.snapshot());
    assertEquals(1, changes.get());
    assertEquals(12, restored.extractSlot(3, 12, false).count());
    assertEquals(18, restored.total(IRON));
    assertEquals(30, buffer.total(IRON));
    assertEquals(2, changes.get());
    assertEquals(0, restored.insert(IRON, 0));
    assertEquals(0, restored.extract(IRON, 0));
    assertEquals(2, changes.get());
    assertThrows(IllegalArgumentException.class, () -> new ItemBuffer.Slot(IRON, 65, 64));
    assertThrows(
        IllegalArgumentException.class, () -> new ItemBuffer.Slot(ResourceKey.ENERGY, 1, 64));
    assertThrows(
        IllegalArgumentException.class, () -> new ItemBuffer.State(UUID.randomUUID(), List.of()));
  }

  @Test
  void fullStorageBackpressureDoesNotDrainBufferAndKnownStagingReturnsWithoutLoss() {
    var buffer = buffer(() -> {});
    buffer.insert(IRON, 64);
    var target = new ResourceLedger(UUID.randomUUID(), ResourceKind.ITEM, 0, false, 8, 8);
    var blocked =
        StagedTransfer.start(
            UUID.randomUUID(), IRON, buffer, new LedgerPort(target, null), 64, 64, () -> true);
    assertEquals(64, buffer.total(IRON));
    assertEquals(0, blocked.staged());
    assertEquals(0, blocked.delivered());
    var destination = new ResourceLedger(UUID.randomUUID(), ResourceKind.ITEM, 100, false, 8, 8);
    var destinationPort = new LedgerPort(destination, null);
    ResourcePort partial =
        new ResourcePort() {
          public UUID backingId() {
            return destinationPort.backingId();
          }

          public long simulateInsert(ResourceKey key, long offered) {
            return destinationPort.simulateInsert(key, offered);
          }

          public long extract(ResourceKey key, long requested) {
            return destinationPort.extract(key, requested);
          }

          public long insert(ResourceKey key, long offered) {
            return destinationPort.insert(key, Math.min(5, offered));
          }
        };
    var move = StagedTransfer.start(UUID.randomUUID(), IRON, buffer, partial, 64, 64, () -> true);
    assertEquals(5, move.delivered());
    assertEquals(59, move.staged());
    assertEquals(0, buffer.total(IRON));
    assertEquals(576, buffer.insert(OTHER_IRON, 576));
    assertEquals(0, move.cancel().amount());
    assertEquals(59, move.staged());
    var removed = buffer.extractSlot(0, 64, false);
    assertEquals(64, removed.count());
    assertEquals(59, move.cancel().amount());
    assertEquals(0, move.staged());
    assertEquals(64, buffer.total(IRON) + destination.total());
    assertEquals(576, buffer.total(OTHER_IRON) + removed.count());
  }

  @Test
  void generatedSlotTransfersAndReloadsConserveEveryExactKey() {
    long seed = 0xB0FFE901L;
    var random = new Random(seed);
    var keys = List.of(IRON, OTHER_IRON, TOOL);
    long[] owned = new long[keys.size()];
    var buffer = buffer(() -> {});
    for (int step = 0; step < 5000; step++) {
      int index = random.nextInt(keys.size());
      var key = keys.get(index);
      long amount = random.nextInt(160);
      int slot = random.nextInt(ItemBuffer.SLOTS);
      switch (random.nextInt(5)) {
        case 0 -> owned[index] += buffer.insert(key, amount);
        case 1 -> owned[index] -= buffer.extract(key, amount);
        case 2 -> owned[index] += buffer.insertSlot(slot, key, amount, false);
        case 3 -> {
          var extracted = buffer.extractSlot(slot, amount, false);
          if (extracted.key() != null) owned[keys.indexOf(extracted.key())] -= extracted.count();
        }
        default -> {
          var snapshot = buffer.snapshot();
          buffer.simulateInsert(key, amount);
          buffer.insertSlot(slot, key, amount, true);
          buffer.extractSlot(slot, amount, true);
          assertEquals(snapshot, buffer.snapshot(), "seed=" + seed + ", step=" + step);
          buffer = new ItemBuffer(snapshot, candidate -> candidate.equals(TOOL) ? 1 : 64, () -> {});
        }
      }
      for (int identity = 0; identity < keys.size(); identity++)
        assertEquals(
            owned[identity], buffer.total(keys.get(identity)), "seed=" + seed + ", step=" + step);
    }
  }

  @Test
  void dirtyNotificationCannotReenterMutationAndChangedLimitsNeverOverfillOldStacks() {
    ItemBuffer[] current = {null};
    var limit = new AtomicInteger(64);
    current[0] =
        new ItemBuffer(
            ItemBuffer.empty(UUID.randomUUID()),
            key -> limit.get(),
            () -> {
              assertThrows(IllegalStateException.class, () -> current[0].extract(IRON, 1));
            });
    current[0].insertSlot(0, IRON, 30, false);
    limit.set(16);
    assertEquals(0, current[0].insertSlot(0, IRON, 1, false));
    assertEquals(16, current[0].insertSlot(1, IRON, 64, false));
    assertEquals(30, current[0].extractSlot(0, 64, false).count());
    assertEquals(16, current[0].total(IRON));
    ItemBuffer[] resolving = {null};
    resolving[0] =
        new ItemBuffer(
            ItemBuffer.empty(UUID.randomUUID()),
            key -> {
              assertThrows(IllegalStateException.class, () -> resolving[0].insert(IRON, 1));
              return 64;
            },
            () -> {});
    assertEquals(1, resolving[0].insert(IRON, 1));
    assertEquals(1, resolving[0].total(IRON));
  }
}
