package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkPermissions.Actor;
import dev.izzy.factorycore.core.network.NetworkPermissions.Change;
import dev.izzy.factorycore.core.network.NetworkPermissions.Permission;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NetworkPersistenceTest {
  @TempDir Path folder;
  private static HolderLookup.Provider registries;
  private static final UUID OWNER = new UUID(0, 201);
  private static final UUID PRINCIPAL = new UUID(0, 202);
  private static final UUID NETWORK = new UUID(0, 101);

  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
  }

  private CompoundTag fixture() throws Exception {
    try (var input = getClass().getResourceAsStream("/persistence/registry-v2.snbt")) {
      assertNotNull(input);
      return TagParser.parseTag(new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  private DimensionDataStorage storage() {
    return new DimensionDataStorage(folder.toFile(), DataFixers.getDataFixer(), registries);
  }

  private CompoundTag network(CompoundTag tag) {
    return tag.getList("Networks", Tag.TAG_COMPOUND).getCompound(0);
  }

  private CompoundTag grant(CompoundTag tag) {
    return network(tag).getList("Grants", Tag.TAG_COMPOUND).getCompound(0);
  }

  private void rejected(CompoundTag tag) {
    var loaded = DeviceSavedData.load(tag, registries);
    assertFalse(loaded.diagnostic().isEmpty());
    assertThrows(IllegalStateException.class, loaded::registry);
    assertThrows(IllegalStateException.class, () -> loaded.network(NETWORK));
    assertThrows(IllegalStateException.class, () -> loaded.createNetwork(OWNER));
    assertThrows(IllegalStateException.class, loaded::setDirty);
    assertFalse(loaded.isDirty());
    assertEquals(tag, loaded.save(new CompoundTag(), registries));
  }

  @Test
  void fixedFixtureKeepsExactPermissionBitsAndPrivateDefaults() throws Exception {
    var tag = fixture();
    var loaded = DeviceSavedData.load(tag, registries);
    assertEquals("", loaded.diagnostic());
    assertFalse(loaded.isDirty());
    var permissions = loaded.network(NETWORK);
    assertEquals(3, permissions.snapshot().generation());
    assertEquals(OWNER, permissions.snapshot().owner());
    assertEquals(
        Set.of(Permission.VIEW, Permission.WITHDRAW, Permission.CRAFT),
        permissions.snapshot().grants().get(PRINCIPAL));
    for (var permission : Permission.values()) {
      assertTrue(permissions.allows(new Actor(OWNER, false), permission));
      assertFalse(permissions.allows(new Actor(new UUID(0, 203), false), permission));
      assertEquals(
          permission == Permission.MANAGE,
          permissions.allows(new Actor(new UUID(0, 203), true), permission));
    }
    assertThrows(IllegalArgumentException.class, () -> loaded.network(new UUID(0, 999)));
    assertEquals(1, loaded.principalBindings());
    assertEquals(tag, loaded.save(new CompoundTag(), registries));
    var all = Set.of(Permission.values());
    assertEquals(Change.OK, permissions.setGrants(new Actor(OWNER, false), PRINCIPAL, all));
    assertEquals(127, grant(loaded.save(new CompoundTag(), registries)).getInt("Permissions"));
    var bits =
        java.util.Map.of(
            Permission.VIEW,
            1,
            Permission.DEPOSIT,
            2,
            Permission.WITHDRAW,
            4,
            Permission.CRAFT,
            8,
            Permission.EDIT,
            16,
            Permission.DEPLOY,
            32,
            Permission.MANAGE,
            64);
    for (var permission : Permission.values()) {
      permissions.setGrants(new Actor(OWNER, false), PRINCIPAL, Set.of(permission));
      assertEquals(
          bits.get(permission).intValue(),
          grant(loaded.save(new CompoundTag(), registries)).getInt("Permissions"));
      var roundTrip = DeviceSavedData.load(loaded.save(new CompoundTag(), registries), registries);
      assertEquals(
          Set.of(permission), roundTrip.network(NETWORK).snapshot().grants().get(PRINCIPAL));
    }
  }

  @Test
  void realCompressedSaveRecoversTransferAndRevocation() throws Exception {
    var storage = storage();
    var saved = DeviceSavedData.load(fixture(), registries);
    storage.set(DeviceSavedData.NAME, saved);
    var permissions = saved.network(NETWORK);
    var owner = new Actor(OWNER, false);
    assertEquals(
        Change.OK,
        permissions.setGrants(owner, PRINCIPAL, Set.of(Permission.MANAGE, Permission.DEPOSIT)));
    assertEquals(Change.OK, permissions.transferOwnership(owner, PRINCIPAL));
    assertEquals(0, saved.principalBindings());
    assertFalse(permissions.allows(owner, Permission.VIEW));
    assertEquals(
        Change.OK,
        permissions.setGrants(new Actor(PRINCIPAL, false), OWNER, Set.of(Permission.VIEW)));
    storage.save();
    IOUtilities.waitUntilIOWorkerComplete();
    var reloaded = DeviceSavedData.open(storage(), folder.resolve(DeviceSavedData.NAME + ".dat"));
    assertEquals(
        saved.save(new CompoundTag(), registries), reloaded.save(new CompoundTag(), registries));
    var recovered = reloaded.network(NETWORK);
    assertEquals(PRINCIPAL, recovered.snapshot().owner());
    assertTrue(recovered.allows(owner, Permission.VIEW));
    assertFalse(recovered.allows(owner, Permission.DEPOSIT));
    assertEquals(Change.OK, recovered.setGrants(new Actor(PRINCIPAL, false), OWNER, Set.of()));
    assertTrue(reloaded.isDirty());
    var nextStorage = storage();
    nextStorage.set(DeviceSavedData.NAME, reloaded);
    nextStorage.save();
    IOUtilities.waitUntilIOWorkerComplete();
    var revoked = DeviceSavedData.open(storage(), folder.resolve(DeviceSavedData.NAME + ".dat"));
    assertFalse(revoked.network(NETWORK).allows(owner, Permission.VIEW));
    assertEquals(0, revoked.principalBindings());
    var handle =
        new dev.izzy.factorycore.core.storage.DeviceRegistry.Handle(
            new UUID(0, 11), new UUID(0, 1), 2);
    var stock =
        revoked.registry().stock(handle, dev.izzy.factorycore.core.resource.ResourceKey.ENERGY);
    assertEquals(17, stock.total());
    assertEquals(7, stock.reserved());
    assertFalse(stock.loaded());
  }

  @Test
  void malformedOrUnknownOwnedMetadataNeverGetsDiscarded() throws Exception {
    for (int mask : new int[] {0, -1, 128}) {
      var tag = fixture();
      grant(tag).putInt("Permissions", mask);
      rejected(tag);
    }
    var tag = fixture();
    grant(tag).putUUID("Principal", OWNER);
    rejected(tag);
    tag = fixture();
    network(tag).putLong("Generation", 0);
    rejected(tag);
    tag = fixture();
    network(tag).putString("Owner", "wrong type");
    rejected(tag);
    tag = fixture();
    network(tag).getList("Grants", Tag.TAG_COMPOUND).add(grant(tag).copy());
    rejected(tag);
    tag = fixture();
    tag.getList("Networks", Tag.TAG_COMPOUND).add(network(tag).copy());
    rejected(tag);
    tag = fixture();
    network(tag).putUUID("Id", new UUID(0, 1));
    rejected(tag);
    tag = fixture();
    tag.remove("Networks");
    rejected(tag);
    tag = fixture();
    network(tag).putString("FutureOwnership", "preserve me");
    rejected(tag);
    tag = fixture();
    grant(tag).putBoolean("Operator", true);
    rejected(tag);
    tag = fixture();
    tag.getList("Devices", Tag.TAG_COMPOUND)
        .getCompound(0)
        .putString("FutureLedger", "preserve me");
    rejected(tag);
    tag = fixture();
    tag.getList("Devices", Tag.TAG_COMPOUND)
        .getCompound(0)
        .getCompound("Location")
        .putString("FutureLease", "preserve me");
    rejected(tag);
    for (int schema : new int[] {1, 2}) {
      tag = fixture();
      tag.putInt("Schema", schema);
      tag.putString("FutureTable", "preserve me");
      rejected(tag);
    }
  }

  private CompoundTag emptyNetwork(int id, int principals) {
    var network = new CompoundTag();
    network.putUUID("Id", new UUID(1, id));
    network.putUUID("Owner", OWNER);
    network.putLong("Generation", 1);
    var grants = new ListTag();
    for (int j = 0; j < principals; j++) {
      var grant = new CompoundTag();
      grant.putUUID("Principal", new UUID(2, j));
      grant.putInt("Permissions", 1);
      grants.add(grant);
    }
    network.put("Grants", grants);
    return network;
  }

  @Test
  void worldBindingAdmissionRejectsBeforeMutationAndReclaimsSpace() throws Exception {
    var tag = fixture();
    var networks = new ListTag();
    for (int i = 0; i < 256; i++) networks.add(emptyNetwork(i, NetworkPermissions.MAX_PRINCIPALS));
    tag.put("Networks", networks);
    var loaded = DeviceSavedData.load(tag, registries);
    assertEquals("", loaded.diagnostic());
    assertEquals(DeviceSavedData.MAX_NETWORK_GRANTS, loaded.principalBindings());
    UUID id = loaded.createNetwork(OWNER);
    var permissions = loaded.network(id);
    var owner = new Actor(OWNER, false);
    loaded.setDirty(false);
    var before = permissions.snapshot();
    assertEquals(Change.LIMIT, permissions.setGrants(owner, PRINCIPAL, Set.of(Permission.VIEW)));
    assertEquals(before, permissions.snapshot());
    assertFalse(loaded.isDirty());
    var first = loaded.network(new UUID(1, 0));
    assertEquals(Change.OK, first.setGrants(owner, new UUID(2, 0), Set.of()));
    assertEquals(Change.OK, permissions.setGrants(owner, PRINCIPAL, Set.of(Permission.VIEW)));
    assertEquals(DeviceSavedData.MAX_NETWORK_GRANTS, loaded.principalBindings());
    assertEquals(Change.OK, permissions.transferOwnership(owner, PRINCIPAL));
    assertEquals(DeviceSavedData.MAX_NETWORK_GRANTS - 1, loaded.principalBindings());
    var restored = DeviceSavedData.load(loaded.save(new CompoundTag(), registries), registries);
    assertEquals(loaded.principalBindings(), restored.principalBindings());
    networks.add(emptyNetwork(257, 1));
    rejected(tag);
  }

  @Test
  void networkAndPerNetworkLimitsRejectWithoutResettingOwners() throws Exception {
    var tag = fixture();
    var networks = new ListTag();
    for (int i = 0; i < DeviceSavedData.MAX_NETWORKS; i++) networks.add(emptyNetwork(i, 0));
    tag.put("Networks", networks);
    var loaded = DeviceSavedData.load(tag, registries);
    assertEquals("", loaded.diagnostic());
    assertThrows(IllegalStateException.class, () -> loaded.createNetwork(OWNER));
    assertFalse(loaded.isDirty());
    assertEquals(tag, loaded.save(new CompoundTag(), registries));
    networks.add(emptyNetwork(DeviceSavedData.MAX_NETWORKS, 0));
    rejected(tag);
    tag.put("Networks", new ListTag());
    tag.getList("Networks", Tag.TAG_COMPOUND)
        .add(emptyNetwork(0, NetworkPermissions.MAX_PRINCIPALS + 1));
    rejected(tag);
  }
}
