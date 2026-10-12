package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class ExternalProviderIdentityTest {
  private static final UUID WORLD = new UUID(0, 11);

  @Test
  void strictMarkersPreserveForeignFutureAndMalformedEvidence() {
    var id = UUID.randomUUID();
    var marker = ExternalProviderIdentity.write(WORLD, id);
    assertEquals(id, ExternalProviderIdentity.read(marker, WORLD));
    assertNull(ExternalProviderIdentity.read(null, WORLD));
    var before = marker.copy();
    assertThrows(
        IllegalArgumentException.class,
        () -> ExternalProviderIdentity.read(marker, UUID.randomUUID()));
    assertEquals(before, marker);
    marker.putInt("Schema", 2);
    var future = marker.copy();
    assertThrows(
        IllegalArgumentException.class, () -> ExternalProviderIdentity.read(marker, WORLD));
    assertEquals(future, marker);
    marker.putInt("Schema", 1);
    marker.putString("Extra", "keep");
    assertThrows(
        IllegalArgumentException.class, () -> ExternalProviderIdentity.read(marker, WORLD));
    var malformed = new CompoundTag();
    malformed.putInt("Schema", 1);
    malformed.putUUID("World", WORLD);
    malformed.putString("Id", id.toString());
    assertThrows(
        IllegalArgumentException.class, () -> ExternalProviderIdentity.read(malformed, WORLD));
  }

  @Test
  void groupIdentityIsStableOrderedKindScopedAndRejectsCopiedMembers() {
    var first = UUID.randomUUID();
    var second = UUID.randomUUID();
    var group = ExternalProviderIdentity.group(WORLD, ResourceKind.ITEM, List.of(first, second));
    assertEquals(
        group, ExternalProviderIdentity.group(WORLD, ResourceKind.ITEM, List.of(second, first)));
    assertNotEquals(
        group, ExternalProviderIdentity.group(WORLD, ResourceKind.ENERGY, List.of(first, second)));
    assertNotEquals(
        group,
        ExternalProviderIdentity.group(
            UUID.randomUUID(), ResourceKind.ITEM, List.of(first, second)));
    assertNotEquals(
        group, ExternalProviderIdentity.group(WORLD, ResourceKind.ITEM, List.of(first)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ExternalProviderIdentity.group(WORLD, ResourceKind.ITEM, List.of(first, first)));
    var oversized = new ArrayList<UUID>();
    for (int index = 0; index < 65; index++) oversized.add(UUID.randomUUID());
    assertThrows(
        IllegalArgumentException.class,
        () -> ExternalProviderIdentity.group(WORLD, ResourceKind.ITEM, oversized));
  }
}
