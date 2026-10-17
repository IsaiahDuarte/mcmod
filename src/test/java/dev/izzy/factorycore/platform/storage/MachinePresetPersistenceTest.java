package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.core.storage.MachinePresets;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class MachinePresetPersistenceTest {
  @TempDir Path folder;

  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
  }

  private static net.minecraft.core.HolderLookup.Provider registries() {
    return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
  }

  private CompoundTag fixture() throws Exception {
    try (var input = getClass().getResourceAsStream("/persistence/machine-presets-v1.snbt")) {
      assertNotNull(input);
      return TagParser.parseTag(new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  private DeviceSavedData world() {
    var tag = new CompoundTag();
    tag.putInt("Schema", 1);
    tag.putUUID("World", new UUID(0, 10));
    tag.put("Devices", new ListTag());
    var saved = DeviceSavedData.load(tag, registries());
    var root =
        saved.createController(new UUID(0, 9), new NodeRegistry.Position("minecraft:overworld", 1));
    var node =
        saved.createForeignNode(
            new NodeRegistry.Position("minecraft:overworld", 3),
            NodeRegistry.Kind.MACHINE_INTERFACE);
    var sample =
        new MachinePresets.Deployment(
            node.id(),
            1,
            1,
            1,
            MachinePresets.Face.NORTH,
            new MachinePresets.Authority(
                new UUID(0, 9), root.id(), root.id(), saved.network(root.id()).generation()),
            MachinePresets.Mode.STOCK,
            dev.izzy.factorycore.core.resource.ResourceFilter.all(
                dev.izzy.factorycore.core.resource.ResourceKind.ENERGY),
            Long.MAX_VALUE);
    saved
        .machinePresets()
        .deploy(
            node,
            1,
            sample.face(),
            sample.authority(),
            sample.mode(),
            sample.filter(),
            sample.target());
    return saved;
  }

  @Test
  void fixedFilterBytesAndCompressedCanonicalScopeRoundTrip() throws Exception {
    var fixed = fixture();
    var records = MachinePresetCodec.decode(fixed);
    assertEquals(fixed, MachinePresetCodec.encode(records));
    assertEquals(Long.MAX_VALUE, records.getFirst().target());
    var saved = world();
    var expected = saved.machinePresets().snapshot();
    var tag = saved.save(new CompoundTag(), registries());
    assertEquals(12, tag.getInt("Schema"));
    var file = folder.resolve("deployments.dat");
    NbtIo.writeCompressed(tag, file);
    var loaded =
        DeviceSavedData.load(
            NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()), registries());
    assertEquals("", loaded.diagnostic());
    assertEquals(expected, loaded.machinePresets().snapshot());
    var legacy = tag.copy();
    legacy.remove("MachinePresets");
    legacy.putInt("Schema", 11);
    var migrated = DeviceSavedData.load(legacy, registries());
    assertEquals("", migrated.diagnostic());
    assertTrue(migrated.machinePresets().snapshot().isEmpty());
    var encoded = migrated.save(new CompoundTag(), registries());
    encoded.remove("MachinePresets");
    encoded.putInt("Schema", 11);
    assertEquals(legacy, encoded);
  }

  @Test
  void invalidOwnerScopeFilterAndFutureTablePreserveWholeWorld() {
    var original = world().save(new CompoundTag(), registries());
    for (int i = 0; i < 9; i++) {
      var tag = original.copy();
      var table = tag.getCompound("MachinePresets");
      var list = table.getList("Deployments", Tag.TAG_COMPOUND);
      var entry = list.getCompound(0);
      switch (i) {
        case 0 -> table.putInt("Schema", 2);
        case 1 -> entry.putUUID("Node", UUID.randomUUID());
        case 2 -> entry.putUUID("Network", UUID.randomUUID());
        case 3 -> entry.putUUID("Segment", UUID.randomUUID());
        case 4 -> list.add(entry.copy());
        case 5 -> entry.putLong("Lease", 2);
        case 6 -> entry.getCompound("Filter").putString("Mode", "FUTURE");
        case 7 -> entry.putLong("Target", -1);
        case 8 -> tag.putInt("Schema", 13);
        default -> throw new IllegalStateException();
      }
      var rejected = DeviceSavedData.load(tag, registries());
      assertFalse(rejected.diagnostic().isEmpty(), "Malformed case " + i);
      assertEquals(tag, rejected.save(new CompoundTag(), registries()));
      assertThrows(IllegalStateException.class, rejected::machinePresets);
      assertFalse(rejected.isDirty());
    }
  }

  @Test
  void wrongTypedSelectorAndUnknownFieldsNeverBroadenSelection() throws Exception {
    var original = fixture();
    for (int i = 0; i < 5; i++) {
      var tag = original.copy();
      var entry = tag.getList("Deployments", Tag.TAG_COMPOUND).getCompound(0);
      var filter = entry.getCompound("Filter");
      switch (i) {
        case 0 -> filter.putString("Terms", "ALL");
        case 1 ->
            filter
                .getList("Terms", Tag.TAG_COMPOUND)
                .getCompound(0)
                .putString("Components", "ignored");
        case 2 ->
            filter.getList("Terms", Tag.TAG_COMPOUND).getCompound(1).putString("Type", "FUTURE");
        case 3 -> entry.putString("Extra", "ignored");
        case 4 -> filter.putString("Kind", "ENERGY");
        default -> throw new IllegalStateException();
      }
      assertThrows(IllegalArgumentException.class, () -> MachinePresetCodec.decode(tag));
    }
  }
}
