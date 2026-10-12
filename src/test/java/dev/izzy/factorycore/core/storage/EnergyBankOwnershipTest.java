package dev.izzy.factorycore.core.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EnergyBankOwnershipTest {
  private final DeviceRegistry registry =
      new DeviceRegistry(UUID.randomUUID(), () -> {}, UUID::randomUUID);
  private final EnergyBankOwnership ownership = new EnergyBankOwnership(registry);
  private final DeviceRegistry.Location first =
      new DeviceRegistry.Location("minecraft:overworld", 1, 0);
  private final DeviceRegistry.Location second =
      new DeviceRegistry.Location("minecraft:overworld", 2, 0);

  @Test
  void movementAndSequentialUpgradesKeepStockClaimsAndSharedTickRates() {
    var starter = EnergyBankTier.STARTER;
    var placed = ownership.place(null, starter, first);
    assertFalse(registry.stock(placed, ResourceKey.ENERGY).loaded());
    registry.availability(placed, first, true);
    var access = new EnergyBankAccess(registry, () -> 5);
    assertEquals(700, access.receive(placed, starter, 700, false).amount());
    var job = UUID.randomUUID();
    assertEquals(200, registry.reserve(placed, job, ResourceKey.ENERGY, 200).amount());
    var portable = ownership.remove(placed, starter, first);
    assertEquals(DeviceRegistry.Failure.STALE, registry.validate(placed));
    assertFalse(registry.stock(portable, ResourceKey.ENERGY).loaded());
    var expanded = ownership.upgrade(portable, starter, 1);
    assertEquals(portable.backing(), placed.backing());
    assertEquals(700, registry.stock(portable, ResourceKey.ENERGY).total());
    assertEquals(200, registry.stock(portable, ResourceKey.ENERGY).reserved());
    var moved = ownership.place(portable, expanded, second);
    registry.availability(moved, second, true);
    assertEquals(3300, access.receive(moved, expanded, 4000, false).amount());
    assertEquals(3800, access.extract(moved, expanded, 5000, false).amount());
    assertEquals(200, registry.stock(moved, ResourceKey.ENERGY).reserved());
    portable = ownership.remove(moved, expanded, second);
    assertEquals(EnergyBankTier.ADVANCED, ownership.upgrade(portable, expanded, 2));
    assertEquals(200, registry.stock(portable, ResourceKey.ENERGY).total());
  }

  @Test
  void copiedPlacedReferencesQuarantineRatherThanAdmitAnotherOwner() {
    var tier = EnergyBankTier.STARTER;
    var placed = ownership.place(null, tier, first);
    registry.availability(placed, first, true);
    registry.insert(placed, ResourceKey.ENERGY, 19);
    assertThrows(IllegalStateException.class, () -> ownership.place(placed, tier, second));
    assertEquals(DeviceRegistry.Failure.OK, registry.validate(placed));
    assertThrows(IllegalStateException.class, () -> ownership.reload(placed, tier, second));
    assertEquals(DeviceRegistry.Failure.CONFLICT, registry.validate(placed));
    assertEquals(
        19, registry.snapshot().devices().getFirst().ledger().contents().get(ResourceKey.ENERGY));
    assertTrue(registry.vacant(second));
  }

  @Test
  void restoredBankStaysOfflineAndReloadRejectsPortableAndForeignReferences() {
    var tier = EnergyBankTier.STARTER;
    var placed = ownership.place(null, tier, first);
    registry.availability(placed, first, true);
    registry.insert(placed, ResourceKey.ENERGY, 41);
    var restored = DeviceRegistry.restore(registry.snapshot(), () -> {}, UUID::randomUUID);
    var owner = new EnergyBankOwnership(restored);
    owner.reload(placed, tier, first);
    owner.reload(placed, tier, first);
    assertFalse(restored.stock(placed, ResourceKey.ENERGY).loaded());
    assertEquals(41, restored.stock(placed, ResourceKey.ENERGY).total());
    var portable = owner.remove(placed, tier, first);
    assertThrows(IllegalStateException.class, () -> owner.reload(portable, tier, first));
    var foreign =
        new DeviceRegistry.Handle(UUID.randomUUID(), portable.backing(), portable.generation());
    assertThrows(IllegalStateException.class, () -> owner.place(foreign, tier, first));
    assertEquals(41, restored.stock(portable, ResourceKey.ENERGY).total());
  }

  @Test
  void invalidTierUpgradeAndOccupiedPlacementRejectBeforeMutation() {
    var tier = EnergyBankTier.STARTER;
    var placed = ownership.place(null, tier, first);
    var before = registry.snapshot();
    assertThrows(IllegalStateException.class, () -> ownership.place(null, tier, first));
    assertThrows(IllegalStateException.class, () -> ownership.upgrade(placed, tier, 1));
    assertThrows(IllegalArgumentException.class, () -> ownership.upgrade(placed, tier, 2));
    assertThrows(
        IllegalStateException.class,
        () -> ownership.remove(placed, EnergyBankTier.EXPANDED, first));
    assertEquals(before, registry.snapshot());
    var wrong = registry.create(ResourceKind.ITEM, tier.capacity(), false, 8, 8).handle();
    var wrongBefore = registry.snapshot();
    assertThrows(IllegalStateException.class, () -> ownership.place(wrong, tier, second));
    assertEquals(wrongBefore, registry.snapshot());
  }
}
