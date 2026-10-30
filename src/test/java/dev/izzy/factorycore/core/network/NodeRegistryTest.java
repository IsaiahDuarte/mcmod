package dev.izzy.factorycore.core.network;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NodeRegistryTest {
  private static final UUID WORLD = new UUID(0, 11);
  private static final UUID ID = new UUID(0, 1);
  private static final NodeRegistry.Position FIRST =
      new NodeRegistry.Position("minecraft:overworld", 42);
  private static final NodeRegistry.Position SECOND =
      new NodeRegistry.Position("minecraft:overworld", 43);

  @Test
  void movementConsumesGenerationsAndReloadPreservesPolicy() {
    int[] changes = {0};
    var registry = new NodeRegistry(WORLD, List.of(), () -> changes[0]++);
    var handle = registry.create(ID, NodeRegistry.Kind.GATEWAY, FIRST).handle();
    assertEquals(NodeRegistry.Failure.OK, registry.attach(handle, FIRST).failure());
    assertEquals(1, changes[0]);
    var portable = registry.detach(handle, FIRST).handle();
    assertEquals(NodeRegistry.Failure.STALE, registry.validate(handle));
    assertNull(registry.definition(portable).position());
    var moved = registry.attach(portable, SECOND).handle();
    assertEquals(3, moved.generation());
    assertEquals(NodeRegistry.Failure.STALE, registry.validate(portable));
    var restored = new NodeRegistry(WORLD, registry.snapshot(), () -> {});
    assertEquals(GatewayPolicy.open(), restored.definition(moved).policy());
    assertEquals(SECOND, restored.definition(moved).position());
    assertEquals(
        NodeRegistry.Failure.FOREIGN_WORLD,
        restored.validate(
            new NodeRegistry.Handle(new UUID(0, 12), ID, 3, NodeRegistry.Kind.GATEWAY)));
    assertEquals(
        NodeRegistry.Failure.STALE,
        restored.validate(new NodeRegistry.Handle(WORLD, ID, 3, NodeRegistry.Kind.CONTROLLER)));
    assertEquals(
        NodeRegistry.Failure.UNKNOWN,
        restored.validate(
            new NodeRegistry.Handle(WORLD, new UUID(0, 2), 1, NodeRegistry.Kind.CONTROLLER)));
  }

  @Test
  void currentCopyQuarantinesButStaleCopyCannotMoveOwner() {
    var registry = new NodeRegistry(WORLD, List.of(), () -> {});
    var handle = registry.create(ID, NodeRegistry.Kind.CONTROLLER, FIRST).handle();
    assertEquals(NodeRegistry.Failure.CONFLICT, registry.attach(handle, SECOND).failure());
    assertEquals(NodeRegistry.Failure.CONFLICT, registry.validate(handle));
    assertEquals(NodeRegistry.Failure.CONFLICT, registry.detach(handle, FIRST).failure());
    assertTrue(registry.snapshot().getFirst().conflict());
    var restored = new NodeRegistry(WORLD, registry.snapshot(), () -> {});
    assertEquals(NodeRegistry.Failure.CONFLICT, restored.validate(handle));
    assertEquals(NodeRegistry.Failure.OCCUPIED, restored.admission(FIRST));
    assertEquals(NodeRegistry.Failure.OK, restored.admission(SECOND));
  }

  @Test
  void occupiedAndOverflowTransitionsPreserveBothOwners() {
    var registry = new NodeRegistry(WORLD, List.of(), () -> {});
    var first = registry.create(ID, NodeRegistry.Kind.CONTROLLER, FIRST).handle();
    var second = registry.create(new UUID(0, 2), NodeRegistry.Kind.GATEWAY, SECOND).handle();
    var portable = registry.detach(first, FIRST).handle();
    var before = registry.snapshot();
    assertEquals(NodeRegistry.Failure.OCCUPIED, registry.attach(portable, SECOND).failure());
    assertEquals(before, registry.snapshot());
    assertEquals(NodeRegistry.Failure.OK, registry.validate(second));
    var maximum =
        new NodeRegistry(
            WORLD,
            List.of(
                new NodeRegistry.Stored(
                    ID, NodeRegistry.Kind.CONTROLLER, Long.MAX_VALUE, FIRST, false, null)),
            () -> fail("Overflow must not notify"));
    var handle = new NodeRegistry.Handle(WORLD, ID, Long.MAX_VALUE, NodeRegistry.Kind.CONTROLLER);
    var original = maximum.snapshot();
    assertThrows(ArithmeticException.class, () -> maximum.detach(handle, FIRST));
    assertEquals(original, maximum.snapshot());
    var portableMax =
        new NodeRegistry(
            WORLD,
            List.of(
                new NodeRegistry.Stored(
                    ID, NodeRegistry.Kind.CONTROLLER, Long.MAX_VALUE, null, false, null)),
            () -> fail("Overflow must not notify"));
    assertThrows(ArithmeticException.class, () -> portableMax.attach(handle, FIRST));
    assertNull(portableMax.definition(handle).position());
  }

  @Test
  void admissionAndDuplicateRestoreRejectWithoutReplacement() {
    var records = new ArrayList<NodeRegistry.Stored>();
    for (int i = 0; i < NodeRegistry.MAX_NODES; i++)
      records.add(
          new NodeRegistry.Stored(
              new UUID(1, i), NodeRegistry.Kind.GATEWAY, 1, null, false, GatewayPolicy.open()));
    var registry =
        new NodeRegistry(WORLD, records, () -> fail("Rejected admission must not notify"));
    assertEquals(
        NodeRegistry.Failure.LIMIT,
        registry.create(ID, NodeRegistry.Kind.CONTROLLER, FIRST).failure());
    assertEquals(records.size(), registry.snapshot().size());
    var duplicate =
        new NodeRegistry.Stored(ID, NodeRegistry.Kind.CONTROLLER, 1, FIRST, false, null);
    assertThrows(
        IllegalArgumentException.class,
        () -> new NodeRegistry(WORLD, List.of(duplicate, duplicate), () -> {}));
    var other =
        new NodeRegistry.Stored(
            new UUID(0, 2), NodeRegistry.Kind.CONTROLLER, 1, FIRST, false, null);
    assertThrows(
        IllegalArgumentException.class,
        () -> new NodeRegistry(WORLD, List.of(duplicate, other), () -> {}));
    records.add(duplicate);
    assertThrows(IllegalArgumentException.class, () -> new NodeRegistry(WORLD, records, () -> {}));
  }

  @Test
  void auditedConflictTransitionPreservesEveryKindPositionAndRestrictedGatewayPolicy() {
    var policy =
        new GatewayPolicy(
            Set.of(NetworkPermissions.Permission.VIEW),
            Set.of(NetworkPermissions.Permission.DEPOSIT),
            Set.of(ResourceKind.FLUID));
    for (var kind : NodeRegistry.Kind.values()) {
      var stored =
          new NodeRegistry.Stored(
              ID, kind, 7, FIRST, true, kind == NodeRegistry.Kind.GATEWAY ? policy : null);
      int[] dirty = {0};
      var registry = new NodeRegistry(WORLD, List.of(stored), () -> dirty[0]++);
      var old = new NodeRegistry.Handle(WORLD, ID, 7, kind);
      assertEquals(stored, registry.evidence(ID));
      assertEquals(NodeRegistry.Failure.OFFLINE, registry.repairConflict(old, SECOND).failure());
      assertEquals(List.of(stored), registry.snapshot());
      assertEquals(0, dirty[0]);
      var repaired = registry.repairConflict(old, FIRST);
      assertEquals(NodeRegistry.Failure.OK, repaired.failure());
      assertEquals(8, repaired.handle().generation());
      assertEquals(1, dirty[0]);
      assertEquals(stored.policy(), registry.definition(repaired.handle()).policy());
      assertEquals(FIRST, registry.definition(repaired.handle()).position());
      assertFalse(registry.definition(repaired.handle()).conflict());
      assertEquals(NodeRegistry.Failure.STALE, registry.attach(old, FIRST).failure());
      assertEquals(NodeRegistry.Failure.STALE, registry.attach(old, SECOND).failure());
      assertEquals(NodeRegistry.Failure.OCCUPIED, registry.admission(FIRST));
      var restored = new NodeRegistry(WORLD, registry.snapshot(), () -> {});
      assertEquals(NodeRegistry.Failure.OK, restored.validate(repaired.handle()));
      assertEquals(stored.policy(), restored.definition(repaired.handle()).policy());
      assertTrue(stored.conflict(), "Inspection evidence stays detached");
    }
  }

  @Test
  void structuralRecoveryRejectsInvalidPortableUnconflictedAndOverflowWithoutMutation() {
    var stored = new NodeRegistry.Stored(ID, NodeRegistry.Kind.CONTROLLER, 7, FIRST, true, null);
    var registry =
        new NodeRegistry(WORLD, List.of(stored), () -> fail("Rejected repair dirtied state"));
    assertEquals(
        NodeRegistry.Failure.FOREIGN_WORLD,
        registry
            .repairConflict(new NodeRegistry.Handle(UUID.randomUUID(), ID, 7, stored.kind()), FIRST)
            .failure());
    assertEquals(
        NodeRegistry.Failure.UNKNOWN,
        registry
            .repairConflict(
                new NodeRegistry.Handle(WORLD, UUID.randomUUID(), 7, stored.kind()), FIRST)
            .failure());
    assertEquals(
        NodeRegistry.Failure.STALE,
        registry
            .repairConflict(new NodeRegistry.Handle(WORLD, ID, 6, stored.kind()), FIRST)
            .failure());
    assertEquals(
        NodeRegistry.Failure.STALE,
        registry
            .repairConflict(new NodeRegistry.Handle(WORLD, ID, 7, NodeRegistry.Kind.GATEWAY), FIRST)
            .failure());
    assertEquals(List.of(stored), registry.snapshot());
    for (var position : new NodeRegistry.Position[] {FIRST, null}) {
      var unconflicted =
          new NodeRegistry(
              WORLD,
              List.of(new NodeRegistry.Stored(ID, stored.kind(), 7, position, false, null)),
              () -> fail("Rejected repair dirtied state"));
      assertEquals(
          NodeRegistry.Failure.NOT_CONFLICTED,
          unconflicted
              .repairConflict(new NodeRegistry.Handle(WORLD, ID, 7, stored.kind()), FIRST)
              .failure());
      assertEquals(position, unconflicted.evidence(ID).position());
    }
    var exhausted = new NodeRegistry.Stored(ID, stored.kind(), Long.MAX_VALUE, FIRST, true, null);
    var maximum = new NodeRegistry(WORLD, List.of(exhausted), () -> fail("Overflow dirtied state"));
    assertEquals(
        NodeRegistry.Failure.LIMIT,
        maximum
            .repairConflict(
                new NodeRegistry.Handle(WORLD, ID, Long.MAX_VALUE, stored.kind()), FIRST)
            .failure());
    assertEquals(List.of(exhausted), maximum.snapshot());
    assertNull(registry.evidence(UUID.randomUUID()));
  }

  @Test
  void structuralPagesAdvanceThroughAllKindsAndExposeCurrentGenerations() {
    int[] dirty = {0};
    var registry = new NodeRegistry(WORLD, List.of(), () -> dirty[0]++);
    var expected = new ArrayList<NodeRegistry.Handle>();
    for (int index = 35; index > 0; index--)
      expected.add(
          registry
              .create(
                  new UUID(0, index),
                  index % 2 == 0 ? NodeRegistry.Kind.CONNECTOR : NodeRegistry.Kind.GATEWAY,
                  new NodeRegistry.Position("minecraft:overworld", index))
              .handle());
    int before = dirty[0];
    var actual = new ArrayList<NodeRegistry.Handle>();
    UUID cursor = null;
    do {
      var page = registry.handles(cursor, 8);
      assertTrue(page.handles().size() <= 8);
      actual.addAll(page.handles());
      cursor = page.next();
    } while (cursor != null);
    assertEquals(
        expected.stream().sorted(Comparator.comparing(NodeRegistry.Handle::id)).toList(), actual);
    assertEquals(before, dirty[0]);
    var first = actual.getFirst();
    var moved = registry.detach(first, registry.definition(first).position()).handle();
    assertEquals(moved, registry.handles(null, 1).handles().getFirst());
    assertThrows(IllegalArgumentException.class, () -> registry.handles(null, 0));
  }

  /** Q108: a network's connectors page with their current generation, nobody else's. */
  @Test
  void pagesWithinASetVisitOnlyItsIdsWithCurrentGenerations() {
    var registry = new NodeRegistry(WORLD, List.of(), () -> {});
    var within = new java.util.TreeSet<UUID>();
    NodeRegistry.Handle first = null;
    for (int index = 1; index <= 10; index++) {
      var handle =
          registry
              .create(
                  new UUID(0, index),
                  NodeRegistry.Kind.CONNECTOR,
                  new NodeRegistry.Position("minecraft:overworld", index))
              .handle();
      if (index % 3 == 0) within.add(handle.id());
      if (index == 3) first = handle;
    }
    var moved = registry.detach(first, registry.definition(first).position()).handle();
    var page = registry.handles(within, null, 8);
    assertEquals(
        List.of(new UUID(0, 3), new UUID(0, 6), new UUID(0, 9)),
        page.handles().stream().map(NodeRegistry.Handle::id).toList());
    assertEquals(moved, page.handles().getFirst(), "the current generation, not a remembered one");
    assertNull(page.next());
    assertEquals(
        new UUID(0, 6), registry.handles(within, new UUID(0, 3), 1).handles().getFirst().id());
  }

  @Test
  void setPolicyReplacesOnlyAValidGatewayPolicyAndMarksDirty() {
    int[] changes = {0};
    var registry = new NodeRegistry(WORLD, List.of(), () -> changes[0]++);
    var gate = registry.create(ID, NodeRegistry.Kind.GATEWAY, FIRST).handle();
    var root = registry.create(new UUID(0, 2), NodeRegistry.Kind.CONTROLLER, SECOND).handle();
    int before = changes[0];
    var restricted =
        new GatewayPolicy(
            EnumSet.of(NetworkPermissions.Permission.VIEW),
            EnumSet.noneOf(NetworkPermissions.Permission.class),
            EnumSet.noneOf(ResourceKind.class));
    assertEquals(NodeRegistry.Failure.OK, registry.setPolicy(gate, restricted));
    assertEquals(before + 1, changes[0]);
    assertEquals(restricted, registry.definition(gate).policy());
    assertEquals(gate.generation(), registry.definition(gate).generation(), "Lease unchanged");
    assertEquals(NodeRegistry.Failure.UNKNOWN, registry.setPolicy(root, GatewayPolicy.open()));
    var stale =
        new NodeRegistry.Handle(WORLD, ID, gate.generation() + 5, NodeRegistry.Kind.GATEWAY);
    assertNotEquals(NodeRegistry.Failure.OK, registry.setPolicy(stale, GatewayPolicy.open()));
    assertEquals(restricted, registry.definition(gate).policy(), "Refusals change nothing");
    assertEquals(
        restricted,
        new NodeRegistry(WORLD, registry.snapshot(), () -> {}).definition(gate).policy());
  }

  @Test
  void adoptMovesTheCanonicalLeaseOnceAndKeepsPolicy() {
    int[] changes = {0};
    var registry = new NodeRegistry(WORLD, List.of(), () -> changes[0]++);
    var handle = registry.create(ID, NodeRegistry.Kind.GATEWAY, FIRST).handle();
    var policy = registry.definition(handle).policy();
    int before = changes[0];
    var adopted = registry.adopt(handle, SECOND);
    assertEquals(NodeRegistry.Failure.OK, adopted.failure());
    assertEquals(handle.generation() + 1, adopted.handle().generation());
    assertEquals(SECOND, registry.definition(adopted.handle()).position());
    assertEquals(policy, registry.definition(adopted.handle()).policy());
    assertEquals(NodeRegistry.Failure.STALE, registry.validate(handle));
    assertEquals(NodeRegistry.Failure.OK, registry.admission(FIRST), "Old position is released");
    assertEquals(NodeRegistry.Failure.OCCUPIED, registry.admission(SECOND));
    assertEquals(before + 1, changes[0]);
    var restored = new NodeRegistry(WORLD, registry.snapshot(), () -> {});
    assertEquals(SECOND, restored.definition(adopted.handle()).position());
  }

  @Test
  void adoptRefusesStaleSameOccupiedAndPortableWithoutMutation() {
    var registry = new NodeRegistry(WORLD, List.of(), () -> {});
    var handle = registry.create(ID, NodeRegistry.Kind.CONTROLLER, FIRST).handle();
    var other = registry.create(new UUID(9, 9), NodeRegistry.Kind.GATEWAY, SECOND).handle();
    var before = registry.snapshot();
    assertEquals(NodeRegistry.Failure.OCCUPIED, registry.adopt(handle, SECOND).failure());
    assertEquals(NodeRegistry.Failure.OFFLINE, registry.adopt(handle, FIRST).failure());
    assertEquals(
        NodeRegistry.Failure.STALE,
        registry
            .adopt(
                new NodeRegistry.Handle(WORLD, ID, handle.generation() + 5, handle.kind()), SECOND)
            .failure());
    assertEquals(before, registry.snapshot());
    var portable = registry.detach(other, SECOND).handle();
    assertEquals(
        NodeRegistry.Failure.OFFLINE,
        registry.adopt(portable, FIRST).failure(),
        "A portable lease cannot prove its item is gone");
  }

  /** U11: only a detached, current foreign-port lease is retired; roots never are. */
  @Test
  void retireRemovesOnlyDetachedPortLeasesAndFreesTheirSlot() {
    var registry = new NodeRegistry(WORLD, List.of(), () -> {});
    var placed = registry.create(ID, NodeRegistry.Kind.CONNECTOR, FIRST).handle();
    assertEquals(NodeRegistry.Failure.OCCUPIED, registry.retire(placed));
    var portable = registry.detach(placed, FIRST).handle();
    assertEquals(NodeRegistry.Failure.STALE, registry.retire(placed));
    assertEquals(NodeRegistry.Failure.OK, registry.retire(portable));
    assertFalse(registry.contains(ID));
    assertEquals(NodeRegistry.Failure.UNKNOWN, registry.retire(portable));
    assertEquals(NodeRegistry.Failure.OK, registry.admission(FIRST));
    assertTrue(new NodeRegistry(WORLD, registry.snapshot(), () -> {}).snapshot().isEmpty());
    var root = registry.create(new UUID(0, 2), NodeRegistry.Kind.CONTROLLER, SECOND).handle();
    var detachedRoot = registry.detach(root, SECOND).handle();
    assertThrows(IllegalArgumentException.class, () -> registry.retire(detachedRoot));
  }
}
