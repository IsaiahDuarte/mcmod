package dev.izzy.factorycore.core.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EnergyBankAccessTest {
  private static final DeviceRegistry.Location FIRST =
      new DeviceRegistry.Location("minecraft:overworld", 1, 0);
  private static final DeviceRegistry.Location SECOND =
      new DeviceRegistry.Location("minecraft:overworld", 2, 0);

  private static DeviceRegistry registry() {
    return new DeviceRegistry(UUID.randomUUID(), () -> {}, UUID::randomUUID);
  }

  private static DeviceRegistry.Handle placed(DeviceRegistry registry, EnergyBankTier tier) {
    var handle =
        registry
            .attach(
                registry.create(ResourceKind.ENERGY, tier.capacity(), false, 8, 64).handle(), FIRST)
            .handle();
    registry.availability(handle, FIRST, true);
    return handle;
  }

  @Test
  void combinedCallsSimulationAndDirectionsShareOneIdentityBudget() {
    var registry = registry();
    var handle = placed(registry, EnergyBankTier.STARTER);
    long[] tick = {1};
    var access = new EnergyBankAccess(registry, () -> tick[0]);
    assertEquals(
        1000, access.receive(handle, EnergyBankTier.STARTER, Long.MAX_VALUE, true).amount());
    assertEquals(
        1000, access.receive(handle, EnergyBankTier.STARTER, Long.MAX_VALUE, true).amount());
    assertEquals(600, access.receive(handle, EnergyBankTier.STARTER, 600, false).amount());
    assertEquals(
        new OperationResult(400, OperationResult.Reason.RATE_LIMIT),
        access.receive(handle, EnergyBankTier.STARTER, 800, false));
    assertEquals(
        OperationResult.Reason.RATE_LIMIT,
        access.receive(handle, EnergyBankTier.STARTER, 1, false).reason());
    assertEquals(1000, access.extract(handle, EnergyBankTier.STARTER, 1000, true).amount());
    assertEquals(1000, access.extract(handle, EnergyBankTier.STARTER, 1000, false).amount());
    assertEquals(0, registry.stock(handle, ResourceKey.ENERGY).total());
    tick[0]++;
    assertEquals(1000, access.receive(handle, EnergyBankTier.STARTER, 1000, false).amount());
  }

  @Test
  void movedLeasesAndTierChangesDoNotResetSpentEnergy() {
    var registry = registry();
    var handle = placed(registry, EnergyBankTier.STARTER);
    var access = new EnergyBankAccess(registry, () -> 1);
    access.receive(handle, EnergyBankTier.STARTER, 1000, false);
    var job = UUID.randomUUID();
    registry.reserve(handle, job, ResourceKey.ENERGY, 800);
    var portable = registry.detach(handle, FIRST).handle();
    assertEquals(
        OperationResult.Reason.DENIED,
        access.receive(handle, EnergyBankTier.STARTER, 1, false).reason());
    registry.resize(portable, EnergyBankTier.EXPANDED.capacity(), false);
    var moved = registry.attach(portable, SECOND).handle();
    registry.availability(moved, SECOND, true);
    assertEquals(3000, access.receive(moved, EnergyBankTier.EXPANDED, 4000, false).amount());
    assertEquals(3200, access.extract(moved, EnergyBankTier.EXPANDED, 4000, false).amount());
    assertEquals(800, registry.stock(moved, ResourceKey.ENERGY).reserved());
    assertEquals(800, registry.stock(moved, ResourceKey.ENERGY).total());
    assertThrows(
        IllegalArgumentException.class,
        () -> access.receive(moved, EnergyBankTier.STARTER, 1, false));
    assertThrows(IllegalArgumentException.class, () -> EnergyBankTier.ADVANCED.upgrade(3));
    assertEquals(EnergyBankTier.EXPANDED, EnergyBankTier.STARTER.upgrade(1));
  }

  @Test
  void fullOfflineReservedAndBackwardsClockNeverLoseEnergy() {
    var registry = registry();
    var handle = placed(registry, EnergyBankTier.STARTER);
    registry.insert(handle, ResourceKey.ENERGY, 99950);
    long[] tick = {10};
    var access = new EnergyBankAccess(registry, () -> tick[0]);
    assertEquals(
        new OperationResult(50, OperationResult.Reason.FULL),
        access.receive(handle, EnergyBankTier.STARTER, 1000, false));
    var job = UUID.randomUUID();
    registry.reserve(handle, job, ResourceKey.ENERGY, 100000);
    assertEquals(
        OperationResult.Reason.RESERVED,
        access.extract(handle, EnergyBankTier.STARTER, 1000, false).reason());
    registry.routing(location -> false);
    assertEquals(
        OperationResult.Reason.OFFLINE,
        access.receive(handle, EnergyBankTier.STARTER, 1, false).reason());
    assertEquals(
        OperationResult.Reason.OFFLINE,
        access.extract(handle, EnergyBankTier.STARTER, 1, false).reason());
    tick[0] = 9;
    assertThrows(
        IllegalStateException.class,
        () -> access.extract(handle, EnergyBankTier.STARTER, 1, false));
    assertEquals(100000, registry.stock(handle, ResourceKey.ENERGY).total());
    assertEquals(100000, registry.release(handle, job, ResourceKey.ENERGY).amount());
  }

  @Test
  void reloadedCanonicalStateStartsOfflineAndKeepsExactReservations() {
    var registry = registry();
    var handle = placed(registry, EnergyBankTier.ADVANCED);
    var access = new EnergyBankAccess(registry, () -> 1);
    access.receive(handle, EnergyBankTier.ADVANCED, 16000, false);
    var job = UUID.randomUUID();
    registry.reserve(handle, job, ResourceKey.ENERGY, 9000);
    var loaded = DeviceRegistry.restore(registry.snapshot(), () -> {}, UUID::randomUUID);
    var restored = new EnergyBankAccess(loaded, () -> 1);
    assertEquals(
        OperationResult.Reason.OFFLINE,
        restored.extract(handle, EnergyBankTier.ADVANCED, 16000, false).reason());
    loaded.availability(handle, FIRST, true);
    assertEquals(7000, restored.extract(handle, EnergyBankTier.ADVANCED, 16000, false).amount());
    assertEquals(9000, loaded.stock(handle, ResourceKey.ENERGY).total());
  }

  @Test
  void wrongResourceAndMalformedRequestsRejectWithoutChangingStock() {
    var registry = registry();
    var handle = placed(registry, EnergyBankTier.STARTER);
    var item =
        registry
            .attach(registry.create(ResourceKind.ITEM, 100000, false, 8, 64).handle(), SECOND)
            .handle();
    registry.availability(item, SECOND, true);
    var access = new EnergyBankAccess(registry, () -> 1);
    assertEquals(
        OperationResult.Reason.WRONG_RESOURCE,
        access.receive(item, EnergyBankTier.STARTER, 1000, false).reason());
    assertThrows(
        IllegalArgumentException.class,
        () -> access.receive(handle, EnergyBankTier.STARTER, -1, false));
    assertThrows(IllegalArgumentException.class, () -> EnergyBankTier.STARTER.upgrade(2));
    assertThrows(IllegalArgumentException.class, () -> EnergyBankTier.EXPANDED.upgrade(0));
    var badClock = new EnergyBankAccess(registry, () -> -1);
    assertThrows(
        IllegalStateException.class,
        () -> badClock.receive(handle, EnergyBankTier.STARTER, 1000, false));
    assertEquals(0, registry.stock(handle, ResourceKey.ENERGY).total());
    assertEquals(1000, access.receive(handle, EnergyBankTier.STARTER, 1000, false).amount());
  }

  @Test
  void generatedOperationsConserveEnergyAndNeverSpendMoreThanTickRates() {
    long seed = 0xE0B001L;
    var random = new Random(seed);
    var registry = registry();
    var handle = placed(registry, EnergyBankTier.STARTER);
    long[] tick = {0};
    var access = new EnergyBankAccess(registry, () -> tick[0]);
    long total = 0, received = 0, extracted = 0;
    for (int step = 0; step < 4000; step++) {
      if (random.nextInt(5) == 0) {
        tick[0]++;
        received = 0;
        extracted = 0;
      }
      boolean input = random.nextBoolean(), simulate = random.nextInt(3) == 0;
      long amount = random.nextInt(3000);
      var result =
          input
              ? access.receive(handle, EnergyBankTier.STARTER, amount, simulate)
              : access.extract(handle, EnergyBankTier.STARTER, amount, simulate);
      if (!simulate) {
        total += input ? result.amount() : -result.amount();
        if (input) received += result.amount();
        else extracted += result.amount();
      }
      assertEquals(
          total,
          registry.stock(handle, ResourceKey.ENERGY).total(),
          "seed=" + seed + " step=" + step);
      assertTrue(received <= 1000 && extracted <= 1000, "seed=" + seed + " step=" + step);
    }
  }
}
