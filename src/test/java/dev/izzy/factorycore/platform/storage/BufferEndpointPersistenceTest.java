package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.*;
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

class BufferEndpointPersistenceTest {
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
  void contextTwoFixtureKeepsBufferIdentityAndRejectsOldSchemaOrConfiguration() throws Exception {
    var original = fixture("transfer-buffer-context-v2");
    var context = TransferContextCodec.decode(original);
    assertEquals(TransferContext.Type.BUFFER, context.source().type());
    assertEquals(context.source().device(), context.source().backing());
    assertEquals(2, context.source().configuration());
    assertEquals(original, TransferContextCodec.encode(context));
    var wrongKind =
        new StagedTransfer.Snapshot(
            UUID.randomUUID(),
            ResourceKey.ENERGY,
            context.source().backing(),
            context.destination().backing(),
            0,
            0,
            0,
            StagedTransfer.State.COMPLETE,
            OperationResult.Reason.OK,
            "",
            StagedTransfer.PendingCall.NONE,
            0);
    assertThrows(IllegalArgumentException.class, () -> context.validate(wrongKind));
    for (int mode = 0; mode < 4; mode++) {
      var invalid = original.copy();
      if (mode == 0) invalid.putInt("Schema", 1);
      if (mode == 1) invalid.putInt("Schema", 3);
      if (mode == 2) invalid.getCompound("Source").putLong("Configuration", 0);
      if (mode == 3) invalid.getCompound("Source").putUUID("Device", new UUID(0, 999));
      var saved = invalid.copy();
      assertThrows(IllegalArgumentException.class, () -> TransferContextCodec.decode(invalid));
      assertEquals(saved, invalid);
    }
  }

  @Test
  void schemaEightMigrationChangesOnlyGlobalVersionAndPreservesNonemptyOwnedTables()
      throws Exception {
    var original = fixture("registry-v8");
    var restored = DeviceSavedData.load(original, registries);
    assertEquals("", restored.diagnostic());
    assertTrue(restored.isDirty());
    assertEquals(23, restored.buffers().snapshot().getFirst().slots().getFirst().count());
    var migrated = restored.save(new CompoundTag(), registries);
    assertEquals(DeviceSavedData.SCHEMA, migrated.getInt("Schema"));
    assertTrue(
        migrated
            .getCompound("MachinePresets")
            .getList("Deployments", net.minecraft.nbt.Tag.TAG_COMPOUND)
            .isEmpty());
    migrated.remove("MachinePresets");
    migrated.remove("CraftingJobs");
    migrated.putInt("Schema", 8);
    assertEquals(original, migrated);
    var unsupported = original.copy();
    var context = fixture("transfer-buffer-context-v2");
    // Keep the transfer's existing backing evidence aligned; only the unsupported union changes.
    context.getCompound("Source").putUUID("Device", new UUID(0, 301));
    context.getCompound("Source").putUUID("Backing", new UUID(0, 301));
    context.getCompound("Destination").putUUID("Device", new UUID(0, 302));
    context.getCompound("Destination").putUUID("Backing", new UUID(0, 302));
    unsupported.getList("Transfers", Tag.TAG_COMPOUND).getCompound(0).put("Context", context);
    unsupported.getList("Transfers", Tag.TAG_COMPOUND).getCompound(0).putString("Kind", "ITEM");
    unsupported
        .getList("Transfers", Tag.TAG_COMPOUND)
        .getCompound(0)
        .putString("Registry", "minecraft:iron_ingot");
    var rejected = DeviceSavedData.load(unsupported, registries);
    assertThrows(IllegalStateException.class, rejected::transfers);
    assertEquals(unsupported, rejected.save(new CompoundTag(), registries));
  }

  @Test
  void compressedCanonicalReloadKeepsSourceSlotsAndOwnedStagingWithoutRepeatExtraction() {
    var storage = storage();
    var file = directory.resolve(DeviceSavedData.NAME + ".dat");
    var saved = DeviceSavedData.open(storage, file);
    var actor = UUID.randomUUID();
    var network = saved.createNetwork(actor);
    var position = new NodeRegistry.Position("minecraft:overworld", 42);
    var handle = saved.createBuffer(position);
    var owner = new Object();
    saved.buffers().stackLimit(key -> 64);
    assertTrue(saved.buffers().bind(handle, position, owner));
    var buffer = saved.buffers().inventory(handle, owner);
    var key = new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[] {1, 2});
    buffer.insert(key, 64);
    var target = UUID.randomUUID();
    var id = UUID.randomUUID();
    var context =
        new TransferContext(
            saved.registry().world(),
            network,
            network,
            position,
            new TransferContext.Endpoint(
                TransferContext.Type.BUFFER, handle.id(), handle.id(), handle.generation(), 1),
            new TransferContext.Endpoint(TransferContext.Type.DIGITAL, target, target, 1, 0));
    ResourcePort blocked =
        new ResourcePort() {
          public UUID backingId() {
            return target;
          }

          public long simulateInsert(ResourceKey resource, long amount) {
            return amount;
          }

          public long insert(ResourceKey resource, long amount) {
            return 0;
          }

          public long extract(ResourceKey resource, long amount) {
            throw new IllegalStateException("Fixture target does not extract");
          }
        };
    saved.transfers().start(id, actor, key, buffer, blocked, 64, 64, () -> true, context);
    assertEquals(0, buffer.total(key));
    assertEquals(64, saved.transfers().get(id).staged());
    storage.save();
    net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
    var restored = DeviceSavedData.open(storage(), file);
    assertEquals(context, restored.transfers().context(id));
    assertEquals(64, restored.transfers().get(id).staged());
    assertTrue(
        restored.buffers().snapshot().getFirst().slots().stream()
            .allMatch(slot -> slot.count() == 0));
    assertEquals(OperationResult.Reason.OFFLINE, restored.transfers().deliver(id).reason());
    assertFalse(restored.buffers().usable(handle, owner));
    assertFalse(restored.isDirty());
  }

  private DimensionDataStorage storage() {
    return new DimensionDataStorage(
        directory.toFile(), net.minecraft.util.datafix.DataFixers.getDataFixer(), registries);
  }
}
