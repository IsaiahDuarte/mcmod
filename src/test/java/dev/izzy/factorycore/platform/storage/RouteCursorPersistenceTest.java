package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.RouteCursors;
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

class RouteCursorPersistenceTest {
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

  private CompoundTag fixture(String name) throws Exception {
    try (var input = getClass().getResourceAsStream("/persistence/" + name + ".snbt")) {
      assertNotNull(input);
      return TagParser.parseTag(new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void nestedFixtureKeepsExactScopeAndLastBackingWithoutAvailability() throws Exception {
    var original = fixture("route-cursors-v1");
    var decoded = RouteCursorCodec.decode(original);
    assertEquals(1, decoded.size());
    assertEquals(new UUID(0, 503), decoded.getFirst().last());
    assertEquals(ResourceKind.ITEM, decoded.getFirst().scope().kind());
    assertTrue(decoded.getFirst().scope().toStorage());
    assertEquals(original, RouteCursorCodec.encode(decoded));
  }

  @Test
  void schemaSixMigrationPreservesAllOwnershipTablesAndAddsOnlyEmptyCursors() throws Exception {
    var original = fixture("registry-v6");
    var restored = DeviceSavedData.load(original, registries);
    assertEquals("", restored.diagnostic());
    assertTrue(restored.isDirty());
    assertTrue(restored.routeCursors().snapshot().isEmpty());
    var migrated = restored.save(new CompoundTag(), registries);
    assertEquals(11, migrated.getInt("Schema"));
    assertTrue(
        migrated
            .getCompound("Buffers")
            .getList("Buffers", net.minecraft.nbt.Tag.TAG_COMPOUND)
            .isEmpty());
    assertTrue(migrated.getCompound("RouteCursors").getList("Cursors", Tag.TAG_COMPOUND).isEmpty());
    migrated.remove("RouteCursors");
    migrated.remove("Buffers");
    migrated.putInt("Schema", 6);
    assertEquals(original, migrated);
  }

  @Test
  void compressedSaveReloadRetainsRotationAndDirtyOnlyOnChange() throws Exception {
    var storage =
        new DimensionDataStorage(
            directory.toFile(), net.minecraft.util.datafix.DataFixers.getDataFixer(), registries);
    var file = directory.resolve(DeviceSavedData.NAME + ".dat");
    var saved = DeviceSavedData.open(storage, file);
    var actor = new UUID(0, 201);
    var network = saved.createNetwork(actor);
    var scope =
        new RouteCursors.Scope(
            network, network, actor, new UUID(0, 501), new UUID(0, 502), ResourceKind.ITEM, true);
    var last = new UUID(0, 503);
    saved.setDirty(false);
    assertTrue(saved.routeCursors().advance(scope, last));
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
    assertEquals(last, restored.routeCursors().last(scope));
    assertFalse(restored.isDirty());
    restored.routeCursors().advance(scope, last);
    assertFalse(restored.isDirty());
    restored.routeCursors().advance(scope, new UUID(0, 504));
    assertTrue(restored.isDirty());
    assertTrue(restored.routeCursors().forget(scope));
    assertNull(restored.routeCursors().last(scope));
  }

  @Test
  void malformedFutureDuplicateUnknownNetworkAndLimitsPreserveWholeAuthority() throws Exception {
    var baseline = fixture("registry-v6");
    baseline.putInt("Schema", 7);
    baseline.put("RouteCursors", fixture("route-cursors-v1"));
    assertEquals("", DeviceSavedData.load(baseline, registries).diagnostic());
    for (int mode = 0; mode < 9; mode++) {
      var tag = baseline.copy();
      var data = tag.getCompound("RouteCursors");
      var list = data.getList("Cursors", Tag.TAG_COMPOUND);
      var first = list.getCompound(0);
      if (mode == 0) data.putInt("Schema", 2);
      if (mode == 1) data.putString("Future", "retain");
      if (mode == 2) list.add(first.copy());
      if (mode == 3) first.putInt("ToStorage", 1);
      if (mode == 4) first.putByte("ToStorage", (byte) 2);
      if (mode == 5) first.putUUID("Network", new UUID(0, 999));
      if (mode == 6) tag.remove("RouteCursors");
      if (mode == 7) first.putString("Kind", "CHEMICAL");
      if (mode == 8) {
        for (int index = 1; index <= RouteCursors.MAX_CURSORS; index++) {
          var entry = first.copy();
          entry.putUUID("Deployment", new UUID(0, 2000 + index));
          list.add(entry);
        }
      }
      var rejected = DeviceSavedData.load(tag, registries);
      assertThrows(IllegalStateException.class, rejected::routeCursors);
      assertEquals(tag, rejected.save(new CompoundTag(), registries));
      assertFalse(rejected.isDirty());
    }
  }
}
