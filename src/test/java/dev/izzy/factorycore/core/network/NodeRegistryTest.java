package dev.izzy.factorycore.core.network;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
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
}
