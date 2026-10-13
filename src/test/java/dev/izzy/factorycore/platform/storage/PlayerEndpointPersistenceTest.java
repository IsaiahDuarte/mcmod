package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.resource.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerEndpointPersistenceTest {
  @TempDir Path directory;
  private static RegistryAccess registries;

  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
  }

  private CompoundTag fixture(String name) throws Exception {
    try (var input = getClass().getResourceAsStream("/persistence/" + name + ".snbt")) {
      assertNotNull(input);
      return TagParser.parseTag(new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  private static StagedTransfer.Snapshot snapshot(
      TransferContext context, UUID actor, ResourceKind kind) {
    return new StagedTransfer.Snapshot(
        actor,
        kind == ResourceKind.ITEM
            ? new ResourceKey(kind, "minecraft:iron_ingot", new byte[] {1, 2})
            : ResourceKey.ENERGY,
        context.source().backing(),
        context.destination().backing(),
        23,
        0,
        0,
        StagedTransfer.State.STAGED,
        OperationResult.Reason.FULL,
        "",
        StagedTransfer.PendingCall.NONE,
        0);
  }

  @Test
  void playerContextFixtureAndUnionVersionsPreserveExactAcceptedPrincipalSlotAndNonce()
      throws Exception {
    var original = fixture("transfer-player-context-v3");
    var context = TransferContextCodec.decode(original);
    assertEquals(original, TransferContextCodec.encode(context));
    assertEquals(TransferContext.Type.PLAYER, context.source().type());
    assertEquals(
        TransferContext.playerBacking(context.world(), new UUID(0, 9)), context.source().backing());
    assertEquals(7, context.source().generation());
    assertEquals(1, context.source().configuration());
    context.validate(snapshot(context, new UUID(0, 9), ResourceKind.ITEM));
    assertThrows(
        IllegalArgumentException.class,
        () -> context.validate(snapshot(context, new UUID(0, 10), ResourceKind.ITEM)));
    assertThrows(
        IllegalArgumentException.class,
        () -> context.validate(snapshot(context, new UUID(0, 9), ResourceKind.ENERGY)));
    for (int version : new int[] {1, 2, 4}) {
      var invalid = original.copy();
      invalid.putInt("Schema", version);
      assertThrows(IllegalArgumentException.class, () -> TransferContextCodec.decode(invalid));
    }
    var wholeSource = original.copy();
    wholeSource.getCompound("Source").putLong("Configuration", 37);
    var whole = TransferContextCodec.decode(wholeSource);
    assertThrows(
        IllegalArgumentException.class,
        () -> whole.validate(snapshot(whole, new UUID(0, 9), ResourceKind.ITEM)));
    var wrongBacking = original.copy();
    wrongBacking.getCompound("Source").putUUID("Backing", UUID.randomUUID());
    var mismatched = TransferContextCodec.decode(wrongBacking);
    assertThrows(
        IllegalArgumentException.class,
        () -> mismatched.validate(snapshot(mismatched, new UUID(0, 9), ResourceKind.ITEM)));
    for (var name : new String[] {"transfer-context-v1", "transfer-buffer-context-v2"}) {
      var prior = fixture(name);
      assertEquals(prior, TransferContextCodec.encode(TransferContextCodec.decode(prior)));
    }
  }

  @Test
  void schemaNineMigrationChangesOnlyVersionAndPlayerStagingSurvivesCompressedReload()
      throws Exception {
    var original = fixture("registry-v8");
    original.putInt("Schema", 9);
    var migrated = DeviceSavedData.load(original, registries).save(new CompoundTag(), registries);
    assertEquals(10, migrated.getInt("Schema"));
    migrated.putInt("Schema", 9);
    assertEquals(original, migrated);
    var context = TransferContextCodec.decode(fixture("transfer-player-context-v3"));
    // Encode via the production transfer table so no hand-maintained quantity format substitutes.
    var transfers =
        TransferStateCodec.encode(
            java.util.List.of(
                new TransferRegistry.Stored(
                    new UUID(0, 999),
                    snapshot(context, new UUID(0, 9), ResourceKind.ITEM),
                    context)));
    var data = original.copy();
    data.putInt("Schema", 10);
    data.put("Transfers", transfers);
    var restored = DeviceSavedData.load(data, registries);
    assertEquals("", restored.diagnostic());
    assertEquals(23, restored.transfers().get(new UUID(0, 999)).staged());
    var disk = directory.resolve("player-stage.dat");
    NbtIo.writeCompressed(restored.save(new CompoundTag(), registries), disk);
    var reloaded =
        DeviceSavedData.load(NbtIo.readCompressed(disk, NbtAccounter.unlimitedHeap()), registries);
    assertEquals(context, reloaded.transfers().context(new UUID(0, 999)));
    assertEquals(23, reloaded.transfers().get(new UUID(0, 999)).staged());
    data.putInt("Schema", 9);
    var old = DeviceSavedData.load(data, registries);
    assertThrows(IllegalStateException.class, old::transfers);
    assertEquals(data, old.save(new CompoundTag(), registries));
    assertFalse(old.isDirty());
  }
}
