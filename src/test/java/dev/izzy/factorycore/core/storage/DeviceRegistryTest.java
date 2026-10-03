package dev.izzy.factorycore.core.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.resource.LedgerState;
import dev.izzy.factorycore.core.resource.LedgerStateCodec;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ResourceLedger;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DeviceRegistryTest {
  private static final UUID WORLD = new UUID(0, 11);
  private static final UUID JOB = new UUID(0, 12);
  private static final ResourceKey KEY =
      new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[] {1, 2});
  private static final DeviceRegistry.Location FIRST =
      new DeviceRegistry.Location("minecraft:overworld", 100, 0);
  private static final DeviceRegistry.Location SECOND =
      new DeviceRegistry.Location("minecraft:overworld", 200, 1);

  private static DeviceRegistry registry(Runnable dirty) {
    var next = new AtomicLong();
    return new DeviceRegistry(WORLD, dirty, () -> new UUID(1, next.incrementAndGet()));
  }

  private static DeviceRegistry.Handle placed(DeviceRegistry registry) {
    var portable = registry.create(ResourceKind.ITEM, 1000, false, 4096, 8192).handle();
    var placed = registry.attach(portable, FIRST).handle();
    assertEquals(DeviceRegistry.Failure.OK, registry.availability(placed, FIRST, true));
    return placed;
  }

  @Test
  void movingDeviceConsumesPortableGenerationAndRetainsExactStockAndReservations() {
    var registry = registry(() -> {});
    var placed = placed(registry);
    registry.insert(placed, KEY, 100);
    registry.reserve(placed, JOB, KEY, 60);
    var portable = registry.detach(placed, FIRST).handle();
    assertEquals(DeviceRegistry.Failure.STALE, registry.validate(placed));
    assertEquals(OperationResult.Reason.OFFLINE, registry.insert(portable, KEY, 1).reason());
    assertEquals(new ResourceLedger.Stock(100, 60, 0, false), registry.stock(portable, KEY));
    var moved = registry.attach(portable, SECOND).handle();
    assertEquals(DeviceRegistry.Failure.STALE, registry.attach(portable, FIRST).failure());
    assertEquals(DeviceRegistry.Failure.OK, registry.availability(moved, SECOND, true));
    assertEquals(40, registry.extract(moved, KEY, 100, null).amount());
    assertEquals(60, registry.extract(moved, KEY, 100, JOB).amount());
    assertEquals(0, registry.stock(moved, KEY).total());
  }

  @Test
  void conflictingLiveCopyIsQuarantinedAcrossReloadAndNeverAddsStock() {
    var registry = registry(() -> {});
    var handle = placed(registry);
    registry.insert(handle, KEY, 100);
    assertEquals(DeviceRegistry.Failure.CONFLICT, registry.attach(handle, SECOND).failure());
    assertEquals(DeviceRegistry.Failure.CONFLICT, registry.availability(handle, FIRST, true));
    assertEquals(OperationResult.Reason.DENIED, registry.extract(handle, KEY, 100, null).reason());
    var snapshot = registry.snapshot();
    assertEquals(1, snapshot.devices().size());
    assertEquals(100, snapshot.devices().getFirst().ledger().contents().get(KEY));
    var reloaded = DeviceRegistry.restore(snapshot, () -> {}, UUID::randomUUID);
    assertEquals(DeviceRegistry.Failure.CONFLICT, reloaded.validate(handle));
    assertEquals(
        DeviceRegistry.Failure.FOREIGN_WORLD,
        reloaded.validate(
            new DeviceRegistry.Handle(UUID.randomUUID(), handle.backing(), handle.generation())));
    assertEquals(
        DeviceRegistry.Failure.UNKNOWN,
        reloaded.validate(new DeviceRegistry.Handle(WORLD, UUID.randomUUID(), 1)));
  }

  @Test
  void unloadReloadDoesNotReactivateRoutingOrReleaseClaimsAndUpgradeKeepsHandle() {
    var registry = registry(() -> {});
    var handle = placed(registry);
    registry.insert(handle, KEY, 100);
    registry.reserve(handle, JOB, KEY, 60);
    registry.availability(handle, FIRST, false);
    assertEquals(OperationResult.Reason.OFFLINE, registry.extract(handle, KEY, 100, JOB).reason());
    var reload = DeviceRegistry.restore(registry.snapshot(), () -> {}, UUID::randomUUID);
    assertEquals(handle, reload.attach(handle, FIRST).handle());
    assertFalse(reload.stock(handle, KEY).loaded());
    assertEquals(OperationResult.Reason.FULL, reload.resize(handle, 99, false).reason());
    assertEquals(OperationResult.Reason.OK, reload.resize(handle, 1000, true).reason());
    assertEquals(DeviceRegistry.Failure.OK, reload.availability(handle, FIRST, true));
    assertEquals(new ResourceLedger.Stock(100, 60, 40, true), reload.stock(handle, KEY));
    assertEquals(60, reload.release(handle, JOB, KEY).amount());
    assertEquals(100, reload.extract(handle, KEY, 100, null).amount());
  }

  @Test
  void diskMetadataBudgetRejectsBeforeTakingNewStockAndFreesOnRemoval() {
    var registry = registry(() -> {});
    var portable = registry.create(ResourceKind.ITEM, Long.MAX_VALUE, true, 4096, 8192).handle();
    var handle = registry.attach(portable, FIRST).handle();
    registry.availability(handle, FIRST, true);
    for (int i = 0; i < 127; i++) {
      assertEquals(1, registry.insert(handle, giantKey(i), 1).amount());
    }
    assertEquals(
        OperationResult.Reason.TECHNICAL_LIMIT, registry.insert(handle, giantKey(127), 1).reason());
    assertEquals(127, registry.snapshot().devices().getFirst().ledger().contents().size());
    assertEquals(
        OperationResult.Reason.TECHNICAL_LIMIT,
        registry.reserve(handle, JOB, giantKey(1), 1).reason());
    assertEquals(0, registry.stock(handle, giantKey(1)).reserved());
    assertEquals(1, registry.extract(handle, giantKey(0), 1, null).amount());
    assertEquals(1, registry.reserve(handle, JOB, giantKey(1), 1).amount());
    assertEquals(
        OperationResult.Reason.TECHNICAL_LIMIT, registry.insert(handle, giantKey(127), 1).reason());
    assertEquals(1, registry.release(handle, JOB, giantKey(1)).amount());
    assertEquals(1, registry.insert(handle, giantKey(127), 1).amount());
    LedgerStateCodec.encode(registry.snapshot().devices().getFirst().ledger());
    assertEquals(
        OperationResult.Reason.WRONG_RESOURCE,
        registry.insert(handle, ResourceKey.ENERGY, 1).reason());
  }

  @Test
  void duplicateSavedOwnersPlacementAndGenerationOverflowCannotBecomeLive() {
    var ledger =
        new LedgerState(
            new UUID(0, 1), ResourceKind.ITEM, 100, false, 8, 8, Map.of(KEY, 10L), List.of());
    var owner = new DeviceRegistry.Stored(ledger, 1, FIRST, false);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DeviceRegistry.restore(
                new DeviceRegistry.State(WORLD, List.of(owner, owner)),
                () -> {},
                UUID::randomUUID));
    var second =
        new LedgerState(new UUID(0, 2), ResourceKind.ITEM, 100, false, 8, 8, Map.of(), List.of());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DeviceRegistry.restore(
                new DeviceRegistry.State(
                    WORLD, List.of(owner, new DeviceRegistry.Stored(second, 1, FIRST, false))),
                () -> {},
                UUID::randomUUID));
    var end =
        DeviceRegistry.restore(
            new DeviceRegistry.State(
                WORLD, List.of(new DeviceRegistry.Stored(ledger, Long.MAX_VALUE, FIRST, false))),
            () -> {},
            UUID::randomUUID);
    assertEquals(
        DeviceRegistry.Failure.LIMIT,
        end.detach(new DeviceRegistry.Handle(WORLD, ledger.id(), Long.MAX_VALUE), FIRST).failure());
    assertEquals(10L, end.snapshot().devices().getFirst().ledger().contents().get(KEY));
  }

  @Test
  void encodedByteAccountingStaysExactThroughGeneratedClaimsAndPartialExtraction() {
    long seed = 0xD031001L;
    var random = new Random(seed);
    var ledger = new ResourceLedger(UUID.randomUUID(), ResourceKind.ITEM, 1000, false, 8, 8);
    for (int step = 0; step < 3000; step++) {
      switch (random.nextInt(5)) {
        case 0 -> ledger.insert(KEY, random.nextInt(50));
        case 1 -> ledger.reserve(JOB, KEY, random.nextInt(50));
        case 2 -> ledger.extract(KEY, random.nextInt(50), null);
        case 3 -> ledger.extract(KEY, random.nextInt(50), JOB);
        case 4 -> ledger.release(JOB, KEY);
        default -> throw new AssertionError();
      }
      assertEquals(
          LedgerStateCodec.encode(ledger.persistentState()).length,
          ledger.serializedBytes(),
          "seed=" + seed + " step=" + step);
      assertEquals(
          ledger.snapshot().size() + ledger.persistentState().reservations().size(),
          ledger.entryCount());
    }
  }

  private static ResourceKey giantKey(int id) {
    return new ResourceKey(ResourceKind.ITEM, "test:large_" + id, new byte[65536]);
  }

  @Test
  void deviceAndWorldEntryLimitsRejectWithoutDroppingExistingOwners() {
    var registry = registry(() -> {});
    for (int i = 0; i < DeviceRegistry.MAX_DEVICES; i++) {
      assertEquals(
          DeviceRegistry.Failure.OK,
          registry.create(ResourceKind.ENERGY, 100, false, 8, 8).failure());
    }
    assertEquals(
        DeviceRegistry.Failure.LIMIT,
        registry.create(ResourceKind.ENERGY, 100, false, 8, 8).failure());
    assertEquals(DeviceRegistry.MAX_DEVICES, registry.snapshot().devices().size());
    var anotherRegistry = registry(() -> {});
    placed(anotherRegistry);
    var extra = anotherRegistry.create(ResourceKind.ITEM, 100, false, 8, 8).handle();
    assertEquals(DeviceRegistry.Failure.OCCUPIED, anotherRegistry.attach(extra, FIRST).failure());
    assertEquals(DeviceRegistry.Failure.OK, anotherRegistry.validate(extra));
    var stock = new java.util.HashMap<ResourceKey, Long>();
    var claims = new java.util.ArrayList<LedgerState.Reservation>();
    for (int i = 0; i < 4096; i++) {
      var key = new ResourceKey(ResourceKind.ITEM, "test:entry_" + i, new byte[0]);
      stock.put(key, 2L);
      claims.add(new LedgerState.Reservation(JOB, key, 1));
      claims.add(new LedgerState.Reservation(new UUID(0, 13), key, 1));
    }
    var owners = new java.util.ArrayList<DeviceRegistry.Stored>();
    for (int i = 0; i < 6; i++) {
      var ledger =
          new LedgerState(
              new UUID(2, i), ResourceKind.ITEM, 8192, false, 4096, 8192, stock, claims);
      owners.add(new DeviceRegistry.Stored(ledger, 1, null, false));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DeviceRegistry.restore(
                new DeviceRegistry.State(WORLD, owners), () -> {}, UUID::randomUUID));
  }
}
