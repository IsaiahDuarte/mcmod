package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.resource.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import net.minecraft.nbt.TagParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryAuditTest {
  @TempDir Path folder;

  private static TransferRegistry.Stored record() {
    return new TransferRegistry.Stored(
        UUID.randomUUID(),
        new StagedTransfer.Snapshot(
            UUID.randomUUID(),
            new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[] {1, 2, 3}),
            UUID.randomUUID(),
            UUID.randomUUID(),
            5,
            3,
            0,
            StagedTransfer.State.STAGED,
            OperationResult.Reason.FULL,
            "Known partial",
            StagedTransfer.PendingCall.NONE,
            0));
  }

  @Test
  void preservesExactBeforeStateAndReusesIdenticalEvidence() throws Exception {
    var value = record();
    var world = UUID.randomUUID();
    var file = RecoveryAudit.preserve(folder, world, value);
    var encoded = Files.readString(file);
    var root = TagParser.parseTag(encoded);
    assertEquals(1, root.getInt("Schema"));
    assertEquals(world, root.getUUID("World"));
    var canonicalShape = root.copy();
    canonicalShape.putInt("Schema", root.getInt("WorldSchema"));
    assertEquals(value, TransferStateCodec.decode(canonicalShape).getFirst());
    assertEquals(file, RecoveryAudit.preserve(folder, world, value));
    assertEquals(encoded, Files.readString(file));
    Files.writeString(file, "corrupt");
    assertThrows(IOException.class, () -> RecoveryAudit.preserve(folder, world, value));
    assertEquals("corrupt", Files.readString(file), "Existing evidence is never overwritten");
  }

  @Test
  void quotaAndUnexpectedEntriesRejectWithoutDiscardingEvidence() throws Exception {
    var directory = Files.createDirectory(folder.resolve("factorycore-recovery"));
    for (int i = 0; i < RecoveryAudit.MAX_FILES; i++)
      Files.writeString(directory.resolve(i + ".snbt"), "retained");
    var value = record();
    assertThrows(IOException.class, () -> RecoveryAudit.preserve(folder, UUID.randomUUID(), value));
    assertEquals("retained", Files.readString(directory.resolve("0.snbt")));
    for (int i = 0; i < RecoveryAudit.MAX_FILES; i++) Files.delete(directory.resolve(i + ".snbt"));
    Files.createDirectory(directory.resolve("unexpected"));
    assertThrows(IOException.class, () -> RecoveryAudit.preserve(folder, UUID.randomUUID(), value));
    assertTrue(Files.isDirectory(directory.resolve("unexpected")));
  }
}
