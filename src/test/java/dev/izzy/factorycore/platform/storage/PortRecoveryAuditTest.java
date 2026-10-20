package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.storage.*;
import dev.izzy.factorycore.platform.network.NodeHandleData;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortRecoveryAuditTest {
  @TempDir Path folder;
  private static final UUID WORLD = new UUID(0, 100), ID = new UUID(0, 101);

  private static NodeRegistry.Stored owner(boolean machine) {
    return new NodeRegistry.Stored(
        ID,
        machine ? NodeRegistry.Kind.MACHINE_INTERFACE : NodeRegistry.Kind.CONNECTOR,
        2,
        new NodeRegistry.Position("minecraft:overworld", 123),
        true,
        null);
  }

  private static ConnectorData.State reference(boolean machine) {
    return new ConnectorData.State(
        new NodeRegistry.Handle(WORLD, ID, 2, owner(machine).kind()),
        new ConnectorSettings(ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 7, 1, 2, 3),
        5,
        "",
        machine ? "furnace" : "");
  }

  private static CompoundTag physical(ConnectorData.State reference) {
    var tag = new CompoundTag();
    tag.put(ConnectorData.FIELD, ConnectorData.write(reference));
    tag.putString("retained", "complete holder data");
    return tag;
  }

  private static MachinePresets.Deployment deployment() {
    return new MachinePresets.Deployment(
        ID,
        2,
        7,
        5,
        MachinePresets.Face.EAST,
        new MachinePresets.Authority(new UUID(0, 102), new UUID(0, 103), new UUID(0, 104), 9),
        MachinePresets.Mode.STOCK,
        ResourceFilter.all(ResourceKind.ITEM),
        17);
  }

  @Test
  void schemaFivePreservesSettingsLabelFenceDeploymentAndOptionalRememberedBinding()
      throws Exception {
    for (boolean machine : new boolean[] {false, true}) {
      var reference = reference(machine);
      var physical = physical(reference);
      var blockState = new CompoundTag();
      blockState.putString(
          "Name", machine ? "factorycore:machine_interface" : "factorycore:storage_connector");
      var binding =
          new WorldConnectors.Handle(
              reference.lease(), 5, UUID.randomUUID(), UUID.randomUUID(), ResourceKind.ITEM);
      var deployed = machine ? deployment() : null;
      for (boolean remembered : new boolean[] {false, true}) {
        var witness = remembered ? binding : null;
        var file =
            DeviceRecoveryAudit.preservePort(
                folder,
                WORLD,
                owner(machine),
                deployed,
                machine ? 5 : 0,
                witness,
                blockState,
                physical);
        assertEquals(
            file,
            DeviceRecoveryAudit.preservePort(
                folder,
                WORLD,
                owner(machine),
                deployed,
                machine ? 5 : 0,
                witness,
                blockState,
                physical));
        try (var input = new DataInputStream(Files.newInputStream(file))) {
          var root =
              NbtIo.read(input, NbtAccounter.create(DeviceRecoveryAudit.MAX_FILE_BYTES * 2L));
          assertEquals(5, root.getInt("Schema"));
          assertEquals(12, root.getInt("WorldSchema"));
          assertEquals(WORLD, root.getUUID("World"));
          assertEquals("FOREIGN_PORT", root.getString("HolderKind"));
          var canonical = root.copy();
          canonical.putInt("Schema", 12);
          assertEquals(List.of(owner(machine)), NodeStateCodec.decode(canonical));
          var presets = root.getCompound("MachinePresets");
          assertEquals(machine ? List.of(deployed) : List.of(), MachinePresetCodec.decode(presets));
          assertEquals(
              machine ? Map.of(ID, 5L) : Map.of(), MachinePresetCodec.configurations(presets));
          assertEquals(physical, root.getCompound("BlockEntity"));
          assertEquals(
              reference,
              ConnectorData.read(root.getCompound("BlockEntity").get(ConnectorData.FIELD)));
          assertEquals(blockState, root.getCompound("BlockState"));
          assertEquals(remembered, root.contains("Registration"));
          if (remembered) {
            var value = root.getCompound("Registration");
            assertEquals(binding.backing(), value.getUUID("Backing"));
            assertEquals(binding.provider(), value.getUUID("Provider"));
            assertEquals(binding.generation(), value.getLong("Configuration"));
            assertEquals(binding.kind().name(), value.getString("Kind"));
            assertEquals(binding.connector(), NodeHandleData.read(value.get("Node")));
          }
          assertFalse(root.contains("Ledger"));
          assertFalse(root.contains("ProviderContents"));
        }
      }
    }
  }

  @Test
  void schemaSevenAdoptionPreservesBothSaveVersionsAndRefusesCurrentOrFaultedHolder()
      throws Exception {
    for (boolean machine : new boolean[] {false, true}) {
      var owner = owner(machine);
      var current = reference(machine);
      var stale =
          new ConnectorData.State(
              new NodeRegistry.Handle(WORLD, ID, 1, owner.kind()),
              current.settings(),
              3,
              "",
              current.label());
      var target = new NodeRegistry.Position("minecraft:overworld", 456);
      var file =
          DeviceRecoveryAudit.preservePortAdoption(
              folder,
              WORLD,
              owner,
              machine ? deployment() : null,
              machine ? 5 : 0,
              target,
              new CompoundTag(),
              physical(stale));
      try (var input = new DataInputStream(Files.newInputStream(file))) {
        var root = NbtIo.read(input, NbtAccounter.create(DeviceRecoveryAudit.MAX_FILE_BYTES * 2L));
        assertEquals(7, root.getInt("Schema"));
        assertEquals(DeviceSavedData.SCHEMA, root.getInt("WorldSchema"));
        assertEquals("FOREIGN_PORT_ADOPTION", root.getString("HolderKind"));
        var canonical = root.copy();
        canonical.putInt("Schema", DeviceSavedData.SCHEMA);
        assertEquals(List.of(owner), NodeStateCodec.decode(canonical));
        assertEquals(456, root.getCompound("Target").getLong("Position"));
        assertEquals(physical(stale), root.getCompound("BlockEntity"));
        assertEquals(
            machine ? Map.of(ID, 5L) : Map.of(),
            MachinePresetCodec.configurations(root.getCompound("MachinePresets")));
        assertFalse(root.contains("ProviderContents"));
      }
      assertThrows(
          IllegalArgumentException.class,
          () ->
              DeviceRecoveryAudit.preservePortAdoption(
                  folder,
                  WORLD,
                  owner,
                  machine ? deployment() : null,
                  machine ? 5 : 0,
                  target,
                  new CompoundTag(),
                  physical(current)));
      var fault =
          new ConnectorData.State(
              stale.lease(), stale.settings(), 3, "uncertain call", stale.label());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              DeviceRecoveryAudit.preservePortAdoption(
                  folder,
                  WORLD,
                  owner,
                  machine ? deployment() : null,
                  machine ? 5 : 0,
                  target,
                  new CompoundTag(),
                  physical(fault)));
    }
  }

  @Test
  void mismatchedConfigurationFaultOrCorruptEvidenceNeverGetsReinterpreted() throws Exception {
    var reference = reference(true);
    var physical = physical(reference);
    var state = new CompoundTag();
    var file =
        DeviceRecoveryAudit.preservePort(
            folder, WORLD, owner(true), deployment(), 5, null, state, physical);
    Files.writeString(file, "retain damaged port evidence");
    assertThrows(
        IOException.class,
        () ->
            DeviceRecoveryAudit.preservePort(
                folder, WORLD, owner(true), deployment(), 5, null, state, physical));
    assertEquals("retain damaged port evidence", Files.readString(file));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DeviceRecoveryAudit.preservePort(
                folder, WORLD, owner(true), deployment(), 6, null, state, physical));
    var faulted =
        physical(
            new ConnectorData.State(
                reference.lease(),
                reference.settings(),
                5,
                "Provider call uncertain",
                reference.label()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DeviceRecoveryAudit.preservePort(
                folder, WORLD, owner(true), deployment(), 5, null, state, faulted));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DeviceRecoveryAudit.preservePort(
                folder, WORLD, owner(false), null, 0, null, state, physical));
    var wrong =
        new WorldConnectors.Handle(
            reference.lease(), 4, UUID.randomUUID(), UUID.randomUUID(), ResourceKind.ITEM);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DeviceRecoveryAudit.preservePort(
                folder, WORLD, owner(true), deployment(), 5, wrong, state, physical));
    assertEquals("retain damaged port evidence", Files.readString(file));
  }
}
