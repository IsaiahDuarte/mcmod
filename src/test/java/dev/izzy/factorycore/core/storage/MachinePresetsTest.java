package dev.izzy.factorycore.core.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MachinePresetsTest {
  private static final MachinePresets.Authority AUTH =
      new MachinePresets.Authority(new UUID(0, 9), new UUID(0, 1), new UUID(0, 1), 1);

  private static NodeRegistry nodes() {
    return new NodeRegistry(new UUID(0, 10), List.of(), () -> {});
  }

  private static NodeRegistry.Handle node(NodeRegistry nodes, int id) {
    return nodes
        .create(
            new UUID(0, id),
            NodeRegistry.Kind.MACHINE_INTERFACE,
            new NodeRegistry.Position("minecraft:overworld", id))
        .handle();
  }

  @Test
  void principalScopeConfigGrantAndRevisionAreAllRequiredForFreshWork() {
    var nodes = nodes();
    var node = node(nodes, 3);
    var dirty = new AtomicInteger();
    var presets = new MachinePresets(nodes, List.of(), dirty::incrementAndGet);
    var filter = ResourceFilter.all(ResourceKind.ITEM);
    var deployment =
        presets.deploy(
            node, 1, MachinePresets.Face.NORTH, AUTH, MachinePresets.Mode.IMPORT, filter, 64);
    assertTrue(presets.matches(node, 1, MachinePresets.Face.NORTH, AUTH, deployment.revision()));
    assertFalse(presets.matches(node, 2, MachinePresets.Face.NORTH, AUTH, deployment.revision()));
    assertFalse(presets.matches(node, 1, MachinePresets.Face.EAST, AUTH, deployment.revision()));
    assertFalse(
        presets.matches(
            node,
            1,
            MachinePresets.Face.NORTH,
            new MachinePresets.Authority(new UUID(0, 8), AUTH.network(), AUTH.segment(), 1),
            deployment.revision()));
    assertFalse(
        presets.matches(
            node,
            1,
            MachinePresets.Face.NORTH,
            new MachinePresets.Authority(AUTH.principal(), AUTH.network(), new UUID(0, 2), 1),
            deployment.revision()));
    assertFalse(
        presets.matches(
            node,
            1,
            MachinePresets.Face.NORTH,
            new MachinePresets.Authority(AUTH.principal(), AUTH.network(), AUTH.segment(), 2),
            deployment.revision()));
    presets.prepareInvalidation(node.id());
    presets.invalidate(node.id());
    assertFalse(presets.matches(node, 1, MachinePresets.Face.NORTH, AUTH, deployment.revision()));
    assertEquals(MachinePresets.Mode.PAUSED, presets.get(node.id()).mode());
    assertEquals(AUTH, presets.get(node.id()).authority());
    assertEquals(2, dirty.get());
    var restored = new MachinePresets(nodes, presets.snapshot(), () -> {});
    assertFalse(restored.matches(node, 1, MachinePresets.Face.NORTH, AUTH, deployment.revision()));
    var next =
        restored.deploy(
            node,
            2,
            MachinePresets.Face.NORTH,
            AUTH,
            MachinePresets.Mode.STOCK,
            filter,
            Long.MAX_VALUE);
    assertEquals(3, next.revision());
    assertTrue(restored.matches(node, 2, MachinePresets.Face.NORTH, AUTH, next.revision()));
    var portable = nodes.detach(node, new NodeRegistry.Position("minecraft:overworld", 3)).handle();
    assertFalse(restored.matches(node, 2, MachinePresets.Face.NORTH, AUTH, next.revision()));
    assertThrows(
        IllegalStateException.class,
        () ->
            restored.deploy(
                portable,
                2,
                MachinePresets.Face.NORTH,
                AUTH,
                MachinePresets.Mode.STOCK,
                filter,
                64));
  }

  @Test
  void metadataAdmissionRejectsAtomicallyAndRemovalReclaimsTheCharge() {
    var nodes = nodes();
    var dirty = new AtomicInteger();
    var presets = new MachinePresets(nodes, List.of(), dirty::incrementAndGet);
    var terms = new ArrayList<ResourceFilter.Term>();
    for (int i = 0; i < 4; i++) {
      var data = new byte[60_000];
      data[0] = (byte) i;
      terms.add(
          new ResourceFilter.Exact(
              new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", data)));
    }
    var filter = new ResourceFilter(ResourceKind.ITEM, ResourceFilter.Mode.INCLUDE, terms);
    NodeRegistry.Handle failed = null;
    for (int i = 0; i < 100; i++) {
      var node = node(nodes, 100 + i);
      try {
        presets.deploy(
            node, 1, MachinePresets.Face.NORTH, AUTH, MachinePresets.Mode.EXPORT, filter, 64);
      } catch (IllegalStateException full) {
        failed = node;
        break;
      }
    }
    assertNotNull(failed);
    var old = presets.snapshot();
    long charge = presets.bytes();
    int changes = dirty.get();
    var reject = failed;
    assertThrows(
        IllegalStateException.class,
        () ->
            presets.deploy(
                reject,
                1,
                MachinePresets.Face.NORTH,
                AUTH,
                MachinePresets.Mode.IMPORT,
                filter,
                64));
    assertEquals(old, presets.snapshot());
    assertEquals(charge, presets.bytes());
    assertEquals(changes, dirty.get());
    var first = old.getFirst();
    var lease =
        new NodeRegistry.Handle(
            reject.world(),
            first.node(),
            first.leaseGeneration(),
            NodeRegistry.Kind.MACHINE_INTERFACE);
    presets.remove(lease);
    assertEquals(charge - first.bytes(), presets.bytes());
    assertNotNull(
        presets.deploy(
            reject, 1, MachinePresets.Face.NORTH, AUTH, MachinePresets.Mode.IMPORT, filter, 64));
  }

  @Test
  void futureLeaseDuplicateOwnerAndOverflowCannotRestoreOrMutate() {
    var nodes = nodes();
    var node = node(nodes, 3);
    var record =
        new MachinePresets.Deployment(
            node.id(),
            1,
            Long.MAX_VALUE,
            1,
            MachinePresets.Face.NORTH,
            AUTH,
            MachinePresets.Mode.IMPORT,
            ResourceFilter.all(ResourceKind.ENERGY),
            1000);
    assertThrows(
        IllegalArgumentException.class,
        () -> new MachinePresets(nodes, List.of(record, record), () -> {}));
    var future =
        new MachinePresets.Deployment(
            node.id(),
            2,
            1,
            1,
            MachinePresets.Face.NORTH,
            AUTH,
            record.mode(),
            record.filter(),
            1000);
    assertThrows(
        IllegalArgumentException.class, () -> new MachinePresets(nodes, List.of(future), () -> {}));
    var dirty = new AtomicInteger();
    var presets = new MachinePresets(nodes, List.of(record), dirty::incrementAndGet);
    assertThrows(ArithmeticException.class, () -> presets.prepareInvalidation(node.id()));
    assertThrows(ArithmeticException.class, () -> presets.invalidate(node.id()));
    assertEquals(record, presets.get(node.id()));
    assertEquals(0, dirty.get());
  }

  @Test
  void deploymentCountLimitRejectsWithoutCreatingAnotherOwner() {
    var nodes = nodes();
    var dirty = new AtomicInteger();
    var presets = new MachinePresets(nodes, List.of(), dirty::incrementAndGet);
    var filter = ResourceFilter.all(ResourceKind.ITEM);
    for (int i = 0; i < MachinePresets.MAX_DEPLOYMENTS; i++)
      presets.deploy(
          node(nodes, 1000 + i),
          1,
          MachinePresets.Face.NORTH,
          AUTH,
          MachinePresets.Mode.IMPORT,
          filter,
          64);
    var extra = node(nodes, 2000);
    long bytes = presets.bytes();
    int changes = dirty.get();
    assertThrows(
        IllegalStateException.class,
        () ->
            presets.deploy(
                extra, 1, MachinePresets.Face.NORTH, AUTH, MachinePresets.Mode.IMPORT, filter, 64));
    assertNull(presets.get(extra.id()));
    assertEquals(bytes, presets.bytes());
    assertEquals(changes, dirty.get());
    assertEquals(MachinePresets.MAX_DEPLOYMENTS, presets.snapshot().size());
  }
}
