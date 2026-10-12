package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.storage.BufferRegistry;
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

class BufferPersistenceTest {
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

  private DeviceSavedData fresh(DimensionDataStorage storage) {
    return DeviceSavedData.open(storage, directory.resolve(DeviceSavedData.NAME + ".dat"));
  }

  private DimensionDataStorage storage() {
    return new DimensionDataStorage(
        directory.toFile(), net.minecraft.util.datafix.DataFixers.getDataFixer(), registries);
  }

  @Test
  void fixedNestedFixtureRetainsPhysicalSlotsExactComponentsAndLimits() throws Exception {
    var original = fixture("buffers-v1");
    var states = BufferStateCodec.decode(original);
    assertEquals(1, states.size());
    var buffer = states.getFirst();
    assertEquals(new UUID(0, 601), buffer.backing());
    assertEquals(23, buffer.slots().get(0).count());
    assertArrayEquals(new byte[] {1, 2}, buffer.slots().get(0).key().components());
    assertEquals(1, buffer.slots().get(8).limit());
    assertNull(buffer.slots().get(4).key());
    assertEquals(original, BufferStateCodec.encode(states));
  }

  @Test
  void schemaSevenMigrationAddsOnlyEmptyBuffersAndRetainsAllPreviousOwnership() throws Exception {
    var original = fixture("registry-v7");
    var restored = DeviceSavedData.load(original, registries);
    assertEquals("", restored.diagnostic());
    assertTrue(restored.isDirty());
    assertTrue(restored.buffers().snapshot().isEmpty());
    var migrated = restored.save(new CompoundTag(), registries);
    assertEquals(9, migrated.getInt("Schema"));
    migrated.remove("Buffers");
    migrated.putInt("Schema", 7);
    assertEquals(original, migrated);
  }

  @Test
  void compressedReloadPreservesSlotsAndMovementRequiresNewCurrentBinding() {
    var storage = storage();
    var saved = fresh(storage);
    var initial = new NodeRegistry.Position("minecraft:overworld", 42);
    var movedPosition = new NodeRegistry.Position("minecraft:overworld", 43);
    var handle = saved.createBuffer(initial);
    saved.buffers().stackLimit(key -> 64);
    var owner = new Object();
    assertTrue(saved.buffers().bind(handle, initial, owner));
    var slotState = BufferStateCodec.decode(readFixture()).getFirst().slots().getFirst();
    saved.setDirty(false);
    saved.buffers().inventory(handle, owner).insertSlot(8, slotState.key(), 23, false);
    assertTrue(saved.isDirty());
    storage.save();
    net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
    var restored = fresh(storage());
    assertFalse(restored.isDirty());
    assertFalse(restored.buffers().usable(handle, owner));
    assertThrows(IllegalStateException.class, () -> restored.buffers().inventory(handle, owner));
    restored.buffers().stackLimit(key -> 64);
    var reloadedOwner = new Object();
    assertTrue(restored.buffers().bind(handle, initial, reloadedOwner));
    assertEquals(23, restored.buffers().inventory(handle, reloadedOwner).slot(8).count());
    restored.buffers().unbind(handle, reloadedOwner);
    var portable = restored.nodes().detach(handle, initial).handle();
    var moved = restored.nodes().attach(portable, movedPosition).handle();
    assertFalse(restored.buffers().bind(handle, movedPosition, owner));
    assertTrue(restored.buffers().bind(moved, movedPosition, reloadedOwner));
    assertEquals(23, restored.buffers().inventory(moved, reloadedOwner).total(slotState.key()));
    assertEquals(NodeRegistry.Failure.STALE, restored.nodes().validate(handle));
  }

  private CompoundTag readFixture() {
    try {
      return fixture("buffers-v1");
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  @Test
  void malformedFutureDuplicateMissingOwnersAndMetadataLimitsPreserveRawAuthority()
      throws Exception {
    var baseline = fixture("registry-v7");
    baseline.putInt("Schema", 8);
    baseline.put("Buffers", fixture("buffers-v1"));
    var node = new CompoundTag();
    node.putUUID("Id", new UUID(0, 601));
    node.putString("Kind", "BUFFERED_INTERFACE");
    node.putLong("Generation", 1);
    node.putBoolean("Conflict", false);
    baseline.getList("Nodes", Tag.TAG_COMPOUND).add(node);
    assertEquals("", DeviceSavedData.load(baseline, registries).diagnostic());
    for (int mode = 0; mode < 10; mode++) {
      var tag = baseline.copy();
      var data = tag.getCompound("Buffers");
      var records = data.getList("Buffers", Tag.TAG_COMPOUND);
      var slots = records.getCompound(0).getList("Slots", Tag.TAG_COMPOUND);
      if (mode == 0) data.putInt("Schema", 2);
      if (mode == 1) slots.getCompound(0).putInt("Slot", 9);
      if (mode == 2) slots.add(slots.getCompound(0).copy());
      if (mode == 3) slots.getCompound(0).putLong("Count", 23);
      if (mode == 4) slots.getCompound(0).putInt("Count", 65);
      if (mode == 5) records.add(records.getCompound(0).copy());
      if (mode == 6) records.getCompound(0).putUUID("Backing", new UUID(0, 999));
      if (mode == 7) records.clear();
      if (mode == 8) data.putString("Future", "preserve");
      if (mode == 9) {
        var template = records.getCompound(0).copy();
        for (int index = 1; index <= BufferRegistry.MAX_BUFFERS; index++) {
          var record = template.copy();
          record.putUUID("Backing", new UUID(0, 1000 + index));
          records.add(record);
        }
      }
      var rejected = DeviceSavedData.load(tag, registries);
      assertThrows(IllegalStateException.class, rejected::buffers);
      assertEquals(tag, rejected.save(new CompoundTag(), registries));
      assertFalse(rejected.isDirty());
    }
  }

  @Test
  void excessiveSlotMetadataPreservesWholeTableAndPlacementLimitCreatesNoOrphanLease()
      throws Exception {
    var tag = fixture("registry-v7");
    tag.putInt("Schema", 8);
    var data = fixture("buffers-v1");
    var records = data.getList("Buffers", Tag.TAG_COMPOUND);
    records.clear();
    for (int buffer = 0; buffer < 32; buffer++) {
      var record = new CompoundTag();
      record.putUUID("Backing", new UUID(0, 1000 + buffer));
      var slots = new ListTag();
      for (int index = 0; index < 9; index++) {
        var slot = new CompoundTag();
        slot.putInt("Slot", index);
        slot.putString("Registry", "minecraft:iron_ingot");
        slot.putByteArray("Components", new byte[65536]);
        slot.putInt("Count", 64);
        slot.putInt("Limit", 64);
        slots.add(slot);
      }
      record.put("Slots", slots);
      records.add(record);
    }
    tag.put("Buffers", data);
    assertThrows(IllegalArgumentException.class, () -> BufferStateCodec.decode(data));
    var rejected = DeviceSavedData.load(tag, registries);
    assertThrows(IllegalStateException.class, rejected::buffers);
    assertEquals(tag, rejected.save(new CompoundTag(), registries));
    var saved = fresh(storage());
    for (int index = 0; index < BufferRegistry.MAX_BUFFERS; index++)
      saved.createBuffer(new NodeRegistry.Position("minecraft:overworld", index));
    saved.setDirty(false);
    assertThrows(
        IllegalStateException.class,
        () -> saved.createBuffer(new NodeRegistry.Position("minecraft:overworld", 999)));
    assertEquals(BufferRegistry.MAX_BUFFERS, saved.nodes().snapshot().size());
    assertEquals(BufferRegistry.MAX_BUFFERS, saved.buffers().snapshot().size());
    assertFalse(saved.isDirty());
  }

  @Test
  void canonicalDeploymentRoundTripRetainsSlotsAndRejectsUnownedOrFutureAuthority()
      throws Exception {
    var original = fixture("registry-v8");
    original.putInt("Schema", 9);
    var saved = DeviceSavedData.load(original, registries);
    var handle =
        new NodeRegistry.Handle(
            saved.registry().world(), new UUID(0, 601), 3, NodeRegistry.Kind.BUFFERED_INTERFACE);
    var pos = saved.nodes().definition(handle).position();
    var token = new Object();
    assertTrue(saved.buffers().bind(handle, pos, token));
    var deployment =
        saved
            .buffers()
            .configure(
                handle,
                token,
                1,
                new UUID(0, 9),
                new UUID(0, 101),
                new UUID(0, 102),
                BufferRegistry.Mode.IMPORT);
    var encoded = saved.save(new CompoundTag(), registries);
    assertEquals(2, encoded.getCompound("Buffers").getInt("Schema"));
    var restored = DeviceSavedData.load(encoded, registries);
    assertEquals("", restored.diagnostic());
    assertEquals(deployment, restored.buffers().deployment(handle.id()));
    assertEquals(saved.buffers().snapshot(), restored.buffers().snapshot());
    assertFalse(restored.buffers().usable(handle, token));
    var nested = encoded.getCompound("Buffers");
    var fixture = fixture("buffers-v2");
    assertEquals(fixture, nested);
    var disk = directory.resolve("buffer-deployment.dat");
    NbtIo.writeCompressed(encoded, disk);
    var diskRestored =
        DeviceSavedData.load(NbtIo.readCompressed(disk, NbtAccounter.unlimitedHeap()), registries);
    assertEquals("", diskRestored.diagnostic());
    assertEquals(deployment, diskRestored.buffers().deployment(handle.id()));
    assertEquals(saved.buffers().snapshot(), diskRestored.buffers().snapshot());
    for (int mode = 0; mode < 9; mode++) {
      var invalid = encoded.copy();
      var table = invalid.getCompound("Buffers");
      var list = table.getList("Deployments", Tag.TAG_COMPOUND);
      var entry = list.getCompound(0);
      if (mode == 0) entry.putLong("Revision", 0);
      if (mode == 1) entry.putLong("LeaseGeneration", 4);
      if (mode == 2) entry.putUUID("Backing", new UUID(0, 999));
      if (mode == 3) entry.putUUID("Network", new UUID(0, 999));
      if (mode == 4) entry.putUUID("Segment", new UUID(0, 999));
      if (mode == 5) list.add(entry.copy());
      if (mode == 6) entry.putString("Mode", "FUTURE");
      if (mode == 7) table.putInt("Schema", 3);
      if (mode == 8) entry.putInt("Revision", 2);
      var rejected = DeviceSavedData.load(invalid, registries);
      assertThrows(IllegalStateException.class, rejected::buffers);
      assertEquals(invalid, rejected.save(new CompoundTag(), registries));
    }
  }
}
