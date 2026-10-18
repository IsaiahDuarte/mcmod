package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class MachinePersistenceTest {
  @TempDir Path directory;

  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
  }

  private static DeviceSavedData fresh() {
    var tag = new CompoundTag();
    tag.putInt("Schema", 1);
    tag.putUUID("World", UUID.randomUUID());
    tag.put("Devices", new ListTag());
    return DeviceSavedData.load(
        tag, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
  }

  @Test
  void canonicalMachineLeaseAndLabelPolicySurviveCompressedDisk() throws Exception {
    var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    var saved = fresh();
    var lease =
        saved.createForeignNode(
            new NodeRegistry.Position("minecraft:overworld", 42),
            NodeRegistry.Kind.MACHINE_INTERFACE);
    var state =
        new ConnectorData.State(
            lease,
            new ConnectorSettings(
                ResourceKind.ITEM, ConnectorSettings.Access.READ_ONLY, 0, 2, 1, 8),
            3,
            "",
            "smelter");
    var encoded = saved.save(new CompoundTag(), registries);
    assertEquals(DeviceSavedData.SCHEMA, encoded.getInt("Schema"));
    var data = ConnectorData.write(state);
    assertEquals(2, data.getInt("Schema"));
    var container = new CompoundTag();
    container.put("World", encoded);
    container.put("Machine", data);
    var file = directory.resolve("machine.dat");
    NbtIo.writeCompressed(container, file);
    var restored = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
    var loaded = DeviceSavedData.load(restored.getCompound("World"), registries);
    assertTrue(loaded.diagnostic().isEmpty());
    assertEquals(NodeRegistry.Kind.MACHINE_INTERFACE, loaded.nodes().kind(lease.id()));
    assertEquals(NodeRegistry.Failure.OK, loaded.nodes().validate(lease));
    assertEquals(state, ConnectorData.read(restored.get("Machine")));
    var moved = loaded.nodes().detach(lease, new NodeRegistry.Position("minecraft:overworld", 42));
    assertEquals(NodeRegistry.Failure.STALE, loaded.nodes().validate(lease));
    assertEquals(NodeRegistry.Kind.MACHINE_INTERFACE, moved.handle().kind());
  }

  @Test
  void oldWorldRejectsNewMachineKindAndFutureWorldIsPreserved() {
    var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    var saved = fresh();
    saved.createForeignNode(
        new NodeRegistry.Position("minecraft:overworld", 42), NodeRegistry.Kind.MACHINE_INTERFACE);
    var tag = saved.save(new CompoundTag(), registries);
    tag.remove("MachinePresets");
    tag.remove("CraftingJobs");
    tag.putInt("Schema", 10);
    var old = DeviceSavedData.load(tag, registries);
    assertFalse(old.diagnostic().isEmpty());
    assertEquals(tag, old.save(new CompoundTag(), registries));
    tag.putInt("Schema", DeviceSavedData.SCHEMA + 1);
    var future = DeviceSavedData.load(tag, registries);
    assertFalse(future.diagnostic().isEmpty());
    assertEquals(tag, future.save(new CompoundTag(), registries));
  }

  @Test
  void legacyConnectorBytesAndMachineLabelBoundsRemainStrict() {
    var lease =
        new NodeRegistry.Handle(
            UUID.randomUUID(), UUID.randomUUID(), 1, NodeRegistry.Kind.CONNECTOR);
    var legacy =
        new ConnectorData.State(lease, ConnectorSettings.defaults(ResourceKind.ITEM), 1, "");
    var encoded = ConnectorData.write(legacy);
    assertEquals(1, encoded.getInt("Schema"));
    assertFalse(encoded.contains("Label"));
    assertEquals(legacy, ConnectorData.read(encoded));
    var machine =
        new NodeRegistry.Handle(lease.world(), lease.id(), 1, NodeRegistry.Kind.MACHINE_INTERFACE);
    assertThrows(
        IllegalArgumentException.class,
        () -> new ConnectorData.State(machine, legacy.settings(), 1, "", "x".repeat(65)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ConnectorData.State(machine, legacy.settings(), 1, "", "a\nb"));
    var data =
        ConnectorData.write(new ConnectorData.State(machine, legacy.settings(), 1, "", "local"));
    data.putInt("Schema", 1);
    assertThrows(IllegalArgumentException.class, () -> ConnectorData.read(data));
  }
}
