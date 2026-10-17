package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.storage.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BufferRecoveryAuditTest {
  @TempDir Path folder;

  private static final UUID WORLD = new UUID(0, 100);
  private static final UUID ID = new UUID(0, 101);
  private static final UUID PRINCIPAL = new UUID(0, 102);
  private static final NodeRegistry.Stored OWNER =
      new NodeRegistry.Stored(
          ID,
          NodeRegistry.Kind.BUFFERED_INTERFACE,
          7,
          new NodeRegistry.Position("minecraft:overworld", 123),
          true,
          null);
  private static final ResourceKey KEY =
      new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[] {1, 2, 3});

  private static ItemBuffer.State inventory() {
    var slots = new ArrayList<>(Collections.nCopies(9, ItemBuffer.Slot.empty()));
    slots.set(8, new ItemBuffer.Slot(KEY, 19, 64));
    return new ItemBuffer.State(ID, slots);
  }

  private static BufferRegistry.Deployment deployment(UUID backing) {
    return new BufferRegistry.Deployment(
        backing,
        4,
        7,
        PRINCIPAL,
        new UUID(0, 103),
        new UUID(0, 104),
        BufferRegistry.Mode.STOCK,
        new BufferPolicy(
            new ResourceFilter(
                ResourceKind.ITEM,
                ResourceFilter.Mode.INCLUDE,
                List.of(new ResourceFilter.Exact(KEY))),
            3,
            17));
  }

  @Test
  void schemaThreeRetainsExactSlotsAcceptedAuthorityAndPhysicalReferenceWithOrWithoutDeployment()
      throws Exception {
    var state =
        new BufferData.State(
            new NodeRegistry.Handle(WORLD, ID, 7, OWNER.kind()), new UUID(0, 105), 4);
    var physical = new CompoundTag();
    physical.put(BufferData.FIELD, BufferData.write(state));
    physical.putString("retained", "full holder data");
    var blockState = TagParser.parseTag("{Name:'factorycore:buffered_interface'}");
    for (var configured : new boolean[] {false, true}) {
      var deployment = configured ? deployment(ID) : null;
      var file =
          DeviceRecoveryAudit.preserveBuffer(
              folder, WORLD, OWNER, inventory(), deployment, blockState, physical);
      assertEquals(
          file,
          DeviceRecoveryAudit.preserveBuffer(
              folder, WORLD, OWNER, inventory(), deployment, blockState, physical));
      try (var input = new DataInputStream(Files.newInputStream(file))) {
        var root = NbtIo.read(input, NbtAccounter.create(DeviceRecoveryAudit.MAX_FILE_BYTES * 2L));
        assertEquals(3, root.getInt("Schema"));
        assertEquals(12, root.getInt("WorldSchema"));
        assertEquals(WORLD, root.getUUID("World"));
        assertEquals("BUFFERED_INTERFACE", root.getString("HolderKind"));
        var canonicalShape = root.copy();
        canonicalShape.putInt("Schema", root.getInt("WorldSchema"));
        assertEquals(List.of(OWNER), NodeStateCodec.decode(canonicalShape));
        assertEquals(List.of(inventory()), BufferStateCodec.decode(root.getCompound("Buffers")));
        assertEquals(
            configured ? List.of(deployment) : List.of(),
            BufferStateCodec.deployments(root.getCompound("Buffers")));
        assertEquals(blockState, root.getCompound("BlockState"));
        assertEquals(physical, root.getCompound("BlockEntity"));
        assertEquals(state, BufferData.read(root.getCompound("BlockEntity").get(BufferData.FIELD)));
        assertFalse(root.contains("Ledger"));
      }
    }
  }

  @Test
  void corruptOrMismatchedEvidenceNeverOverwritesExistingFile() throws Exception {
    var holder = new CompoundTag();
    var file =
        DeviceRecoveryAudit.preserveBuffer(
            folder, WORLD, OWNER, inventory(), deployment(ID), holder, holder);
    Files.writeString(file, "preserve corrupt before-state");
    assertThrows(
        IOException.class,
        () ->
            DeviceRecoveryAudit.preserveBuffer(
                folder, WORLD, OWNER, inventory(), deployment(ID), holder, holder));
    assertEquals("preserve corrupt before-state", Files.readString(file));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DeviceRecoveryAudit.preserveBuffer(
                folder,
                WORLD,
                new NodeRegistry.Stored(ID, OWNER.kind(), 7, OWNER.position(), false, null),
                inventory(),
                deployment(ID),
                holder,
                holder));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DeviceRecoveryAudit.preserveBuffer(
                folder,
                WORLD,
                OWNER,
                ItemBuffer.empty(UUID.randomUUID()),
                deployment(ID),
                holder,
                holder));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DeviceRecoveryAudit.preserveBuffer(
                folder, WORLD, OWNER, inventory(), deployment(UUID.randomUUID()), holder, holder));
    assertEquals("preserve corrupt before-state", Files.readString(file));
  }
}
