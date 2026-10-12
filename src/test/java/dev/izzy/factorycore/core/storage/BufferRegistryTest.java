package dev.izzy.factorycore.core.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BufferRegistryTest {
  private static final UUID WORLD = new UUID(0, 101);
  private static final ResourceKey KEY =
      new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[] {1});

  private static NodeRegistry.Position position(long index) {
    return new NodeRegistry.Position("minecraft:overworld", index);
  }

  private static NodeRegistry.Handle create(NodeRegistry nodes, BufferRegistry buffers, int index) {
    var handle =
        nodes
            .create(
                new UUID(0, 1000 + index), NodeRegistry.Kind.BUFFERED_INTERFACE, position(index))
            .handle();
    buffers.create(handle);
    return handle;
  }

  @Test
  void ownershipMovementAndUnloadedRestoreRetainOneExactSlotInventory() {
    var nodes = new NodeRegistry(WORLD, List.of(), () -> {});
    var buffers = new BufferRegistry(nodes, List.of(), () -> {});
    buffers.stackLimit(key -> 64);
    var placed = create(nodes, buffers, 1);
    var owner = new Object();
    assertTrue(buffers.bind(placed, position(1), owner));
    var inventory = buffers.inventory(placed, owner);
    assertEquals(23, inventory.insertSlot(8, KEY, 23, false));
    long bytes = buffers.bytes();
    assertFalse(buffers.bind(placed, position(1), new Object()));
    assertFalse(buffers.usable(placed, new Object()));
    buffers.unbind(placed, new Object());
    assertTrue(buffers.usable(placed, owner));
    buffers.unbind(placed, owner);
    assertFalse(buffers.usable(placed, owner));
    var portable = nodes.detach(placed, position(1)).handle();
    var moved = nodes.attach(portable, position(2)).handle();
    assertFalse(buffers.bind(placed, position(2), owner));
    var replacement = new Object();
    assertTrue(buffers.bind(moved, position(2), replacement));
    assertFalse(buffers.usable(placed, owner));
    assertEquals(23, buffers.inventory(moved, replacement).total(KEY));
    assertEquals(bytes, buffers.bytes());
    var nodeCopy = new NodeRegistry(WORLD, nodes.snapshot(), () -> {});
    var restored = new BufferRegistry(nodeCopy, buffers.snapshot(), () -> {});
    restored.stackLimit(key -> 64);
    assertFalse(restored.usable(moved, replacement));
    assertThrows(IllegalStateException.class, () -> restored.inventory(moved, replacement));
    var reloadOwner = new Object();
    assertTrue(restored.bind(moved, position(2), reloadOwner));
    assertEquals(new ItemBuffer.Slot(KEY, 23, 64), restored.inventory(moved, reloadOwner).slot(8));
    assertEquals(buffers.snapshot(), restored.snapshot());
  }

  @Test
  void copiedPlacedLeaseQuarantinesAccessWithoutDroppingCanonicalContents() {
    var nodes = new NodeRegistry(WORLD, List.of(), () -> {});
    var buffers = new BufferRegistry(nodes, List.of(), () -> {});
    buffers.stackLimit(key -> 64);
    var placed = create(nodes, buffers, 1);
    var owner = new Object();
    buffers.bind(placed, position(1), owner);
    buffers.inventory(placed, owner).insert(KEY, 64);
    assertEquals(NodeRegistry.Failure.CONFLICT, nodes.attach(placed, position(2)).failure());
    assertFalse(buffers.usable(placed, owner));
    assertFalse(buffers.bind(placed, position(2), new Object()));
    assertTrue(buffers.boundTo(placed, owner));
    assertFalse(buffers.boundTo(placed, new Object()));
    assertEquals(64, buffers.snapshot().getFirst().slots().getFirst().count());
  }

  @Test
  void metadataQuotaRejectsBeforeSlotMutationAndExtractionReclaimsCapacity() {
    var nodes = new NodeRegistry(WORLD, List.of(), () -> {});
    var buffers = new BufferRegistry(nodes, List.of(), () -> {});
    buffers.stackLimit(key -> 64);
    var large = new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[65536]);
    ItemBuffer first = null;
    ItemBuffer rejected = null;
    for (int index = 0; index < 40; index++) {
      var handle = create(nodes, buffers, index);
      var owner = new Object();
      buffers.bind(handle, position(index), owner);
      var inventory = buffers.inventory(handle, owner);
      if (first == null) first = inventory;
      long admitted = inventory.simulateInsert(large, 576);
      long accepted = inventory.insert(large, 576);
      assertEquals(admitted, accepted);
      if (accepted == 0) {
        rejected = inventory;
        break;
      }
    }
    assertNotNull(rejected);
    var original = rejected.snapshot();
    assertEquals(0, rejected.insert(large, Long.MAX_VALUE));
    assertEquals(original, rejected.snapshot());
    assertTrue(buffers.bytes() <= BufferRegistry.MAX_BYTES);
    long before = buffers.bytes();
    assertEquals(576, first.extract(large, 576));
    assertTrue(buffers.bytes() < before);
    assertEquals(576, rejected.insert(large, 576));
    assertTrue(buffers.bytes() <= BufferRegistry.MAX_BYTES);
  }

  @Test
  void missingDuplicateOrUnownedInventoriesRejectAndCountCeilingPreflightsCreation() {
    var nodes = new NodeRegistry(WORLD, List.of(), () -> {});
    var buffers = new BufferRegistry(nodes, List.of(), () -> {});
    create(nodes, buffers, 0);
    var state = buffers.snapshot().getFirst();
    assertThrows(
        IllegalArgumentException.class, () -> new BufferRegistry(nodes, List.of(), () -> {}));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BufferRegistry(nodes, List.of(state, state), () -> {}));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BufferRegistry(
                new NodeRegistry(WORLD, List.of(), () -> {}), List.of(state), () -> {}));
    for (int index = 1; index < BufferRegistry.MAX_BUFFERS; index++) create(nodes, buffers, index);
    assertFalse(buffers.canCreate());
    assertEquals(BufferRegistry.MAX_BUFFERS, buffers.snapshot().size());
  }

  @Test
  void deploymentsReplaceOneOwnerWithIncreasingRevisionAndRestoreOffline() {
    var nodes = new NodeRegistry(WORLD, List.of(), () -> {});
    int[] changes = {0};
    var buffers = new BufferRegistry(nodes, List.of(), () -> changes[0]++);
    var handle = create(nodes, buffers, 1);
    var token = new Object();
    buffers.bind(handle, position(1), token);
    var actor = new UUID(0, 9);
    var root = new UUID(0, 10);
    var first = buffers.configure(handle, token, 1, actor, root, root, BufferRegistry.Mode.IMPORT);
    assertEquals(2, first.revision());
    var next =
        buffers.configure(
            handle, token, 1, new UUID(0, 11), root, root, BufferRegistry.Mode.PAUSED);
    assertEquals(3, next.revision());
    assertEquals(1, buffers.deployments().size());
    assertEquals(next, buffers.deployment(handle.id()));
    int dirty = changes[0];
    assertThrows(
        IllegalStateException.class,
        () ->
            buffers.configure(
                handle, new Object(), 3, actor, root, root, BufferRegistry.Mode.IMPORT));
    assertThrows(
        ArithmeticException.class,
        () ->
            buffers.configure(
                handle, token, Long.MAX_VALUE, actor, root, root, BufferRegistry.Mode.IMPORT));
    assertEquals(dirty, changes[0]);
    assertEquals(next, buffers.deployment(handle.id()));
    var restored =
        new BufferRegistry(
            new NodeRegistry(WORLD, nodes.snapshot(), () -> {}),
            buffers.snapshot(),
            buffers.deployments(),
            () -> {});
    assertEquals(next, restored.deployment(handle.id()));
    assertFalse(restored.usable(handle, token));
    assertThrows(
        IllegalStateException.class,
        () -> restored.configure(handle, token, 3, actor, root, root, BufferRegistry.Mode.IMPORT));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BufferRegistry(nodes, buffers.snapshot(), List.of(first, next), () -> {}));
  }

  @Test
  void movementDoesNotRenewDeploymentAuthorityAndFutureLeaseRejects() {
    var nodes = new NodeRegistry(WORLD, List.of(), () -> {});
    var buffers = new BufferRegistry(nodes, List.of(), () -> {});
    var handle = create(nodes, buffers, 1);
    var token = new Object();
    buffers.bind(handle, position(1), token);
    var root = new UUID(0, 10);
    var deployment =
        buffers.configure(handle, token, 1, new UUID(0, 9), root, root, BufferRegistry.Mode.IMPORT);
    buffers.unbind(handle, token);
    var moved = nodes.attach(nodes.detach(handle, position(1)).handle(), position(2)).handle();
    assertNotEquals(moved.generation(), deployment.leaseGeneration());
    assertEquals(deployment, buffers.deployment(handle.id()));
    var future =
        new BufferRegistry.Deployment(
            handle.id(),
            2,
            Long.MAX_VALUE,
            deployment.principal(),
            root,
            root,
            BufferRegistry.Mode.IMPORT);
    assertThrows(
        IllegalArgumentException.class,
        () -> new BufferRegistry(nodes, buffers.snapshot(), List.of(future), () -> {}));
  }
}
