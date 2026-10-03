package dev.izzy.factorycore.core.network;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.network.NetworkTopology.*;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NetworkTopologyTest {
  private static UUID id(int value) {
    return new UUID(0, value);
  }

  private static Node node(int value, Role role) {
    return new Node(id(value), role, "", true);
  }

  private static NetworkTopology topology(int depth) {
    return new NetworkTopology(id(10000), depth);
  }

  private static Gateway gate(int value, int up, int down, boolean loaded) {
    return new Gateway(id(value), id(up), id(down), GatewayPolicy.open(), loaded);
  }

  static int finish(NetworkTopology topology, int budget) {
    var validation = topology.beginValidation();
    int work = 0;
    for (int calls = 0; calls < 100000; calls++) {
      var result = validation.advance(budget);
      assertTrue(result.work() >= 0 && result.work() <= budget);
      work += result.work();
      if (result.done()) {
        assertTrue(result.published());
        return work;
      }
    }
    fail("Validation did not terminate under finite graph bounds");
    return -1;
  }

  @Test
  void ordinaryLoopsAndIndependentNetworksDoNotInventAuthority() {
    var topology = topology(8);
    for (var n :
        List.of(
            node(1, Role.ROOT),
            node(2, Role.CABLE),
            node(3, Role.STORAGE),
            node(4, Role.ROOT),
            node(5, Role.STORAGE),
            node(6, Role.CABLE))) topology.putNode(n);
    topology.connect(id(1), id(2));
    topology.connect(id(2), id(3));
    topology.connect(id(3), id(1));
    topology.connect(id(4), id(5));
    finish(topology, 1);
    assertEquals(Status.VALID, topology.membership(id(3)).status());
    assertEquals(id(1), topology.membership(id(3)).scope().network());
    assertEquals(id(4), topology.membership(id(5)).scope().network());
    assertEquals(Status.NO_ROOT, topology.membership(id(6)).status());
    var old = topology.membership(id(3)).scope();
    topology.connect(id(3), id(5));
    assertEquals(Status.STALE, topology.validate(old));
    assertEquals(Status.REBUILDING, topology.membership(id(3)).status());
    finish(topology, 17);
    assertEquals(Status.MULTIPLE_ROOTS, topology.membership(id(1)).status());
    assertEquals(Status.MULTIPLE_ROOTS, topology.membership(id(4)).status());
    topology.disconnect(id(3), id(5));
    finish(topology, 256);
    assertEquals(Status.VALID, topology.membership(id(3)).status());
    assertEquals(Status.VALID, topology.membership(id(5)).status());
  }

  @Test
  void gatewaysRejectBypassesCyclesMultipleParentsAndWrongRootDirection() {
    var topology = topology(8);
    topology.putNode(node(1, Role.ROOT));
    topology.putNode(node(2, Role.CABLE));
    topology.putNode(node(3, Role.CABLE));
    topology.putGateway(gate(101, 1, 2, true));
    topology.putGateway(gate(102, 2, 3, true));
    finish(topology, 5);
    assertEquals(id(102), topology.membership(id(3)).scope().segment());
    topology.connect(id(1), id(3));
    finish(topology, 3);
    assertNotEquals(Status.VALID, topology.membership(id(2)).status());
    topology.disconnect(id(1), id(3));
    topology.putGateway(gate(103, 3, 1, true));
    finish(topology, 1);
    assertEquals(Status.GATEWAY_CYCLE, topology.membership(id(1)).status());
    topology.removeGateway(id(103));
    topology.putGateway(gate(104, 1, 3, true));
    finish(topology, 2);
    assertEquals(Status.MULTIPLE_PARENTS, topology.membership(id(3)).status());
    topology.removeGateway(id(104));
    topology.connect(id(1), id(2));
    finish(topology, 7);
    assertEquals(Status.GATEWAY_BYPASS, topology.membership(id(3)).status());
    topology.disconnect(id(1), id(2));
    topology.putGateway(gate(101, 2, 1, true));
    finish(topology, 7);
    assertEquals(Status.ROOT_HAS_PARENT, topology.membership(id(1)).status());
  }

  @Test
  void gatewayAndStructuralUnloadSuspendDescendantsWithoutSiblingScopeLeak() {
    var topology = topology(8);
    topology.putNode(node(1, Role.ROOT));
    for (int i = 2; i <= 5; i++) topology.putNode(node(i, Role.CABLE));
    topology.putNode(node(6, Role.STORAGE));
    topology.putGateway(gate(101, 1, 2, true));
    topology.putGateway(gate(102, 2, 3, true));
    topology.putGateway(gate(103, 1, 4, true));
    topology.connect(id(3), id(5));
    topology.connect(id(4), id(6));
    finish(topology, 11);
    topology.putGateway(gate(101, 1, 2, false));
    finish(topology, 11);
    assertEquals(Status.VALID, topology.membership(id(1)).status());
    assertEquals(Status.UNLOADED, topology.membership(id(2)).status());
    assertEquals(Status.UNLOADED, topology.membership(id(5)).status());
    assertEquals(Status.VALID, topology.membership(id(4)).status());
    topology.putGateway(gate(101, 1, 2, true));
    topology.putNode(new Node(id(6), Role.STORAGE, "", false));
    finish(topology, 11);
    assertEquals(Status.VALID, topology.membership(id(4)).status());
    assertEquals(Status.UNLOADED, topology.membership(id(6)).status());
    topology.putNode(new Node(id(2), Role.CABLE, "", false));
    finish(topology, 11);
    assertEquals(Status.UNLOADED, topology.membership(id(3)).status());
    assertEquals(Status.VALID, topology.membership(id(4)).status());
    topology.putNode(node(2, Role.CABLE));
    topology.putNode(new Node(id(1), Role.ROOT, "", false));
    finish(topology, 11);
    for (int i = 1; i <= 6; i++) assertEquals(Status.UNLOADED, topology.membership(id(i)).status());
    topology.putNode(node(1, Role.ROOT));
    topology.putNode(node(6, Role.STORAGE));
    finish(topology, 11);
    assertEquals(Status.VALID, topology.membership(id(5)).status());
    topology.removeGateway(id(101));
    finish(topology, 11);
    assertEquals(Status.NO_ROOT, topology.membership(id(2)).status());
    assertEquals(Status.NO_ROOT, topology.membership(id(3)).status());
    assertEquals(Status.VALID, topology.membership(id(4)).status());
  }

  @Test
  void changedGenerationCannotPublishAndForeignOrForgedHandlesReject() {
    var topology = topology(8);
    topology.putNode(node(1, Role.ROOT));
    var build = topology.beginValidation();
    build.advance(1);
    topology.putNode(node(2, Role.CABLE));
    var abandoned = build.advance(256);
    assertTrue(abandoned.done());
    assertFalse(abandoned.published());
    assertEquals(0, abandoned.work());
    assertEquals(Status.REBUILDING, topology.membership(id(1)).status());
    finish(topology, 1);
    var scope = topology.membership(id(1)).scope();
    assertEquals(
        Status.FOREIGN,
        topology.validate(
            new Scope(
                id(9000), scope.generation(), scope.network(), scope.segment(), scope.node())));
    assertEquals(
        Status.STALE,
        topology.validate(
            new Scope(scope.region(), scope.generation(), id(99), scope.segment(), scope.node())));
    topology.putNode(node(1, Role.ROOT)); // Identical updates preserve handles.
    assertEquals(Status.VALID, topology.validate(scope));
    var completed = topology.beginValidation();
    var completedResult = completed.advance(256);
    assertTrue(completedResult.done());
    assertTrue(completedResult.published());
    topology.invalidate();
    assertEquals(Status.STALE, topology.validate(scope));
    assertEquals(Status.REBUILDING, topology.membership(id(1)).status());
    assertFalse(
        completed.advance(256).published(), "An old completed job is no longer authoritative");
    assertThrows(IllegalArgumentException.class, () -> build.advance(257));
  }

  @Test
  void depthAdmissionDegreeAndFullGraphWorkAreBounded() {
    assertThrows(IllegalArgumentException.class, () -> topology(33));
    var topology = topology(2);
    topology.putNode(node(1, Role.ROOT));
    for (int i = 2; i <= 4; i++) {
      topology.putNode(node(i, Role.CABLE));
      topology.putGateway(gate(100 + i, i - 1, i, true));
    }
    finish(topology, 256);
    assertEquals(Status.DEPTH_LIMIT, topology.membership(id(1)).status());
    topology.removeGateway(id(104));
    finish(topology, 256);
    assertEquals(Status.VALID, topology.membership(id(3)).status());
    assertEquals(Status.NO_ROOT, topology.membership(id(4)).status());
    var full = topology(32);
    for (int i = 1; i <= NetworkTopology.MAX_NODES; i++)
      full.putNode(node(i, i == 1 ? Role.ROOT : Role.CABLE));
    assertThrows(IllegalStateException.class, () -> full.putNode(node(5000, Role.CABLE)));
    for (int i = 2; i <= 9; i++) full.connect(id(1), id(i));
    assertThrows(IllegalStateException.class, () -> full.connect(id(1), id(10)));
    assertThrows(IllegalArgumentException.class, () -> full.putGateway(gate(1, 2, 3, true)));
    int visits = finish(full, 256);
    assertTrue(visits < 8 * NetworkTopology.MAX_NODES + 64);
    full.removeNode(id(1));
    finish(full, 256);
    for (int i = 2; i <= 9; i++) assertEquals(Status.NO_ROOT, full.membership(id(i)).status());
  }

  @Test
  void generatedGatewayUnloadsKeepPrivateScopesAndSuspendExactlyTheDescendants() {
    long seed = 0x70F0106L;
    var random = new Random(seed);
    var topology = topology(32);
    int count = 30;
    var parents = new int[count + 1];
    topology.putNode(node(1, Role.ROOT));
    for (int i = 2; i <= count; i++) {
      topology.putNode(node(i, Role.INTERFACE));
      parents[i] = (i - 2) / 2 + 1;
      topology.putGateway(gate(100 + i, parents[i], i, true));
    }
    for (int operation = 0; operation < 300; operation++) {
      int unloaded = 2 + random.nextInt(count - 1);
      topology.putGateway(gate(100 + unloaded, parents[unloaded], unloaded, false));
      finish(topology, 1 + random.nextInt(256));
      for (int target = 1; target <= count; target++) {
        boolean descendant = false;
        for (int cursor = target; cursor > 1; cursor = parents[cursor])
          if (cursor == unloaded) descendant = true;
        assertEquals(
            descendant ? Status.UNLOADED : Status.VALID,
            topology.membership(id(target)).status(),
            "seed=" + seed + " operation=" + operation + " target=" + target);
        if (!descendant)
          assertEquals(
              target == 1 ? id(1) : id(100 + target),
              topology.membership(id(target)).scope().segment());
      }
      topology.putGateway(gate(100 + unloaded, parents[unloaded], unloaded, true));
    }
  }

  @Test
  void largeSegmentFanoutIsIncrementalAndNeverEnumeratesPrivateChildren() {
    var topology = topology(8);
    for (int i = 1; i <= 1024; i++) {
      topology.putNode(node(i, i == 1 ? Role.ROOT : Role.CABLE));
      if (i > 1) topology.connect(id(i - 1), id(i));
    }
    for (int i = 1025; i <= 4096; i++) {
      topology.putNode(new Node(id(i), Role.INTERFACE, "machines", true));
      topology.putGateway(gate(20000 + i, (i - 1025) / 3 + 1, i, true));
    }
    int work = finish(topology, 1);
    assertTrue(work < 12 * NetworkTopology.MAX_NODES);
    var root = topology.membership(id(1)).scope();
    assertTrue(topology.machines(root, "machines", null, 64).machines().isEmpty());
    var child = topology.membership(id(4096)).scope();
    assertEquals(List.of(child), topology.machines(child, "machines", null, 64).machines());
    assertEquals(List.of(new Ancestor(id(1), GatewayPolicy.open())), topology.ancestors(child));
  }
}
