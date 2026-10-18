package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.*;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class TransferContextTest {
  @TempDir Path folder;
  private static HolderLookup.Provider registries;

  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
  }

  @AfterEach
  void finishIo() {
    IOUtilities.waitUntilIOWorkerComplete();
  }

  private static TransferContext context(UUID world, UUID network, UUID source, UUID target) {
    return new TransferContext(
        world,
        network,
        network,
        new NodeRegistry.Position("minecraft:overworld", 42),
        new TransferContext.Endpoint(TransferContext.Type.DIGITAL, source, source, 1, 0),
        new TransferContext.Endpoint(
            TransferContext.Type.EXTERNAL, UUID.randomUUID(), target, 2, 3));
  }

  private static ResourcePort port(UUID backing) {
    return new ResourcePort() {
      @Override
      public UUID backingId() {
        return backing;
      }

      @Override
      public long simulateInsert(ResourceKey key, long amount) {
        return amount;
      }

      @Override
      public long extract(ResourceKey key, long amount) {
        return amount;
      }

      @Override
      public long insert(ResourceKey key, long amount) {
        return 0;
      }
    };
  }

  @Test
  void committedContextFixtureKeepsExactScopeAndReferenceTypes() throws Exception {
    try (var input = getClass().getResourceAsStream("/persistence/transfer-context-v1.snbt")) {
      assertNotNull(input);
      var tag =
          net.minecraft.nbt.TagParser.parseTag(
              new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
      var context = TransferContextCodec.decode(tag);
      assertEquals(new UUID(0, 102), context.segment());
      assertEquals(42, context.origin().block());
      assertEquals(2, context.source().generation());
      assertEquals(4, context.destination().configuration());
      assertEquals(tag, TransferContextCodec.encode(context));
    }
  }

  @Test
  void contextRoundTripIsStrictAndDoesNotPersistOperatorAuthority() {
    var context =
        context(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    var tag = TransferContextCodec.encode(context);
    assertEquals(context, TransferContextCodec.decode(tag));
    var future = tag.copy();
    future.putInt("Schema", 2);
    assertThrows(IllegalArgumentException.class, () -> TransferContextCodec.decode(future));
    tag.putBoolean("Operator", true);
    assertThrows(IllegalArgumentException.class, () -> TransferContextCodec.decode(tag));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TransferContext.Endpoint(
                TransferContext.Type.DIGITAL, UUID.randomUUID(), UUID.randomUUID(), 1, 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TransferContext.Endpoint(
                TransferContext.Type.EXTERNAL, UUID.randomUUID(), UUID.randomUUID(), 1, 0));
  }

  @Test
  void mismatchedContextRejectsBeforeAnyPortMutationAndSurvivesCanonicalSnapshots() {
    var registry = new TransferRegistry(List.of(), () -> {});
    var source = UUID.randomUUID();
    var target = UUID.randomUUID();
    var context = context(UUID.randomUUID(), UUID.randomUUID(), source, target);
    var id = UUID.randomUUID();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            registry.start(
                id,
                UUID.randomUUID(),
                ResourceKey.ENERGY,
                port(UUID.randomUUID()),
                port(target),
                10,
                10,
                () -> true,
                context));
    assertFalse(registry.contains(id));
    var move =
        registry.start(
            id,
            UUID.randomUUID(),
            ResourceKey.ENERGY,
            port(source),
            port(target),
            10,
            10,
            () -> true,
            context);
    assertEquals(10, move.staged());
    var loaded = new TransferRegistry(registry.snapshot(), () -> {});
    assertEquals(context, loaded.context(id));
    assertEquals(10, loaded.get(id).staged());
    assertEquals(OperationResult.Reason.OFFLINE, loaded.deliver(id).reason());
  }

  @Test
  void compressedCanonicalDiskKeepsContextQuantitiesAndGrantsTogether() {
    var file = folder.resolve(DeviceSavedData.NAME + ".dat");
    var storage = new DimensionDataStorage(folder.toFile(), DataFixers.getDataFixer(), registries);
    var saved = DeviceSavedData.open(storage, file);
    var owner = UUID.randomUUID();
    var root = saved.createController(owner, new NodeRegistry.Position("minecraft:overworld", 42));
    var source = UUID.randomUUID();
    var target = UUID.randomUUID();
    var context = context(saved.registry().world(), root.id(), source, target);
    var id = UUID.randomUUID();
    saved
        .transfers()
        .start(
            id, owner, ResourceKey.ENERGY, port(source), port(target), 37, 37, () -> true, context);
    storage.save();
    IOUtilities.waitUntilIOWorkerComplete();
    var loaded =
        DeviceSavedData.open(
            new DimensionDataStorage(folder.toFile(), DataFixers.getDataFixer(), registries), file);
    assertEquals("", loaded.diagnostic());
    assertEquals(context, loaded.transfers().context(id));
    assertEquals(37, loaded.transfers().get(id).staged());
    assertEquals(owner, loaded.network(root.id()).snapshot().owner());
    assertEquals(
        DeviceSavedData.SCHEMA, loaded.save(new CompoundTag(), registries).getInt("Schema"));
    var malformed = loaded.save(new CompoundTag(), registries);
    var original = malformed.copy();
    malformed
        .getList("Transfers", net.minecraft.nbt.Tag.TAG_COMPOUND)
        .getCompound(0)
        .getCompound("Context")
        .getCompound("Source")
        .putUUID("Backing", UUID.randomUUID());
    var rejected = DeviceSavedData.load(malformed, registries);
    assertFalse(rejected.diagnostic().isEmpty());
    assertEquals(malformed, rejected.save(new CompoundTag(), registries));
    assertNotEquals(original, malformed);
    var unknownWorld = original.copy();
    unknownWorld
        .getList("Transfers", net.minecraft.nbt.Tag.TAG_COMPOUND)
        .getCompound(0)
        .getCompound("Context")
        .putUUID("World", UUID.randomUUID());
    var foreign = DeviceSavedData.load(unknownWorld, registries);
    assertFalse(foreign.diagnostic().isEmpty());
    assertEquals(unknownWorld, foreign.save(new CompoundTag(), registries));
  }
}
