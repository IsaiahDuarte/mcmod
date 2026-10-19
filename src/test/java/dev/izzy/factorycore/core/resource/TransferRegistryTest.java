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
    assertEquals(id, registry.unresolved(source.id).id());
    assertEquals(id, registry.unresolved(dest.id).id());
    registry.unbind(id);
    assertEquals(id, registry.unresolved(source.id).id());
    assertEquals(OperationResult.Reason.OFFLINE, registry.deliver(id).reason());
    var loaded = new TransferRegistry(registry.snapshot(), () -> {});
    assertEquals(id, loaded.unresolved(source.id).id());
    loaded.bind(id, source, dest, auth::get);
    assertThrows(IllegalStateException.class, () -> loaded.bind(id, source, dest, auth::get));
    auth.set(false);
    assertEquals(OperationResult.Reason.DENIED, loaded.deliver(id).reason());
    assertEquals(50, loaded.cancel(id).amount());
    assertEquals(90, source.stock);
    assertEquals(10, dest.stock);
    assertNull(loaded.unresolved(source.id));
    assertNull(loaded.unresolved(dest.id));
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
    assertEquals(id, restored.unresolved(pending.source()).id());
    restored.unbind(id);
    assertEquals(id, restored.unresolved(pending.destination()).id());
    assertTrue(restored.uncertain(pending.source(), pending.owner(), KEY));
    assertFalse(restored.uncertain(pending.source(), UUID.randomUUID(), KEY));
    assertFalse(restored.uncertain(UUID.randomUUID(), pending.owner(), KEY));
    assertFalse(restored.uncertain(pending.source(), pending.owner(), ResourceKey.ENERGY));
    assertEquals(pending, restored.get(id));
  }

  @Test
  void multipleUnresolvedOwnersRemainBlockedAcrossReloadUntilAllKnownStagingFinishes() {
    var registry = new TransferRegistry(List.of(), () -> {});
    var source = new Port(UUID.randomUUID(), 100);
    var target = new Port(UUID.randomUUID(), 0);
    target.acceptance = 0;
    var first = new UUID(0, 1);
    var second = new UUID(0, 2);
    registry.start(second, UUID.randomUUID(), KEY, source, target, 30, 30, () -> true);
    registry.start(first, UUID.randomUUID(), KEY, source, target, 20, 20, () -> true);
    assertEquals(50, source.stock);
    assertEquals(first, registry.unresolved(source.id).id());
    var restored = new TransferRegistry(registry.snapshot(), () -> {});
    assertEquals(first, restored.unresolved(target.id).id());
    target.acceptance = 20;
    restored.bind(first, source, target, () -> true);
    assertEquals(20, restored.deliver(first).amount());
    restored.retire(first);
    assertEquals(second, restored.unresolved(source.id).id());
    assertEquals(30, restored.unresolved(source.id).transfer().staged());
    restored.bind(second, source, target, () -> true);
    assertEquals(30, restored.cancel(second).amount());
    assertNull(restored.unresolved(source.id));
    assertNull(restored.unresolved(target.id));
    assertEquals(100, source.stock + target.stock);
  }

  @Test
  void deviceIndexSurvivesUnbindAndRestoreWithoutConfusingBackingIdentity() {
    var registry = new TransferRegistry(List.of(), () -> {});
    var source = new Port(UUID.randomUUID(), 20);
    var destination = new Port(UUID.randomUUID(), 0);
    destination.acceptance = 0;
    UUID device = UUID.randomUUID(), id = UUID.randomUUID(), owner = UUID.randomUUID();
    var context =
        new TransferContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            new dev.izzy.factorycore.core.network.NodeRegistry.Position("minecraft:overworld", 0),
            new TransferContext.Endpoint(TransferContext.Type.DIGITAL, source.id, source.id, 1, 0),
            new TransferContext.Endpoint(
                TransferContext.Type.EXTERNAL, device, destination.id, 1, 1));
    registry.start(id, owner, KEY, source, destination, 12, 12, () -> true, context);
    assertEquals(id, registry.unresolvedDevice(device).id());
    assertNull(registry.unresolved(device));
    assertNull(registry.unresolvedDevice(destination.id));
    var known = registry.get(id);
    var pending =
        new StagedTransfer.Snapshot(
            known.owner(),
            known.key(),
            known.source(),
            known.destination(),
            known.staged(),
            known.delivered(),
            known.returned(),
            StagedTransfer.State.QUARANTINED,
            OperationResult.Reason.UNCERTAIN,
            "Unknown insert",
            StagedTransfer.PendingCall.DELIVER,
            12);
    var uncertain =
        new TransferRegistry(List.of(new TransferRegistry.Stored(id, pending, context)), () -> {});
    assertEquals(id, uncertain.unresolvedDevice(device).id());
    assertEquals(
        StagedTransfer.PendingCall.DELIVER,
        uncertain.unresolvedDevice(device).transfer().pending());
    registry.unbind(id);
    var loaded = new TransferRegistry(registry.snapshot(), () -> {});
    assertEquals(12, loaded.unresolvedDevice(device).transfer().staged());
    loaded.bind(id, source, destination, () -> true);
    assertEquals(12, loaded.cancel(id).amount());
    assertNull(loaded.unresolvedDevice(device));
    loaded.retire(id);
    assertEquals(20, source.stock);
    assertTrue(loaded.snapshot().isEmpty());
  }

  @Test
  void metadataPagesAreOrderedDetachedAndExclusiveWithoutDroppingUnresolvedOwners() {
    var first = new UUID(0, 1);
    var second = new UUID(0, 2);
    var third = new UUID(0, 3);
    var registry =
        new TransferRegistry(
            List.of(
                new TransferRegistry.Stored(third, empty(KEY)),
                new TransferRegistry.Stored(first, empty(KEY)),
                new TransferRegistry.Stored(second, empty(KEY))),
            () -> {});
    var page = registry.page(null, 2);
    assertEquals(
        List.of(first, second), page.records().stream().map(TransferRegistry.Stored::id).toList());
    assertEquals(second, page.after());
    assertThrows(UnsupportedOperationException.class, () -> page.records().clear());
    registry.retire(first);
    assertEquals(2, page.records().size());
    var next = registry.page(page.after(), 2);
    assertEquals(List.of(third), next.records().stream().map(TransferRegistry.Stored::id).toList());
    assertNull(next.after());
    assertTrue(registry.page(third, 2).records().isEmpty());
    assertThrows(IllegalArgumentException.class, () -> registry.page(null, 17));
  }

  @Test
  void unscopedRecoveryCheckIsReadOnlyAndIgnoresCompletedOrScopedRecords() {
    int[] changes = {0};
    var source = new Port(UUID.randomUUID(), 20);
    var target = new Port(UUID.randomUUID(), 0);
    var registry = new TransferRegistry(List.of(), () -> changes[0]++);
    var completed = UUID.randomUUID();
    registry.start(completed, UUID.randomUUID(), KEY, source, target, 1, 1, () -> true);
    assertFalse(registry.hasUnscopedUnresolved());
    target.acceptance = 0;
    var scoped = UUID.randomUUID();
    var context =
        new TransferContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            new dev.izzy.factorycore.core.network.NodeRegistry.Position("minecraft:overworld", 0),
            new TransferContext.Endpoint(TransferContext.Type.DIGITAL, source.id, source.id, 1, 0),
            new TransferContext.Endpoint(
                TransferContext.Type.EXTERNAL, UUID.randomUUID(), target.id, 1, 1));
    registry.start(scoped, UUID.randomUUID(), KEY, source, target, 3, 3, () -> true, context);
    assertFalse(registry.hasUnscopedUnresolved());
    var legacy = UUID.randomUUID();
    registry.start(legacy, UUID.randomUUID(), KEY, source, target, 4, 4, () -> true);
    var before = registry.snapshot();
    int dirty = changes[0];
    assertTrue(registry.hasUnscopedUnresolved());
    registry.unbind(legacy);
    assertTrue(registry.hasUnscopedUnresolved());
    var restored = new TransferRegistry(before, () -> fail("Inspection changed evidence"));
    assertTrue(restored.hasUnscopedUnresolved());
    assertEquals(before, restored.snapshot());
    assertEquals(dirty, changes[0]);
    registry.bind(legacy, source, target, () -> true);
    target.acceptance = 10;
    registry.deliver(legacy);
    assertFalse(
        registry.hasUnscopedUnresolved(),
        "Scoped pending owner still does not make legacy ambiguity");
    var unknown =
        new StagedTransfer.Snapshot(
            UUID.randomUUID(),
            KEY,
            source.id,
            target.id,
            4,
            0,
            0,
            StagedTransfer.State.QUARANTINED,
            OperationResult.Reason.UNCERTAIN,
            "Interrupted foreign call",
            StagedTransfer.PendingCall.DELIVER,
            4);
    assertTrue(
        new TransferRegistry(
                List.of(new TransferRegistry.Stored(UUID.randomUUID(), unknown)), () -> {})
            .hasUnscopedUnresolved());
  }

  @Test
  void portPreflightBlocksOnlyUncertainLegacyRecordsNamingANonCanonicalBacking() {
    var cellA = UUID.randomUUID();
    var cellB = UUID.randomUUID();
    var provider = UUID.randomUUID();
    java.util.function.Predicate<UUID> canonical = id -> id.equals(cellA) || id.equals(cellB);
    java.util.function.BiFunction<UUID, Boolean, StagedTransfer.Snapshot> legacy =
        (destination, uncertain) ->
            new StagedTransfer.Snapshot(
                UUID.randomUUID(),
                KEY,
                cellA,
                destination,
                4,
                0,
                0,
                uncertain ? StagedTransfer.State.QUARANTINED : StagedTransfer.State.STAGED,
                uncertain ? OperationResult.Reason.UNCERTAIN : OperationResult.Reason.FULL,
                "",
                uncertain ? StagedTransfer.PendingCall.DELIVER : StagedTransfer.PendingCall.NONE,
                uncertain ? 4 : 0);
    var known =
        new TransferRegistry(
            List.of(new TransferRegistry.Stored(UUID.randomUUID(), legacy.apply(provider, false))),
            () -> fail("Inspection changed evidence"));
    assertTrue(known.hasUnscopedUnresolved());
    assertFalse(
        known.hasUnscopedUncertain(canonical), "Known legacy staging settles without any port");
    var betweenCells =
        new TransferRegistry(
            List.of(new TransferRegistry.Stored(UUID.randomUUID(), legacy.apply(cellB, true))),
            () -> fail("Inspection changed evidence"));
    assertFalse(
        betweenCells.hasUnscopedUncertain(canonical),
        "An uncertain record between canonical holders cannot involve a foreign provider");
    var foreign =
        new TransferRegistry(
            List.of(new TransferRegistry.Stored(UUID.randomUUID(), legacy.apply(provider, true))),
            () -> fail("Inspection changed evidence"));
    var before = foreign.snapshot();
    assertTrue(foreign.hasUnscopedUncertain(canonical), "It may name any port's provider");
    assertEquals(before, foreign.snapshot());
    var scoped = UUID.randomUUID();
    var registry = new TransferRegistry(List.of(), () -> {});
    var source = new Port(cellA, 4);
    var target = new Port(provider, 0);
    target.acceptance = 0;
    registry.start(
        scoped,
        UUID.randomUUID(),
        KEY,
        source,
        target,
        4,
        4,
        () -> true,
        new TransferContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            new dev.izzy.factorycore.core.network.NodeRegistry.Position("minecraft:overworld", 0),
            new TransferContext.Endpoint(TransferContext.Type.DIGITAL, cellA, cellA, 1, 0),
            new TransferContext.Endpoint(
                TransferContext.Type.EXTERNAL, UUID.randomUUID(), provider, 1, 1)));
    assertFalse(
        registry.hasUnscopedUncertain(id -> false),
        "Scoped records are checked through their device context instead");
  }
}
