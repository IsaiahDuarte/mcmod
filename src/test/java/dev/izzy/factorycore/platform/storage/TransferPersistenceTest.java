package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ResourcePort;
import dev.izzy.factorycore.core.resource.StagedTransfer;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TransferPersistenceTest {
  @TempDir Path folder;
  private static HolderLookup.Provider registries;

  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
  }

  @AfterEach
  void finishIoBeforeTemporaryDirectoryCleanup() {
    IOUtilities.waitUntilIOWorkerComplete();
  }

  private DimensionDataStorage storage() {
    return new DimensionDataStorage(folder.toFile(), DataFixers.getDataFixer(), registries);
  }

  private Path file() {
    return folder.resolve(DeviceSavedData.NAME + ".dat");
  }

  private CompoundTag fixture() throws Exception {
    try (var input = getClass().getResourceAsStream("/persistence/registry-v4.snbt")) {
      assertNotNull(input);
      return TagParser.parseTag(new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  private static final class Port implements ResourcePort {
    final DeviceRegistry registry;
    final DeviceRegistry.Handle handle;
    final long acceptance;

    Port(DeviceRegistry registry, DeviceRegistry.Handle handle, long acceptance) {
      this.registry = registry;
      this.handle = handle;
      this.acceptance = acceptance;
    }

    @Override
    public UUID backingId() {
      return handle.backing();
    }

    @Override
    public long simulateInsert(ResourceKey key, long amount) {
      return amount;
    }

    @Override
    public long extract(ResourceKey key, long amount) {
      return registry.extract(handle, key, amount, null).amount();
    }

    @Override
    public long insert(ResourceKey key, long amount) {
      return registry.insert(handle, key, Math.min(amount, acceptance)).amount();
    }
  }

  @Test
  void compressedCanonicalSaveKeepsDebitsCreditsAndKnownStagingTogether() {
    var storage = storage();
    var saved = DeviceSavedData.open(storage, file());
    var registry = saved.registry();
    var a = new DeviceRegistry.Location("minecraft:overworld", 41, 0);
    var b = new DeviceRegistry.Location("minecraft:overworld", 42, 0);
    var source =
        registry
            .attach(registry.create(ResourceKind.ENERGY, 1000, false, 1, 8).handle(), a)
            .handle();
    var destination =
        registry
            .attach(registry.create(ResourceKind.ENERGY, 1000, false, 1, 8).handle(), b)
            .handle();
    registry.availability(source, a, true);
    registry.availability(destination, b, true);
    registry.insert(source, ResourceKey.ENERGY, 100);
    var id = UUID.randomUUID();
    var owner = UUID.randomUUID();
    saved
        .transfers()
        .start(
            id,
            owner,
            ResourceKey.ENERGY,
            new Port(registry, source, 1000),
            new Port(registry, destination, 17),
            60,
            60,
            () -> true);
    assertEquals(43, saved.transfers().get(id).staged());
    storage.save();
    IOUtilities.waitUntilIOWorkerComplete();
    var loaded = DeviceSavedData.open(storage(), file());
    var restored = loaded.registry();
    assertEquals(40, restored.stock(source, ResourceKey.ENERGY).total());
    assertEquals(17, restored.stock(destination, ResourceKey.ENERGY).total());
    assertEquals(43, loaded.transfers().get(id).staged());
    assertEquals(owner, loaded.transfers().get(id).owner());
    assertFalse(restored.stock(source, ResourceKey.ENERGY).loaded());
    assertEquals(OperationResult.Reason.OFFLINE, loaded.transfers().deliver(id).reason());
    restored.availability(source, a, true);
    restored.availability(destination, b, true);
    loaded
        .transfers()
        .bind(
            id,
            new Port(restored, source, 1000),
            new Port(restored, destination, 1000),
            () -> true);
    assertEquals(43, loaded.transfers().deliver(id).amount());
    assertEquals(
        100,
        restored.stock(source, ResourceKey.ENERGY).total()
            + restored.stock(destination, ResourceKey.ENERGY).total());
    assertEquals(0, loaded.transfers().get(id).staged());
    loaded.transfers().retire(id);
    assertTrue(loaded.transfers().snapshot().isEmpty());
  }

  @Test
  void committedPendingCallFixtureKeepsExactEvidenceAndCannotRetireAsComplete() throws Exception {
    var original = fixture();
    var loaded = DeviceSavedData.load(original, registries);
    assertEquals("", loaded.diagnostic());
    assertTrue(loaded.isDirty());
    var migrated = loaded.save(new CompoundTag(), registries);
    assertEquals(DeviceSavedData.SCHEMA, migrated.getInt("Schema"));
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
    assertTrue(
        migrated
            .getCompound("ExternalClaims")
            .getList("Claims", net.minecraft.nbt.Tag.TAG_COMPOUND)
            .isEmpty());
    for (var entry : migrated.getList("Transfers", Tag.TAG_COMPOUND)) {
      var transfer = (CompoundTag) entry;
      assertEquals(2, transfer.getInt("Schema"));
      assertFalse(transfer.contains("Context"));
      transfer.putInt("Schema", 1);
    }
    migrated.remove("ExternalClaims");
    migrated.remove("RouteCursors");
    migrated.remove("Buffers");
    migrated.remove("MachinePresets");
    migrated.remove("CraftingJobs");
    migrated.putInt("Schema", 4);
    assertEquals(original, migrated);
    var record = loaded.transfers().get(new UUID(0, 401));
    assertEquals(12, record.staged());
    assertEquals(5, record.delivered());
    assertEquals(2, record.returned());
    assertEquals(StagedTransfer.PendingCall.DELIVER, record.pending());
    assertEquals(12, record.pendingAmount());
    assertEquals(
        OperationResult.Reason.OFFLINE, loaded.transfers().cancel(new UUID(0, 401)).reason());
    assertThrows(IllegalStateException.class, () -> loaded.transfers().retire(new UUID(0, 401)));
  }

  private void rejected(CompoundTag tag) {
    var loaded = DeviceSavedData.load(tag, registries);
    assertFalse(loaded.diagnostic().isEmpty());
    assertThrows(IllegalStateException.class, loaded::transfers);
    assertThrows(IllegalStateException.class, loaded::registry);
    assertThrows(IllegalStateException.class, loaded::setDirty);
    assertEquals(tag, loaded.save(new CompoundTag(), registries));
  }

  @Test
  void malformedFutureDuplicateAndOverflowedTransferDataPreservesEntireCanonicalSave()
      throws Exception {
    for (String mutation :
        new String[] {
          "{Schema:2}",
          "{Reason:NEW_REASON}",
          "{Staged:12}",
          "{Staged:-1L}",
          "{Staged:9223372036854775807L}",
          "{PendingAmount:11L}",
          "{Unknown:1}"
        }) {
      var tag = fixture();
      tag.getList("Transfers", Tag.TAG_COMPOUND).getCompound(0).merge(TagParser.parseTag(mutation));
      rejected(tag);
    }
    var tag = fixture();
    tag.getList("Transfers", Tag.TAG_COMPOUND)
        .add(tag.getList("Transfers", Tag.TAG_COMPOUND).getCompound(0).copy());
    rejected(tag);
    tag = fixture();
    tag.remove("Transfers");
    rejected(tag);
    tag = fixture();
    tag.putInt("Schema", DeviceSavedData.SCHEMA + 1);
    rejected(tag);
    tag = fixture();
    tag.getList("Transfers", Tag.TAG_COMPOUND).getCompound(0).remove("Owner");
    rejected(tag);
  }
}
