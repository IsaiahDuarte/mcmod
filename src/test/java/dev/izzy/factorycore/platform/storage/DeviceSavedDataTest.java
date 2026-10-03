package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeviceSavedDataTest {
  @TempDir Path folder;
  private static HolderLookup.Provider registries;

  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
  }

  private DimensionDataStorage storage() {
    return new DimensionDataStorage(folder.toFile(), DataFixers.getDataFixer(), registries);
  }

  private Path file() {
    return folder.resolve(DeviceSavedData.NAME + ".dat");
  }

  @Test
  void committedRegistryFixtureKeepsExactLeaseAndClaims() throws Exception {
    CompoundTag fixture;
    try (var input = getClass().getResourceAsStream("/persistence/registry-v1.snbt")) {
      assertNotNull(input);
      fixture = TagParser.parseTag(new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
    var loaded = DeviceSavedData.load(fixture, registries);
    assertEquals("", loaded.diagnostic());
    var handle = new DeviceRegistry.Handle(new UUID(0, 11), new UUID(0, 1), 2);
    var location = new DeviceRegistry.Location("minecraft:overworld", 42, 0);
    assertEquals(DeviceRegistry.Failure.OK, loaded.registry().validate(handle));
    assertFalse(loaded.registry().stock(handle, ResourceKey.ENERGY).loaded());
    assertEquals(17, loaded.registry().stock(handle, ResourceKey.ENERGY).total());
    assertEquals(7, loaded.registry().stock(handle, ResourceKey.ENERGY).reserved());
    assertTrue(loaded.isDirty());
    var migrated = loaded.save(new CompoundTag(), registries);
    assertEquals(2, migrated.getInt("Schema"));
    assertEquals(fixture.get("World"), migrated.get("World"));
    assertEquals(fixture.get("Devices"), migrated.get("Devices"));
    assertTrue(migrated.getList("Networks", net.minecraft.nbt.Tag.TAG_COMPOUND).isEmpty());
    loaded.registry().availability(handle, location, true);
    assertEquals(
        7, loaded.registry().extract(handle, ResourceKey.ENERGY, 100, new UUID(0, 2)).amount());
  }

  @Test
  void realSavedDataDiskRoundTripKeepsOwnerStockAndReservationOffline() throws Exception {
    var storage = storage();
    var saved = DeviceSavedData.open(storage, file());
    var registry = saved.registry();
    var location = new DeviceRegistry.Location("minecraft:overworld", 42, 0);
    var portable = registry.create(ResourceKind.ENERGY, 1000, false, 8, 8).handle();
    var handle = registry.attach(portable, location).handle();
    registry.availability(handle, location, true);
    registry.insert(handle, ResourceKey.ENERGY, 500);
    UUID job = UUID.randomUUID();
    registry.reserve(handle, job, ResourceKey.ENERGY, 200);
    assertTrue(saved.isDirty());
    storage.save();
    IOUtilities.waitUntilIOWorkerComplete();
    assertTrue(Files.size(file()) > 0);
    var reloaded = DeviceSavedData.open(storage(), file());
    assertEquals("", reloaded.diagnostic());
    assertEquals(DeviceRegistry.Failure.OK, reloaded.registry().validate(handle));
    assertFalse(reloaded.registry().stock(handle, ResourceKey.ENERGY).loaded());
    assertEquals(500, reloaded.registry().stock(handle, ResourceKey.ENERGY).total());
    assertEquals(200, reloaded.registry().stock(handle, ResourceKey.ENERGY).reserved());
    reloaded.registry().availability(handle, location, true);
    assertEquals(200, reloaded.registry().extract(handle, ResourceKey.ENERGY, 300, job).amount());
    assertTrue(reloaded.isDirty());
  }

  @Test
  void incompatibleOrWrongTypedRegistryIsQuarantinedAndOriginalTagsArePreserved() {
    var saved = DeviceSavedData.open(storage(), file());
    CompoundTag original = saved.save(new CompoundTag(), registries);
    for (int schema : new int[] {0, 3}) {
      var future = original.copy();
      future.putInt("Schema", schema);
      var rejected = DeviceSavedData.load(future, registries);
      assertFalse(rejected.diagnostic().isEmpty());
      assertThrows(IllegalStateException.class, rejected::registry);
      assertEquals(future, rejected.save(new CompoundTag(), registries));
      assertFalse(rejected.isDirty());
      assertThrows(IllegalStateException.class, rejected::setDirty);
      assertFalse(rejected.isDirty());
    }
    original.putString("Devices", "bad");
    var rejected = DeviceSavedData.load(original, registries);
    assertThrows(IllegalStateException.class, rejected::registry);
    assertEquals(original, rejected.save(new CompoundTag(), registries));
  }

  @Test
  void minecraftSwallowedReadFailureDoesNotCreateOrOverwriteAnEmptyRegistry() throws Exception {
    byte[] corrupted = {1, 2, 3, 4};
    Files.write(file(), corrupted);
    assertThrows(IllegalStateException.class, () -> DeviceSavedData.open(storage(), file()));
    IOUtilities.waitUntilIOWorkerComplete();
    assertArrayEquals(corrupted, Files.readAllBytes(file()));
  }
}
