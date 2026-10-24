package dev.izzy.factorycore.core.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.Collections;
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
  void conflictRepairRetainsSlotsPolicyPrincipalAndChargeWithoutRenewingDeployment() {
    var nodes = new NodeRegistry(WORLD, List.of(), () -> {});
    int[] dirty = {0};
    var buffers = new BufferRegistry(nodes, List.of(), () -> dirty[0]++);
    buffers.stackLimit(key -> 64);
    var old = create(nodes, buffers, 1);
    var owner = new Object();
    buffers.bind(old, position(1), owner);
    buffers.inventory(old, owner).insertSlot(8, KEY, 23, false);
    var policy = new BufferPolicy(ResourceFilter.all(ResourceKind.ITEM), 3, 17);
    var actor = UUID.randomUUID();
    var root = UUID.randomUUID();
    var deployment =
        buffers.configure(old, owner, 1, actor, root, root, BufferRegistry.Mode.STOCK, policy);
    var inventory = buffers.evidence(old.id());
    long bytes = buffers.bytes();
    int changes = dirty[0];
    nodes.attach(old, position(2));
    assertEquals(NodeRegistry.Failure.OFFLINE, buffers.repairConflict(old, position(2)).failure());
    assertTrue(buffers.boundTo(old, owner));
    assertEquals(inventory, buffers.evidence(old.id()));
    var current = buffers.repairConflict(old, position(1)).handle();
    assertNotNull(current);
    assertFalse(buffers.boundTo(old, owner));
    assertFalse(buffers.usable(current, owner));
    assertEquals(changes, dirty[0], "Owned slots and deployment did not change");
    assertEquals(inventory, buffers.evidence(old.id()));
    assertEquals(deployment, buffers.deployment(old.id()));
    assertNotEquals(current.generation(), deployment.leaseGeneration());
    assertEquals(bytes, buffers.bytes());
    var replacement = new Object();
    assertTrue(buffers.bind(current, position(1), replacement));
    assertFalse(buffers.usable(old, owner));
    assertFalse(buffers.bind(old, position(1), owner));
    assertEquals(23, buffers.inventory(current, replacement).total(KEY));
    var restored =
        new BufferRegistry(
            new NodeRegistry(WORLD, nodes.snapshot(), () -> {}),
            buffers.snapshot(),
            buffers.deployments(),
            () -> {});
    assertEquals(inventory, restored.evidence(old.id()));
    assertEquals(deployment, restored.deployment(old.id()));
    assertEquals(bytes, restored.bytes());
    assertFalse(restored.usable(current, replacement));
    var reapplied =
        buffers.configure(
            current,
            replacement,
            deployment.revision(),
            actor,
            root,
            root,
            BufferRegistry.Mode.STOCK,
            policy);
    assertEquals(current.generation(), reapplied.leaseGeneration());
    assertEquals(deployment.revision() + 1, reapplied.revision());
    assertEquals(actor, reapplied.principal());
    assertEquals(policy, reapplied.policy());
    assertEquals(inventory, buffers.evidence(old.id()));
  }

  @Test
  void failedBufferRecoveryKeepsBindingInventoryDeploymentAndAccounting() {
    var nodes = new NodeRegistry(WORLD, List.of(), () -> {});
    var buffers = new BufferRegistry(nodes, List.of(), () -> {});
    buffers.stackLimit(key -> 64);
    var old = create(nodes, buffers, 1);
    var owner = new Object();
    buffers.bind(old, position(1), owner);
    buffers.inventory(old, owner).insert(KEY, 19);
    var before = buffers.evidence(old.id());
    long bytes = buffers.bytes();
    assertEquals(
        NodeRegistry.Failure.NOT_CONFLICTED, buffers.repairConflict(old, position(1)).failure());
    nodes.attach(old, position(2));
    assertEquals(
        NodeRegistry.Failure.FOREIGN_WORLD,
        buffers
            .repairConflict(
                new NodeRegistry.Handle(UUID.randomUUID(), old.id(), old.generation(), old.kind()),
                position(1))
            .failure());
    assertEquals(
        NodeRegistry.Failure.STALE,
        buffers
            .repairConflict(
                new NodeRegistry.Handle(WORLD, old.id(), old.generation() + 1, old.kind()),
                position(1))
            .failure());
    assertEquals(
        NodeRegistry.Failure.UNKNOWN,
        buffers
            .repairConflict(
                new NodeRegistry.Handle(WORLD, UUID.randomUUID(), 1, old.kind()), position(1))
            .failure());
    assertEquals(before, buffers.evidence(old.id()));
    assertTrue(buffers.boundTo(old, owner));
    assertEquals(bytes, buffers.bytes());
    assertThrows(IllegalArgumentException.class, () -> buffers.evidence(UUID.randomUUID()));
    var exhausted =
        new NodeRegistry(
            WORLD,
            List.of(
                new NodeRegistry.Stored(
                    old.id(), old.kind(), Long.MAX_VALUE, position(1), true, null)),
            () -> fail("Overflow dirtied authority"));
    var restored =
        new BufferRegistry(exhausted, List.of(before), () -> fail("Overflow changed stock"));
    assertEquals(
        NodeRegistry.Failure.LIMIT,
        restored
            .repairConflict(
                new NodeRegistry.Handle(WORLD, old.id(), Long.MAX_VALUE, old.kind()), position(1))
            .failure());
    assertEquals(before, restored.evidence(old.id()));
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

  @Test
  void policiesShareMetadataAdmissionRetainAcrossPauseAndReclaimOnClear() {
    var nodes = new NodeRegistry(WORLD, List.of(), () -> {});
    var buffers = new BufferRegistry(nodes, List.of(), () -> {});
    var handle = create(nodes, buffers, 1);
    var owner = new Object();
    buffers.bind(handle, position(1), owner);
    var actor = new UUID(0, 9);
    var root = new UUID(0, 10);
    var filter =
        new ResourceFilter(
            ResourceKind.ITEM,
            ResourceFilter.Mode.INCLUDE,
            List.of(
                new ResourceFilter.Exact(
                    new ResourceKey(ResourceKind.ITEM, "test:sample", new byte[60000]))));
    var policy = new BufferPolicy(filter, 16);
    long before = buffers.bytes();
    var deployed =
        buffers.configure(handle, owner, 1, actor, root, root, BufferRegistry.Mode.IMPORT, policy);
    assertEquals(before + policy.bytes(), buffers.bytes());
    var paused =
        buffers.configure(
            handle, owner, deployed.revision(), actor, root, root, BufferRegistry.Mode.PAUSED);
    assertEquals(policy, paused.policy());
    assertEquals(before + policy.bytes(), buffers.bytes());
    var restored =
        new BufferRegistry(
            new NodeRegistry(WORLD, nodes.snapshot(), () -> {}),
            buffers.snapshot(),
            buffers.deployments(),
            () -> {});
    assertEquals(buffers.bytes(), restored.bytes());
    assertEquals(paused, restored.deployment(handle.id()));
    assertFalse(restored.usable(handle, owner));
    buffers.configure(
        handle,
        owner,
        paused.revision(),
        actor,
        root,
        root,
        BufferRegistry.Mode.PAUSED,
        BufferPolicy.DEFAULT);
    assertEquals(before, buffers.bytes());
  }

  @Test
  void policyQuotaFailureDoesNotReplaceAuthorityOrAdvanceRevision() {
    var nodes = new NodeRegistry(WORLD, List.of(), () -> {});
    int[] dirty = {0};
    var buffers = new BufferRegistry(nodes, List.of(), () -> dirty[0]++);
    var key = new ResourceKey(ResourceKind.ITEM, "test:large", new byte[65536]);
    var policy =
        new BufferPolicy(
            new ResourceFilter(
                ResourceKind.ITEM,
                ResourceFilter.Mode.INCLUDE,
                Collections.nCopies(3, new ResourceFilter.Exact(key))),
            8);
    var actor = new UUID(0, 9);
    var root = new UUID(0, 10);
    boolean rejected = false;
    for (int i = 1; i < BufferRegistry.MAX_BUFFERS; i++) {
      var handle = create(nodes, buffers, i);
      var owner = new Object();
      buffers.bind(handle, position(i), owner);
      long before = buffers.bytes();
      int changes = dirty[0];
      try {
        buffers.configure(handle, owner, 1, actor, root, root, BufferRegistry.Mode.IMPORT, policy);
      } catch (IllegalStateException full) {
        assertEquals(before, buffers.bytes());
        assertEquals(changes, dirty[0]);
        assertNull(buffers.deployment(handle.id()));
        rejected = true;
        break;
      }
    }
    assertTrue(rejected);
    assertTrue(buffers.bytes() <= BufferRegistry.MAX_BYTES);
    var restored =
        new BufferRegistry(
            new NodeRegistry(WORLD, nodes.snapshot(), () -> {}),
            buffers.snapshot(),
            buffers.deployments(),
            () -> {});
    assertEquals(buffers.bytes(), restored.bytes());
  }

  /** U11: a buffer retires only when empty and unbound, then reloads without its lease. */
  @Test
  void retireDropsOnlyEmptyUnboundBuffersAndTheirDeployment() {
    var nodes = new NodeRegistry(WORLD, List.of(), () -> {});
    var buffers = new BufferRegistry(nodes, List.of(), () -> {});
    buffers.stackLimit(key -> 64);
    var placed = create(nodes, buffers, 1);
    var owner = new Object();
    assertTrue(buffers.bind(placed, position(1), owner));
    assertEquals(5, buffers.inventory(placed, owner).insertSlot(0, KEY, 5, false));
    assertFalse(buffers.retire(placed.id()), "A bound buffer is kept");
    buffers.unbind(placed, owner);
    assertFalse(buffers.retire(placed.id()), "Items are never discarded");
    assertTrue(buffers.bind(placed, position(1), owner));
    assertEquals(5, buffers.inventory(placed, owner).extractSlot(0, 5, false).count());
    buffers.unbind(placed, owner);
    assertTrue(buffers.retire(placed.id()));
    assertEquals(0, buffers.bytes(), "The buffer's charge is released");
    var portable = nodes.detach(placed, position(1)).handle();
    assertEquals(NodeRegistry.Failure.OK, nodes.retire(portable));
    var nodeCopy = new NodeRegistry(WORLD, nodes.snapshot(), () -> {});
    assertTrue(new BufferRegistry(nodeCopy, buffers.snapshot(), () -> {}).snapshot().isEmpty());
  }
}
