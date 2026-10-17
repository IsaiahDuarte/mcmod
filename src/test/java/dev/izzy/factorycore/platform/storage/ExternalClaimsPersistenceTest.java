package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalClaimsPersistenceTest {
  @TempDir Path directory;
  private static RegistryAccess registries;

  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
  }

  @org.junit.jupiter.api.AfterEach
  void finishIo() {
    net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
  }

  private CompoundTag fixture() throws Exception {
    try (var input = getClass().getResourceAsStream("/persistence/external-claims-v1.snbt")) {
      assertNotNull(input);
      return TagParser.parseTag(new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void fixedFixturePreservesExactOrphanHoldsAndDeterministicEncoding() throws Exception {
    var original = fixture();
    var claims = ExternalClaimsCodec.decode(original);
    assertEquals(2, claims.size());
    assertTrue(claims.stream().anyMatch(entry -> entry.amount() == 9007199254740993L));
    assertEquals(original, ExternalClaimsCodec.encode(claims));
  }

  @Test
  void canonicalDiskReloadPreservesClaimsWithoutObservedStockOrAutomaticRelease() throws Exception {
    var storage =
        new DimensionDataStorage(
            directory.toFile(), net.minecraft.util.datafix.DataFixers.getDataFixer(), registries);
    var file = directory.resolve(DeviceSavedData.NAME + ".dat");
    var saved = DeviceSavedData.open(storage, file);
    var backing = new UUID(0, 501);
    var job = new UUID(0, 502);
    var key = new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[] {1, 2});
    saved.setDirty(false);
    assertEquals(23, saved.externalClaims().reserve(backing, job, key, 23, 100).amount());
    assertTrue(saved.isDirty());
    storage.save();
    net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
    var restored =
        DeviceSavedData.open(
            new DimensionDataStorage(
                directory.toFile(),
                net.minecraft.util.datafix.DataFixers.getDataFixer(),
                registries),
            file);
    assertEquals(23, restored.externalClaims().held(backing, job, key));
    assertEquals(0, restored.externalClaims().available(backing, job, key, 0));
    assertFalse(restored.isDirty());
    restored.externalClaims().consume(backing, job, key, 7);
    assertTrue(restored.isDirty());
    assertEquals(16, restored.externalClaims().held(backing, job, key));
    assertEquals(16, restored.externalClaims().release(backing, job, key));
    assertTrue(restored.externalClaims().snapshot().isEmpty());
  }

  @Test
  void schemaFiveMigrationAddsOnlyEmptyHoldsAndRetainsExistingTables() {
    CompoundTag original;
    try (var input = getClass().getResourceAsStream("/persistence/registry-v5.snbt")) {
      assertNotNull(input);
      original = TagParser.parseTag(new String(input.readAllBytes(), StandardCharsets.UTF_8));
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
    var restored = DeviceSavedData.load(original, registries);
    assertEquals("", restored.diagnostic());
    assertTrue(restored.isDirty());
    var migrated = restored.save(new CompoundTag(), registries);
    assertEquals(11, migrated.getInt("Schema"));
    assertTrue(
        migrated
            .getCompound("Buffers")
            .getList("Buffers", net.minecraft.nbt.Tag.TAG_COMPOUND)
            .isEmpty());
    assertTrue(
        migrated
            .getCompound("RouteCursors")
            .getList("Cursors", net.minecraft.nbt.Tag.TAG_COMPOUND)
            .isEmpty());
    assertTrue(restored.externalClaims().snapshot().isEmpty());
    migrated.remove("ExternalClaims");
    migrated.remove("RouteCursors");
    migrated.remove("Buffers");
    migrated.putInt("Schema", 5);
    assertEquals(original, migrated);
  }

  @Test
  void malformedFutureDuplicateAndOverflowHoldsPreserveWholeRejectedAuthority() throws Exception {
    var saved =
        DeviceSavedData.open(
            new DimensionDataStorage(
                directory.toFile(),
                net.minecraft.util.datafix.DataFixers.getDataFixer(),
                registries),
            directory.resolve(DeviceSavedData.NAME + ".dat"));
    for (int mode = 0; mode < 6; mode++) {
      var tag = saved.save(new CompoundTag(), registries);
      var data = fixture();
      if (mode == 0) data.putInt("Schema", 2);
      if (mode == 1) data.putString("Future", "retain raw");
      if (mode == 2)
        data.getList("Claims", Tag.TAG_COMPOUND)
            .add(data.getList("Claims", Tag.TAG_COMPOUND).getCompound(0).copy());
      if (mode == 3) data.getList("Claims", Tag.TAG_COMPOUND).getCompound(0).putInt("Amount", 23);
      if (mode == 4) data.getList("Claims", Tag.TAG_COMPOUND).getCompound(0).putLong("Amount", 0);
      if (mode == 5) {
        var first = data.getList("Claims", Tag.TAG_COMPOUND).getCompound(0);
        first.putLong("Amount", Long.MAX_VALUE);
        var other = first.copy();
        other.putUUID("Owner", UUID.randomUUID());
        other.putLong("Amount", 1);
        data.getList("Claims", Tag.TAG_COMPOUND).add(other);
      }
      tag.put("ExternalClaims", data);
      var rejected = DeviceSavedData.load(tag, registries);
      assertThrows(IllegalStateException.class, rejected::externalClaims);
      assertEquals(tag, rejected.save(new CompoundTag(), registries));
      assertFalse(rejected.isDirty());
    }
  }

  @Test
  void ownedBackingCollisionRejectsWithoutDroppingForeignEvidence() throws Exception {
    var saved =
        DeviceSavedData.open(
            new DimensionDataStorage(
                directory.toFile(),
                net.minecraft.util.datafix.DataFixers.getDataFixer(),
                registries),
            directory.resolve(DeviceSavedData.NAME + ".dat"));
    var root = saved.createNetwork(UUID.randomUUID());
    var data = fixture();
    data.getList("Claims", Tag.TAG_COMPOUND).getCompound(0).putUUID("Backing", root);
    var tag = saved.save(new CompoundTag(), registries);
    tag.put("ExternalClaims", data);
    var rejected = DeviceSavedData.load(tag, registries);
    assertThrows(IllegalStateException.class, rejected::externalClaims);
    assertEquals(tag, rejected.save(new CompoundTag(), registries));
  }

  @Test
  void excessiveMetadataRejectsWholeAuthorityAndPreservesRawTable() throws Exception {
    var saved =
        DeviceSavedData.open(
            new DimensionDataStorage(
                directory.toFile(),
                net.minecraft.util.datafix.DataFixers.getDataFixer(),
                registries),
            directory.resolve(DeviceSavedData.NAME + ".dat"));
    var data = fixture();
    var template = data.getList("Claims", Tag.TAG_COMPOUND).getCompound(0).copy();
    template.putByteArray("Components", new byte[65536]);
    var list = new ListTag();
    for (int index = 0; index < 64; index++) {
      var entry = template.copy();
      entry.putUUID("Owner", new UUID(0, 1000 + index));
      list.add(entry);
    }
    data.put("Claims", list);
    var tag = saved.save(new CompoundTag(), registries);
    tag.put("ExternalClaims", data);
    var rejected = DeviceSavedData.load(tag, registries);
    assertThrows(IllegalStateException.class, rejected::externalClaims);
    assertEquals(tag, rejected.save(new CompoundTag(), registries));
  }
}
