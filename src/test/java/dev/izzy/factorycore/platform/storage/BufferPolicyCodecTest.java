package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.storage.*;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BufferPolicyCodecTest {
  @TempDir Path directory;

  private static BufferPolicy policy() {
    return new BufferPolicy(
        new ResourceFilter(
            ResourceKind.ITEM,
            ResourceFilter.Mode.EXCLUDE,
            List.of(
                new ResourceFilter.Registry("minecraft:gold_ingot"),
                new ResourceFilter.Exact(
                    new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[] {1, 2})),
                new ResourceFilter.Tag("minecraft:logs"))),
        16);
  }

  @Test
  void strictPolicyFixtureRoundTripsAllTermsAndRejectsMalformedVersions() {
    var tag = BufferPolicyCodec.encode(policy());
    assertEquals(
        "{Mode:\"EXCLUDE\",Rate:16,Schema:1,Terms:[{Id:\"minecraft:gold_ingot\",Type:\"REGISTRY\"},{Components:[B;1B,2B],Id:\"minecraft:iron_ingot\",Type:\"EXACT\"},{Id:\"minecraft:logs\",Type:\"TAG\"}]}",
        tag.toString());
    assertEquals(policy(), BufferPolicyCodec.decode(tag));
    for (int i = 0; i < 9; i++) {
      var invalid = tag.copy();
      var first = invalid.getList("Terms", Tag.TAG_COMPOUND).getCompound(0);
      if (i == 0) invalid.putInt("Schema", 3);
      if (i == 1) invalid.putLong("Rate", 16);
      if (i == 2) invalid.putInt("Rate", 577);
      if (i == 3) invalid.putString("Mode", "UNKNOWN");
      if (i == 4) invalid.putString("Kind", "FLUID");
      if (i == 5) first.putString("Type", "UNKNOWN");
      if (i == 6) first.putByteArray("Components", new byte[] {1});
      if (i == 7) first.putString("Id", "invalid");
      if (i == 8) invalid.put("Terms", new IntArrayTag(new int[] {1}));
      assertThrows(IllegalArgumentException.class, () -> BufferPolicyCodec.decode(invalid));
    }
  }

  @Test
  void bufferTableThreePreservesLegacyTwoAndActualCompressedPolicyRecovery() throws Exception {
    UUID id = new UUID(0, 601), actor = new UUID(0, 9), root = new UUID(0, 101);
    var state = ItemBuffer.empty(id);
    var old =
        new BufferRegistry.Deployment(id, 2, 3, actor, root, root, BufferRegistry.Mode.IMPORT);
    var legacy = BufferStateCodec.encode(List.of(state), List.of(old));
    assertEquals(2, legacy.getInt("Schema"));
    assertFalse(legacy.getList("Deployments", Tag.TAG_COMPOUND).getCompound(0).contains("Policy"));
    assertEquals(List.of(old), BufferStateCodec.deployments(legacy));
    assertEquals(
        legacy,
        BufferStateCodec.encode(
            BufferStateCodec.decode(legacy), BufferStateCodec.deployments(legacy)));
    var configured =
        new BufferRegistry.Deployment(
            id, 3, 3, actor, root, root, BufferRegistry.Mode.IMPORT, policy());
    var table = BufferStateCodec.encode(List.of(state), List.of(configured));
    assertEquals(3, table.getInt("Schema"));
    assertEquals(List.of(configured), BufferStateCodec.deployments(table));
    var path = directory.resolve("policy.dat");
    NbtIo.writeCompressed(table, path);
    var restored = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
    assertEquals(List.of(state), BufferStateCodec.decode(restored));
    assertEquals(List.of(configured), BufferStateCodec.deployments(restored));
    assertEquals(
        table,
        BufferStateCodec.encode(
            BufferStateCodec.decode(restored), BufferStateCodec.deployments(restored)));
    for (int i = 0; i < 3; i++) {
      var bad = table.copy();
      var entry = bad.getList("Deployments", Tag.TAG_COMPOUND).getCompound(0);
      if (i == 0) bad.putInt("Schema", 5);
      if (i == 1) entry.remove("Policy");
      if (i == 2) entry.getCompound("Policy").putInt("Schema", 2);
      assertThrows(IllegalArgumentException.class, () -> BufferStateCodec.decode(bad));
    }
  }

  @Test
  void stockPolicyTwoAndBufferTableFourRetainOlderBytesAndRejectWrongUnions() throws Exception {
    var stock = new BufferPolicy(policy().filter(), 8, 20);
    var nested = BufferPolicyCodec.encode(stock);
    assertEquals(2, nested.getInt("Schema"));
    assertEquals(20, nested.getInt("Target"));
    assertEquals(stock, BufferPolicyCodec.decode(nested));
    var id = new UUID(0, 601);
    var root = new UUID(0, 101);
    var deployment =
        new BufferRegistry.Deployment(
            id, 4, 3, new UUID(0, 9), root, root, BufferRegistry.Mode.STOCK, stock);
    var table = BufferStateCodec.encode(List.of(ItemBuffer.empty(id)), List.of(deployment));
    assertEquals(4, table.getInt("Schema"));
    assertEquals(List.of(deployment), BufferStateCodec.deployments(table));
    var file = directory.resolve("stock-policy.dat");
    NbtIo.writeCompressed(table, file);
    var loaded = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
    assertEquals(
        table,
        BufferStateCodec.encode(
            BufferStateCodec.decode(loaded), BufferStateCodec.deployments(loaded)));
    for (int i = 0; i < 5; i++) {
      var bad = table.copy();
      var entry = bad.getList("Deployments", Tag.TAG_COMPOUND).getCompound(0);
      if (i == 0) bad.putInt("Schema", 5);
      if (i == 1) entry.getCompound("Policy").putInt("Schema", 3);
      if (i == 2) entry.getCompound("Policy").putInt("Target", 577);
      if (i == 3) entry.getCompound("Policy").putLong("Target", 20);
      if (i == 4) bad.putInt("Schema", 3);
      assertThrows(IllegalArgumentException.class, () -> BufferStateCodec.decode(bad));
    }
    assertThrows(IllegalArgumentException.class, () -> new BufferPolicy(policy().filter(), 8, -1));
  }
}
