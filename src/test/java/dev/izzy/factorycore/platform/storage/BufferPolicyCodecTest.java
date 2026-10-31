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
  void slotGoalsRoundTripAsSchemaThreeAndMustMatchTheSlots() {
    var filter =
        new ResourceFilter(
            ResourceKind.ITEM,
            ResourceFilter.Mode.INCLUDE,
            List.of(
                new ResourceFilter.Registry("minecraft:coal"),
                new ResourceFilter.Registry("minecraft:iron_ingot"),
                new ResourceFilter.Tag("minecraft:logs")));
    var policy = new BufferPolicy(filter, 64, 32, List.of(64, 16, BufferPolicy.DEFAULT_GOAL));
    var tag = BufferPolicyCodec.encode(policy);
    assertEquals(3, tag.getInt("Schema"));
    assertArrayEquals(new int[] {64, 16, -1}, tag.getIntArray("Slots"));
    assertEquals(policy, BufferPolicyCodec.decode(tag));
    assertEquals(64, policy.goal(0));
    assertEquals(16, policy.goal(1));
    assertEquals(32, policy.goal(2), "A slot without its own goal uses the policy's");
    assertTrue(policy.perSlot());
    assertEquals(
        policy.slotTargets(), policy.withRate(8).slotTargets(), "A rate change keeps them");
    assertEquals(
        List.of(),
        new BufferPolicy(filter, 64, 32, List.of(-1, -1, -1)).slotTargets(),
        "All slots on the policy's goal is no slot goals");
    var wrongCount = tag.copy();
    wrongCount.putIntArray("Slots", new int[] {64, 16});
    assertThrows(IllegalArgumentException.class, () -> BufferPolicyCodec.decode(wrongCount));
    var tooMany = tag.copy();
    tooMany.putIntArray("Slots", new int[] {64, 577, -1});
    assertThrows(IllegalArgumentException.class, () -> BufferPolicyCodec.decode(tooMany));
    var missing = tag.copy();
    missing.remove("Slots");
    assertThrows(IllegalArgumentException.class, () -> BufferPolicyCodec.decode(missing));
    // The buffer table carries it as table five next to a policy without slot goals.
    UUID root = new UUID(0, 101), actor = new UUID(0, 9);
    var withGoals =
        new BufferRegistry.Deployment(
            new UUID(0, 1), 3, 3, actor, root, root, BufferRegistry.Mode.STOCK, policy);
    var plain =
        new BufferRegistry.Deployment(
            new UUID(0, 2),
            3,
            3,
            actor,
            root,
            root,
            BufferRegistry.Mode.STOCK,
            new BufferPolicy(filter, 64, 32));
    var table = BufferStateCodec.encode(List.of(), List.of(withGoals, plain));
    assertEquals(5, table.getInt("Schema"));
    assertEquals(List.of(withGoals, plain), BufferStateCodec.deployments(table));
    var oldTable = table.copy();
    oldTable.putInt("Schema", 4);
    assertThrows(
        IllegalArgumentException.class,
        () -> BufferStateCodec.deployments(oldTable),
        "A table four cannot hold slot goals");
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
      if (i == 0) bad.putInt("Schema", 6);
      if (i == 1) entry.getCompound("Policy").putInt("Schema", 3);
      if (i == 2) entry.getCompound("Policy").putInt("Target", 577);
      if (i == 3) entry.getCompound("Policy").putLong("Target", 20);
      if (i == 4) bad.putInt("Schema", 3);
      assertThrows(IllegalArgumentException.class, () -> BufferStateCodec.decode(bad));
    }
    assertThrows(IllegalArgumentException.class, () -> new BufferPolicy(policy().filter(), 8, -1));
  }

  @Test
  void wornTermsRoundTripInBothCodecsAndNeedTheirThreshold() {
    var filter =
        new ResourceFilter(
            ResourceKind.ITEM,
            ResourceFilter.Mode.INCLUDE,
            List.of(
                new ResourceFilter.Worn("minecraft:iron_pickaxe", 25),
                new ResourceFilter.Registry("minecraft:coal")));
    var policy = new BufferPolicy(filter, 16, 64);
    var tag = BufferPolicyCodec.encode(policy);
    assertEquals(policy, BufferPolicyCodec.decode(tag));
    var worn = tag.getList("Terms", Tag.TAG_COMPOUND).getCompound(0);
    assertEquals("WORN", worn.getString("Type"));
    assertEquals(25, worn.getInt("Below"));
    var missing = tag.copy();
    missing.getList("Terms", Tag.TAG_COMPOUND).getCompound(0).remove("Below");
    assertThrows(IllegalArgumentException.class, () -> BufferPolicyCodec.decode(missing));
    var zero = tag.copy();
    zero.getList("Terms", Tag.TAG_COMPOUND).getCompound(0).putInt("Below", 0);
    assertThrows(IllegalArgumentException.class, () -> BufferPolicyCodec.decode(zero));
    var extra = tag.copy();
    extra.getList("Terms", Tag.TAG_COMPOUND).getCompound(1).putInt("Below", 5);
    assertThrows(
        IllegalArgumentException.class,
        () -> BufferPolicyCodec.decode(extra),
        "Only a worn term has a threshold");

    var shared = FilterCodec.encode(filter);
    assertEquals(filter, FilterCodec.decode(shared, "test"));
    var sharedMissing = shared.copy();
    sharedMissing.getList("Terms", Tag.TAG_COMPOUND).getCompound(0).remove("Below");
    assertThrows(IllegalArgumentException.class, () -> FilterCodec.decode(sharedMissing, "test"));
  }
}
