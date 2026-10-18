package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.platform.network.NodeHandleData;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NetworkNodeRecoveryAuditTest {
  @TempDir Path folder;
  private static final UUID WORLD = new UUID(0, 100), ID = new UUID(0, 101);

  private static NodeRegistry.Stored owner(NodeRegistry.Kind kind) {
    var policy =
        new GatewayPolicy(
            Set.of(NetworkPermissions.Permission.VIEW),
            Set.of(NetworkPermissions.Permission.DEPOSIT),
            Set.of(ResourceKind.FLUID));
    return new NodeRegistry.Stored(
        ID,
        kind,
        7,
        new NodeRegistry.Position("minecraft:overworld", 123),
        true,
        kind == NodeRegistry.Kind.GATEWAY ? policy : null);
  }

  private static NetworkPermissions.State permissions(UUID id) {
    return new NetworkPermissions.State(
        id,
        new UUID(0, 102),
        19,
        Map.of(
            new UUID(0, 103),
            Set.of(NetworkPermissions.Permission.VIEW, NetworkPermissions.Permission.DEPOSIT)));
  }

  @Test
  void schemaFourRetainsControllerGrantsOrRestrictedGatewayPolicyAndPhysicalData()
      throws Exception {
    for (var kind : List.of(NodeRegistry.Kind.CONTROLLER, NodeRegistry.Kind.GATEWAY)) {
      var owner = owner(kind);
      var permissions = kind == NodeRegistry.Kind.CONTROLLER ? permissions(ID) : null;
      var handle = new NodeRegistry.Handle(WORLD, ID, 7, kind);
      var physical = new CompoundTag();
      physical.put(NodeHandleData.FIELD, NodeHandleData.write(handle));
      physical.putString("retained", "full holder evidence");
      var state =
          TagParser.parseTag(
              kind == NodeRegistry.Kind.CONTROLLER
                  ? "{Name:'factorycore:network_controller'}"
                  : "{Name:'factorycore:gateway',Properties:{facing:'east'}}");
      var file =
          DeviceRecoveryAudit.preserveNetworkNode(
              folder, WORLD, owner, permissions, state, physical);
      assertEquals(
          file,
          DeviceRecoveryAudit.preserveNetworkNode(
              folder, WORLD, owner, permissions, state, physical));
      try (var input = new DataInputStream(Files.newInputStream(file))) {
        var root = NbtIo.read(input, NbtAccounter.create(DeviceRecoveryAudit.MAX_FILE_BYTES * 2L));
        assertEquals(4, root.getInt("Schema"));
        assertEquals(12, root.getInt("WorldSchema"));
        assertEquals(WORLD, root.getUUID("World"));
        assertEquals("NETWORK_NODE", root.getString("HolderKind"));
        var canonical = root.copy();
        canonical.putInt("Schema", root.getInt("WorldSchema"));
        assertEquals(List.of(owner), NodeStateCodec.decode(canonical));
        assertEquals(
            permissions == null ? List.of() : List.of(permissions),
            NetworkStateCodec.decode(canonical));
        assertEquals(state, root.getCompound("BlockState"));
        assertEquals(physical, root.getCompound("BlockEntity"));
        assertEquals(
            handle, NodeHandleData.read(root.getCompound("BlockEntity").get(NodeHandleData.FIELD)));
        assertFalse(root.contains("Ledger"));
        assertFalse(root.contains("Buffers"));
      }
      Files.writeString(file, "preserve damaged node audit");
      assertThrows(
          IOException.class,
          () ->
              DeviceRecoveryAudit.preserveNetworkNode(
                  folder, WORLD, owner, permissions, state, physical));
      assertEquals("preserve damaged node audit", Files.readString(file));
    }
  }

  @Test
  void mismatchedAuthorityAndUnsupportedKindRejectBeforeArchiving() {
    var physical = new CompoundTag();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DeviceRecoveryAudit.preserveNetworkNode(
                folder, WORLD, owner(NodeRegistry.Kind.CONTROLLER), null, physical, physical));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DeviceRecoveryAudit.preserveNetworkNode(
                folder,
                WORLD,
                owner(NodeRegistry.Kind.CONTROLLER),
                permissions(UUID.randomUUID()),
                physical,
                physical));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DeviceRecoveryAudit.preserveNetworkNode(
                folder,
                WORLD,
                owner(NodeRegistry.Kind.GATEWAY),
                permissions(ID),
                physical,
                physical));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DeviceRecoveryAudit.preserveNetworkNode(
                folder, WORLD, owner(NodeRegistry.Kind.CONNECTOR), null, physical, physical));
    assertFalse(Files.exists(folder.resolve("factorycore-device-recovery")));
  }

  @Test
  void schemaFiveAdoptionEvidenceAcceptsUnconflictedNodesAndRecordsTheTarget() throws Exception {
    var conflicted = owner(NodeRegistry.Kind.CONTROLLER);
    var owner =
        new NodeRegistry.Stored(
            ID, conflicted.kind(), 7, conflicted.position(), false, conflicted.policy());
    var target = new NodeRegistry.Position("minecraft:overworld", 456);
    var state = TagParser.parseTag("{Name:'factorycore:network_controller'}");
    var file =
        DeviceRecoveryAudit.preserveNetworkNodeAdoption(
            folder, WORLD, owner, permissions(ID), target, state, new CompoundTag());
    CompoundTag evidence;
    try (var input = new DataInputStream(Files.newInputStream(file))) {
      evidence = NbtIo.read(input, NbtAccounter.unlimitedHeap());
    }
    assertEquals(5, evidence.getInt("Schema"));
    assertEquals("NETWORK_NODE_ADOPTION", evidence.getString("HolderKind"));
    assertEquals(456, evidence.getCompound("Target").getLong("Position"));
    assertEquals(state, evidence.get("BlockState"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DeviceRecoveryAudit.preserveNetworkNodeAdoption(
                folder, WORLD, owner, null, target, state, new CompoundTag()),
        "A controller adoption must carry its grants");
  }
}
