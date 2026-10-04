package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NodeRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodePersistenceTest {
  @TempDir Path folder;
  private static HolderLookup.Provider registries;
  private static final NodeRegistry.Position POSITION =
      new NodeRegistry.Position("minecraft:overworld", 42);

  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
  }

  private DimensionDataStorage storage() {
    return new DimensionDataStorage(folder.toFile(), DataFixers.getDataFixer(), registries);
  }

  private CompoundTag fixture() throws Exception {
    try (var input = getClass().getResourceAsStream("/persistence/registry-v3.snbt")) {
      assertNotNull(input);
      return TagParser.parseTag(new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void fixtureProtectsStructuralPoliciesAndCanonicalBindings() throws Exception {
    var original = fixture();
    var loaded = DeviceSavedData.load(original, registries);
    assertEquals("", loaded.diagnostic());
    assertFalse(loaded.isDirty());
    assertEquals(original, loaded.save(new CompoundTag(), registries));
    var root =
        new NodeRegistry.Handle(new UUID(0, 11), new UUID(0, 101), 2, NodeRegistry.Kind.CONTROLLER);
    assertEquals(POSITION, loaded.nodes().definition(root).position());
    var gate =
        new NodeRegistry.Handle(root.world(), new UUID(0, 102), 3, NodeRegistry.Kind.GATEWAY);
    var policy = loaded.nodes().definition(gate).policy();
    assertEquals(
        Set.of(NetworkPermissions.Permission.VIEW, NetworkPermissions.Permission.WITHDRAW),
        policy.branchPermissions());
    assertEquals(Set.of(), policy.inheritedPermissions());
    assertEquals(
        Set.of(dev.izzy.factorycore.core.resource.ResourceKind.FLUID), policy.inheritedKinds());
    assertEquals(new UUID(0, 201), loaded.network(root.id()).snapshot().owner());
  }

  @Test
  void compressedDiskMovementKeepsNetworkOwnerAndGrants() throws Exception {
    var storage = storage();
    var path = folder.resolve(DeviceSavedData.NAME + ".dat");
    var saved = DeviceSavedData.open(storage, path);
    var owner = new UUID(0, 501);
    var collaborator = new UUID(0, 502);
    var root = saved.createController(owner, POSITION);
    saved
        .network(root.id())
        .setGrants(
            new NetworkPermissions.Actor(owner, false),
            collaborator,
            Set.of(NetworkPermissions.Permission.VIEW));
    var portable = saved.nodes().detach(root, POSITION).handle();
    storage.save();
    IOUtilities.waitUntilIOWorkerComplete();
    var loaded = DeviceSavedData.open(storage(), path);
    assertEquals(NodeRegistry.Failure.STALE, loaded.nodes().validate(root));
    assertNull(loaded.nodes().definition(portable).position());
    var moved =
        loaded
            .nodes()
            .attach(portable, new NodeRegistry.Position("minecraft:overworld", 43))
            .handle();
    assertEquals(root.id(), moved.id());
    assertEquals(owner, loaded.network(moved.id()).snapshot().owner());
    assertTrue(
        loaded
            .network(moved.id())
            .allows(
                new NetworkPermissions.Actor(collaborator, false),
                NetworkPermissions.Permission.VIEW));
    loaded.setDirty(false);
    assertThrows(
        IllegalStateException.class,
        () ->
            loaded.createController(
                collaborator, new NodeRegistry.Position("minecraft:overworld", 43)));
    assertFalse(loaded.isDirty());
    assertEquals(
        1, loaded.save(new CompoundTag(), registries).getList("Networks", Tag.TAG_COMPOUND).size());
  }

  @Test
  void structuralAdmissionCannotLeaveAnOrphanNetwork() throws Exception {
    var tag = fixture();
    var nodes = new ListTag();
    var prototype = tag.getList("Nodes", Tag.TAG_COMPOUND).getCompound(1);
    for (int i = 0; i < NodeRegistry.MAX_NODES; i++) {
      var node = prototype.copy();
      node.putUUID("Id", new UUID(1, i));
      nodes.add(node);
    }
    tag.put("Nodes", nodes);
    var loaded = DeviceSavedData.load(tag, registries);
    assertEquals("", loaded.diagnostic());
    assertThrows(
        IllegalStateException.class, () -> loaded.createController(new UUID(0, 501), POSITION));
    assertFalse(loaded.isDirty());
    assertEquals(tag, loaded.save(new CompoundTag(), registries));
  }

  private void reject(CompoundTag tag) {
    var loaded = DeviceSavedData.load(tag, registries);
    assertFalse(loaded.diagnostic().isEmpty());
    assertThrows(IllegalStateException.class, loaded::nodes);
    assertEquals(tag, loaded.save(new CompoundTag(), registries));
    assertThrows(IllegalStateException.class, loaded::setDirty);
  }

  @Test
  void unknownOrCollidingStructuralStatePreservesOriginalSave() throws Exception {
    var tag = fixture();
    tag.remove("Nodes");
    reject(tag);
    tag = fixture();
    tag.getList("Nodes", Tag.TAG_COMPOUND)
        .add(tag.getList("Nodes", Tag.TAG_COMPOUND).getCompound(0).copy());
    reject(tag);
    tag = fixture();
    tag.getList("Nodes", Tag.TAG_COMPOUND)
        .getCompound(1)
        .put(
            "Position",
            tag.getList("Nodes", Tag.TAG_COMPOUND).getCompound(0).getCompound("Position").copy());
    reject(tag);
    for (int mask : new int[] {-1, 128}) {
      tag = fixture();
      tag.getList("Nodes", Tag.TAG_COMPOUND)
          .getCompound(1)
          .getCompound("Policy")
          .putInt("Branch", mask);
      reject(tag);
    }
    tag = fixture();
    tag.getList("Nodes", Tag.TAG_COMPOUND).getCompound(1).getCompound("Policy").putInt("Kinds", 8);
    reject(tag);
    tag = fixture();
    tag.getList("Nodes", Tag.TAG_COMPOUND).getCompound(0).putUUID("Id", new UUID(0, 103));
    reject(tag);
    tag = fixture();
    tag.getList("Nodes", Tag.TAG_COMPOUND).getCompound(1).putUUID("Id", new UUID(0, 101));
    reject(tag);
    tag = fixture();
    tag.getList("Nodes", Tag.TAG_COMPOUND).getCompound(1).putUUID("Id", new UUID(0, 1));
    reject(tag);
    tag = fixture();
    tag.getList("Nodes", Tag.TAG_COMPOUND).getCompound(1).putString("FutureOwnedState", "retain");
    reject(tag);
    tag = fixture();
    var nodes = new ListTag();
    for (int i = 0; i <= NodeRegistry.MAX_NODES; i++)
      nodes.add(tag.getList("Nodes", Tag.TAG_COMPOUND).getCompound(1).copy());
    tag.put("Nodes", nodes);
    reject(tag);
  }
}
