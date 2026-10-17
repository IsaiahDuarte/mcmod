package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeviceRecoveryAuditTest {
  @TempDir Path folder;

  private static DeviceRegistry.Stored owner() {
    var key = new ResourceKey(ResourceKind.FLUID, "minecraft:water", new byte[] {1, 2});
    return new DeviceRegistry.Stored(
        new LedgerState(
            UUID.randomUUID(),
            ResourceKind.FLUID,
            1000,
            false,
            8,
            8,
            Map.of(key, 800L),
            List.of(new LedgerState.Reservation(UUID.randomUUID(), key, 300))),
        9,
        new DeviceRegistry.Location("minecraft:overworld", 123, 2),
        true);
  }

  @Test
  void binaryEvidenceRetainsFullLedgerClaimsLocationAndPhysicalReferenceWithoutOverwrite()
      throws Exception {
    var owner = owner();
    var world = UUID.randomUUID();
    var stack =
        TagParser.parseTag(
            "{id:'factorycore:starter_fluid_cell',count:1,components:{custom:'retained'}}");
    var file = DeviceRecoveryAudit.preserve(folder, world, owner, stack);
    CompoundTag evidence;
    try (var input = new DataInputStream(Files.newInputStream(file))) {
      evidence = NbtIo.read(input, NbtAccounter.create(DeviceRecoveryAudit.MAX_FILE_BYTES * 2L));
    }
    assertEquals(1, evidence.getInt("Schema"));
    assertEquals(12, evidence.getInt("WorldSchema"));
    assertEquals(world, evidence.getUUID("World"));
    assertEquals(owner.ledger(), LedgerStateCodec.decode(evidence.getByteArray("Ledger")));
    assertEquals(owner.generation(), evidence.getLong("Generation"));
    assertTrue(evidence.getBoolean("Conflict"));
    assertEquals(
        owner.location().dimension(), evidence.getCompound("Location").getString("Dimension"));
    assertEquals(owner.location().position(), evidence.getCompound("Location").getLong("Position"));
    assertEquals(owner.location().slot(), evidence.getCompound("Location").getInt("Slot"));
    assertEquals(stack, evidence.get("Stack"));
    assertEquals(file, DeviceRecoveryAudit.preserve(folder, world, owner, stack));
    Files.writeString(file, "corrupt evidence");
    assertThrows(
        IOException.class, () -> DeviceRecoveryAudit.preserve(folder, world, owner, stack));
    assertEquals("corrupt evidence", Files.readString(file));
  }

  @Test
  void bankEvidenceKeepsStateAndRawHolderWithoutChangingCellSchema() throws Exception {
    var original = owner();
    var owner =
        new DeviceRegistry.Stored(
            new LedgerState(
                original.ledger().id(),
                ResourceKind.ENERGY,
                100000,
                false,
                8,
                8,
                Map.of(ResourceKey.ENERGY, 900L),
                List.of(new LedgerState.Reservation(UUID.randomUUID(), ResourceKey.ENERGY, 200))),
            2,
            new DeviceRegistry.Location("minecraft:overworld", 45, 0),
            true);
    var world = UUID.randomUUID();
    var state = TagParser.parseTag("{Name:'factorycore:starter_energy_bank'}");
    var holder = new CompoundTag();
    var handle = new DeviceRegistry.Handle(world, owner.ledger().id(), owner.generation());
    holder.put(EnergyBankHandleData.FIELD, EnergyBankHandleData.write(handle));
    holder.putString("retained", "complete physical evidence");
    var file = DeviceRecoveryAudit.preserveBank(folder, world, owner, state, holder);
    assertEquals(file, DeviceRecoveryAudit.preserveBank(folder, world, owner, state, holder));
    try (var input = new DataInputStream(Files.newInputStream(file))) {
      var root = NbtIo.read(input, NbtAccounter.create(DeviceRecoveryAudit.MAX_FILE_BYTES * 2L));
      assertEquals(2, root.getInt("Schema"));
      assertEquals("ENERGY_BANK", root.getString("HolderKind"));
      assertEquals(world, root.getUUID("World"));
      assertEquals(owner.ledger(), LedgerStateCodec.decode(root.getByteArray("Ledger")));
      assertEquals(state, root.getCompound("BlockState"));
      assertEquals(holder, root.getCompound("BlockEntity"));
      assertEquals(
          handle,
          EnergyBankHandleData.read(
              root.getCompound("BlockEntity").get(EnergyBankHandleData.FIELD)));
      assertFalse(root.contains("Stack"));
    }
    Files.writeString(file, "preserved corrupt bank evidence");
    assertThrows(
        IOException.class,
        () -> DeviceRecoveryAudit.preserveBank(folder, world, owner, state, holder));
    assertEquals("preserved corrupt bank evidence", Files.readString(file));
  }

  @Test
  void binaryAuditSupportsLedgerBeyondTransferSnbtLimit() throws Exception {
    var contents = new java.util.HashMap<ResourceKey, Long>();
    for (int index = 0; index < 10; index++)
      contents.put(new ResourceKey(ResourceKind.ITEM, "test:large_" + index, new byte[65536]), 10L);
    var original = owner();
    var owner =
        new DeviceRegistry.Stored(
            new LedgerState(
                original.ledger().id(), ResourceKind.ITEM, 1000, false, 16, 8, contents, List.of()),
            7,
            original.location(),
            true);
    var file = DeviceRecoveryAudit.preserve(folder, UUID.randomUUID(), owner, new CompoundTag());
    assertTrue(Files.size(file) > RecoveryAudit.MAX_FILE_BYTES);
    try (var input = new DataInputStream(Files.newInputStream(file))) {
      var root = NbtIo.read(input, NbtAccounter.create(DeviceRecoveryAudit.MAX_FILE_BYTES * 2L));
      assertEquals(owner.ledger(), LedgerStateCodec.decode(root.getByteArray("Ledger")));
    }
  }

  @Test
  void oversizedPhysicalDataAndBlockedDirectoryRejectWithoutReplacingEvidence() throws Exception {
    var owner = owner();
    var stack = new CompoundTag();
    stack.putByteArray("oversized", new byte[DeviceRecoveryAudit.MAX_FILE_BYTES]);
    assertThrows(
        IOException.class,
        () -> DeviceRecoveryAudit.preserve(folder, UUID.randomUUID(), owner, stack));
    assertFalse(Files.exists(folder.resolve("factorycore-device-recovery")));
    var path = Files.writeString(folder.resolve("factorycore-device-recovery"), "preserved");
    assertThrows(
        IOException.class,
        () -> DeviceRecoveryAudit.preserve(folder, UUID.randomUUID(), owner, new CompoundTag()));
    assertEquals("preserved", Files.readString(path));
  }
}
