package dev.izzy.factorycore.core.resource;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class TransferRegistryTest {
  private static final ResourceKey KEY =
      new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[0]);

  private static final class Port implements ResourcePort {
    final UUID id;
    long stock;
    long acceptance = Long.MAX_VALUE;

    Port(UUID id, long stock) {
      this.id = id;
      this.stock = stock;
    }

    @Override
    public UUID backingId() {
      return id;
    }

    @Override
    public long simulateInsert(ResourceKey key, long amount) {
      return amount;
    }

    @Override
    public long extract(ResourceKey key, long amount) {
      long n = Math.min(stock, amount);
      stock -= n;
      return n;
    }

    @Override
    public long insert(ResourceKey key, long amount) {
      long n = Math.min(acceptance, amount);
      stock += n;
      return n;
    }
  }

  private static StagedTransfer.Snapshot empty(ResourceKey key) {
    return new StagedTransfer.Snapshot(
        UUID.randomUUID(),
        key,
        UUID.randomUUID(),
        UUID.randomUUID(),
        0,
        0,
        0,
        StagedTransfer.State.COMPLETE,
        OperationResult.Reason.OK,
        "",
        StagedTransfer.PendingCall.NONE,
        0);
  }

  @Test
  void backingInvalidationDuringExtractionAndDeliveryRetainsAccountingAndUnbindsSafely() {
    var registry = new TransferRegistry(List.of(), () -> {});
    var id = UUID.randomUUID();
    var source = new Port(UUID.randomUUID(), 100);
    var destination = new Port(UUID.randomUUID(), 0);
    destination.acceptance = 10;
    var invalidatingSource =
        new ResourcePort() {
          @Override
          public UUID backingId() {
            return source.id;
          }

          @Override
          public long simulateInsert(ResourceKey key, long amount) {
            return source.simulateInsert(key, amount);
          }

          @Override
          public long insert(ResourceKey key, long amount) {
            return source.insert(key, amount);
          }

          @Override
          public long extract(ResourceKey key, long amount) {
            long actual = source.extract(key, amount);
            assertEquals(1, registry.unbindBacking(source.id));
            return actual;
          }
        };
    var started =
        registry.start(
            id, UUID.randomUUID(), KEY, invalidatingSource, destination, 60, 60, () -> true);
    assertEquals(50, started.staged());
    assertEquals(40, source.stock);
    assertEquals(10, destination.stock);
    assertEquals(OperationResult.Reason.OFFLINE, registry.deliver(id).reason());
    assertEquals(0, registry.unbindBacking(source.id));
    var unrelated = UUID.randomUUID();
    var otherSource = new Port(UUID.randomUUID(), 20);
    var otherDest = new Port(UUID.randomUUID(), 0);
    registry.start(unrelated, UUID.randomUUID(), KEY, otherSource, otherDest, 5, 5, () -> true);
    var invalidatingDestination =
        new ResourcePort() {
          @Override
          public UUID backingId() {
            return destination.id;
          }

          @Override
          public long simulateInsert(ResourceKey key, long amount) {
            return amount;
          }

          @Override
          public long extract(ResourceKey key, long amount) {
            return destination.extract(key, amount);
          }

          @Override
          public long insert(ResourceKey key, long amount) {
            long actual = destination.insert(key, amount);
            assertEquals(1, registry.unbindBacking(destination.id));
            return actual;
          }
        };
    destination.acceptance = 50;
    registry.bind(id, source, invalidatingDestination, () -> true);
    assertEquals(50, registry.deliver(id).amount());
    assertEquals(StagedTransfer.State.COMPLETE, registry.get(id).state());
    assertEquals(40, source.stock);
    assertEquals(60, destination.stock);
    assertEquals(OperationResult.Reason.OK, registry.cancel(unrelated).reason());
    assertEquals(15, otherSource.stock);
    assertEquals(5, otherDest.stock);
    registry.retire(id);
    assertEquals(0, registry.unbindBacking(destination.id));
  }

  @Test
  void canonicalOwnerSurvivesUnbindReloadAndCurrentAuthorityWithoutDoubleExtraction() {
    var registry = new TransferRegistry(List.of(), () -> {});
    var source = new Port(UUID.randomUUID(), 100);
    var dest = new Port(UUID.randomUUID(), 0);
    dest.acceptance = 10;
    var id = UUID.randomUUID();
    var auth = new AtomicBoolean(true);
    registry.start(id, UUID.randomUUID(), KEY, source, dest, 60, 60, auth::get);
    assertEquals(40, source.stock);
    assertEquals(50, registry.get(id).staged());
    assertThrows(
        IllegalStateException.class,
        () -> registry.start(id, UUID.randomUUID(), KEY, source, dest, 20, 20, auth::get));
    assertThrows(IllegalStateException.class, () -> registry.retire(id));
    registry.unbind(id);
    assertEquals(OperationResult.Reason.OFFLINE, registry.deliver(id).reason());
    var loaded = new TransferRegistry(registry.snapshot(), () -> {});
    loaded.bind(id, source, dest, auth::get);
    assertThrows(IllegalStateException.class, () -> loaded.bind(id, source, dest, auth::get));
    auth.set(false);
    assertEquals(OperationResult.Reason.DENIED, loaded.deliver(id).reason());
    assertEquals(50, loaded.cancel(id).amount());
    assertEquals(90, source.stock);
    assertEquals(10, dest.stock);
    loaded.retire(id);
    assertTrue(loaded.snapshot().isEmpty());
  }

  @Test
  void pendingExternalIntentCannotRetireReplayOrFabricateRefund() {
    var id = UUID.randomUUID();
    var source = new Port(UUID.randomUUID(), 50);
    var dest = new Port(UUID.randomUUID(), 0);
    var intent =
        new StagedTransfer.Snapshot(
            UUID.randomUUID(),
            KEY,
            source.id,
            dest.id,
            0,
            0,
            0,
            StagedTransfer.State.COMPLETE,
            OperationResult.Reason.OK,
            "",
            StagedTransfer.PendingCall.EXTRACT,
            20);
    var loaded = new TransferRegistry(List.of(new TransferRegistry.Stored(id, intent)), () -> {});
    assertThrows(IllegalStateException.class, () -> loaded.retire(id));
    loaded.bind(id, source, dest, () -> true);
    assertEquals(StagedTransfer.State.QUARANTINED, loaded.get(id).state());
    assertEquals(OperationResult.Reason.UNCERTAIN, loaded.deliver(id).reason());
    assertEquals(OperationResult.Reason.UNCERTAIN, loaded.cancel(id).reason());
    assertEquals(50, source.stock);
    assertEquals(0, dest.stock);
  }

  @Test
  void countByteAndDuplicateAdmissionRejectBeforeResourceMutation() {
    var saved = new ArrayList<TransferRegistry.Stored>();
    for (int i = 0; i < TransferRegistry.MAX_RECORDS; i++)
      saved.add(new TransferRegistry.Stored(UUID.randomUUID(), empty(KEY)));
    var registry = new TransferRegistry(saved, () -> {});
    var source = new Port(UUID.randomUUID(), 100);
    var dest = new Port(UUID.randomUUID(), 0);
    assertThrows(
        IllegalStateException.class,
        () ->
            registry.start(
                UUID.randomUUID(), UUID.randomUUID(), KEY, source, dest, 20, 20, () -> true));
    assertEquals(100, source.stock);
    assertEquals(0, dest.stock);
    assertFalse(registry.canStart(UUID.randomUUID(), KEY));
    registry.retire(saved.getFirst().id());
    assertTrue(registry.canStart(UUID.randomUUID(), KEY));
    registry.start(UUID.randomUUID(), UUID.randomUUID(), KEY, source, dest, 20, 20, () -> true);
    assertEquals(80, source.stock);
    assertEquals(20, dest.stock);
    saved.add(saved.getFirst());
    assertThrows(IllegalArgumentException.class, () -> new TransferRegistry(saved, () -> {}));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TransferRegistry(List.of(saved.getFirst(), saved.getFirst()), () -> {}));
    var big =
        new ResourceKey(
            ResourceKind.ITEM, "minecraft:iron_ingot", new byte[ResourceKey.MAX_COMPONENT_BYTES]);
    saved.clear();
    for (int i = 0; i < 64; i++)
      saved.add(new TransferRegistry.Stored(UUID.randomUUID(), empty(big)));
    assertThrows(IllegalArgumentException.class, () -> new TransferRegistry(saved, () -> {}));
  }

  @Test
  void ownerThreadAndReentrantMutationRejectWithoutDiscardingIntent() throws Exception {
    TransferRegistry[] holder = {null};
    var blocked = new AtomicBoolean();
    var id = UUID.randomUUID();
    holder[0] =
        new TransferRegistry(
            List.of(),
            () -> {
              holder[0].snapshot();
              assertThrows(IllegalStateException.class, () -> holder[0].retire(id));
              blocked.set(true);
            });
    var source = new Port(UUID.randomUUID(), 100);
    var dest = new Port(UUID.randomUUID(), 0);
    holder[0].start(id, UUID.randomUUID(), KEY, source, dest, 20, 20, () -> true);
    assertTrue(blocked.get());
    assertEquals(80, source.stock);
    assertEquals(20, dest.stock);
    holder[0].retire(id);
    assertTrue(holder[0].snapshot().isEmpty());
    var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    var thread =
        new Thread(
            () -> {
              try {
                holder[0].snapshot();
              } catch (IllegalStateException denied) {
                failure.set(denied);
              }
            });
    thread.start();
    thread.join();
    assertInstanceOf(IllegalStateException.class, failure.get());
  }

  @Test
  void uncertainHoldLookupRetainsUnboundDiskEvidenceAndMatchesOwnerKeyAndBacking() {
    var original = empty(KEY);
    var pending =
        new StagedTransfer.Snapshot(
            original.owner(),
            KEY,
            original.source(),
            original.destination(),
            0,
            0,
            0,
            StagedTransfer.State.QUARANTINED,
            OperationResult.Reason.UNCERTAIN,
            "Unknown extraction",
            StagedTransfer.PendingCall.EXTRACT,
            8);
    var id = UUID.randomUUID();
    var restored =
        new TransferRegistry(List.of(new TransferRegistry.Stored(id, pending)), () -> {});
    assertTrue(restored.uncertain(pending.source(), pending.owner(), KEY));
    assertTrue(restored.uncertain(pending.destination(), pending.owner(), KEY));
    restored.unbind(id);
    assertTrue(restored.uncertain(pending.source(), pending.owner(), KEY));
    assertFalse(restored.uncertain(pending.source(), UUID.randomUUID(), KEY));
    assertFalse(restored.uncertain(UUID.randomUUID(), pending.owner(), KEY));
    assertFalse(restored.uncertain(pending.source(), pending.owner(), ResourceKey.ENERGY));
    assertEquals(pending, restored.get(id));
  }
}
